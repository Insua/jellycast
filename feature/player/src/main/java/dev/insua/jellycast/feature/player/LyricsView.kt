package dev.insua.jellycast.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 定位节点用的测试标签,预留给未来的 androidTest,不依赖文案/位置做断言。 */
object LyricsViewTestTags {
    const val PLACEHOLDER = "lyrics_placeholder"

    /** 歌词区容器:真机测试用它断言"歌词区到底分到了多少高度"。 */
    const val CONTAINER = "lyrics_container"
    fun line(index: Int) = "lyrics_line_$index"
}

/**
 * 歌词区该显示什么:三选一,刻意没有 ERROR 分支——字幕铁律要求任何字幕相关异常都降级为
 * "无字幕",绝不向上抛错,所以这里在类型层面就不给"把字幕失败渲染成错误提示"留口子。
 * [PlayerScreen] 用它决定"加载中"要不要直接显示 spinner(不复用 [LyricsView] 内部的占位态,
 * 语义不同:加载中 vs. 确认没有可用字幕);[LyricsView] 内部只关心 PLACEHOLDER/CONTENT 这一半。
 */
enum class LyricsDisplayState { DISABLED, LOADING, PLACEHOLDER, CONTENT }

/**
 * [lyricsEnabled] 是设置里的「歌词式字幕」开关(复审 Minor 6:它此前只被 `SettingsViewModel` 读来
 * 显示开关状态,`PlayerScreen`/[LyricsView] 从不查它——关掉照样滚字幕)。
 *
 * 关掉时优先级最高:既不显示歌词,也不显示"此内容无文本字幕"占位(那是"片源没有字幕"的意思,
 * 和"用户自己关掉了"是两件事,混在一起会让用户以为片源有问题),也不显示加载转圈。
 */
fun lyricsDisplayState(
    isLoading: Boolean,
    timeline: SubtitleTimeline,
    lyricsEnabled: Boolean = true,
): LyricsDisplayState = when {
    !lyricsEnabled -> LyricsDisplayState.DISABLED
    isLoading -> LyricsDisplayState.LOADING
    timeline.lines.isEmpty() -> LyricsDisplayState.PLACEHOLDER
    else -> LyricsDisplayState.CONTENT
}

/**
 * 点击歌词行的落地处:把该行 [SubtitleLine.startMs] 交给 [onSeek]。从 `LyricsLineRow` 的
 * `clickable` lambda 里抽出来单独命名,是这个模块里此前完全没有测试覆盖的一段逻辑(点击第几行
 * 应该 seek 到哪个时间点)——Compose `clickable` 手势本身能不能触发是渲染层的事,留给
 * androidTest/人工验收,这里只测"点击回调收到正确的行时,算出的目标位置对不对"这份纯逻辑。
 */
internal fun onLyricsLineClicked(line: SubtitleLine, onSeek: (Long) -> Unit) {
    onSeek(line.startMs)
}

/**
 * 歌词自动跟随的暂停/恢复计时状态机,从 [LyricsView] 原来内联的 `LaunchedEffect(isDragged)`
 * 里抽出来,方便脱离 Compose 直接用虚拟时间单测(见任务报告 Finding 2)。
 *
 * 语义和原来完全一致:拖拽开始 ([onDragStateChanged] 传 true)立即暂停跟随;拖拽结束后等待
 * [resumeDelayMs] 恢复。每次调用都会取消上一个还没触发的"恢复"协程再重新决定——所以拖拽期间
 * 反复触摸不会提前恢复跟随,和原 `LaunchedEffect(isDragged)` 每次 key 变化都重启的语义一致。
 */
class LyricsAutoFollowController(
    private val scope: CoroutineScope,
    private val resumeDelayMs: Long = 3000L,
) {
    private val _autoFollow = MutableStateFlow(true)
    val autoFollow: StateFlow<Boolean> = _autoFollow.asStateFlow()

    private var resumeJob: Job? = null

    fun onDragStateChanged(isDragged: Boolean) {
        resumeJob?.cancel()
        if (isDragged) {
            _autoFollow.value = false
        } else {
            resumeJob = scope.launch {
                delay(resumeDelayMs)
                _autoFollow.value = true
            }
        }
    }
}

/**
 * 歌词式字幕视图:对标 Apple Music / 小宇宙的歌词滚动。当前行由
 * [dev.insua.jellycast.model.SubtitleTimeline.indexAt] 算出(已在 core:model 单测覆盖的线性扫描——
 * 缺陷 2/设计文档 §3.4 之后不再是二分查找,这里绝不重写第二份查找逻辑),点击任意行只做一件事——
 * 把该行 [SubtitleLine.startMs] 交给
 * [onSeek],至于"seek 在转码音频流上到底怎么实现"完全不是这一层的事(见 [PlayerViewModel.onSeek])。
 *
 * 间隙期处理(设计决策,详见任务报告):[SubtitleTimeline.indexAt] 在两行之间返回 -1 ——
 * 断言就是这样写的,以断言为准。UI 层不会因为返回 -1 就摘掉上一行的高亮跳回"未读"态(那样会像
 * 复读机一样在每两行之间闪烁归零);而是把"当前"与"已读"拆成两种独立视觉态:[currentIndex] 为 -1
 * 时,最后一次真正命中的行保持"已读"(半透明加深),但不再是"当前"(不放大、不上主题色)。
 * 自动滚动只在 [currentIndex] >= 0 时触发,间隙期不触发新的滚动动作,画面因此是静止的,不会有
 * "跨过一行结尾就抖一下"的观感。
 */
@Composable
fun LyricsView(
    timeline: SubtitleTimeline,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // 全支线复审 Important:候选字幕轨存在但全被弹幕信号(标题关键字或解析后密度异常)排除时,
    // [PlayerUiState.subtitleSkippedAsDanmaku] 为 true——占位文案要能区分"这一集真的没有字幕"
    // 和"有字幕但被判定为弹幕丢弃了",后者不该悄无声息。
    subtitleSkippedAsDanmaku: Boolean = false,
) {
    if (lyricsDisplayState(isLoading = false, timeline = timeline) == LyricsDisplayState.PLACEHOLDER) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (subtitleSkippedAsDanmaku) {
                    "识别到疑似弹幕的字幕轨,已跳过——本集暂不显示字幕"
                } else {
                    "此内容无文本字幕"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .testTag(LyricsViewTestTags.PLACEHOLDER),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val coroutineScope = rememberCoroutineScope()
    val autoFollowController = remember {
        LyricsAutoFollowController(coroutineScope, resumeDelayMs = MANUAL_SCROLL_RESUME_DELAY_MS)
    }
    val autoFollow by autoFollowController.autoFollow.collectAsState()

    // 用户手指按住列表(拖拽)期间暂停自动跟随;松手后 3 秒恢复——计时逻辑在
    // [LyricsAutoFollowController] 里(可脱离 Compose 单测),这里只负责把 isDragged 的变化转发过去。
    LaunchedEffect(isDragged) {
        autoFollowController.onDragStateChanged(isDragged)
    }

    val currentIndex = timeline.indexAt(positionMs)
    var lastHighlightedIndex by remember { mutableIntStateOf(-1) }
    if (currentIndex >= 0) lastHighlightedIndex = currentIndex
    // 间隙期(currentIndex == -1)用上一次真正命中的行作为"已读边界",不改变高亮落点。
    val readBoundary = if (currentIndex >= 0) currentIndex else lastHighlightedIndex

    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(LyricsViewTestTags.CONTAINER)) {
        // 视口高度直接取自 BoxWithConstraints 的约束(像素),**不再**读 listState.layoutInfo ——
        // 这是"当前行贴在边缘、上方没有上下文"的第二个成因:LaunchedEffect 在首次组合后、首次
        // 布局完成前就跑了,那时 layoutInfo.viewportSize.height 还是 0,居中偏移被算成 0,
        // 于是当前行被顶到视口最上沿。约束在组合期就是已知的,不存在这个时序窗口。
        val viewportPx = constraints.maxHeight
        // 上下各留半个视口高度的空白。歌词区变高之后重新评估过:这段留白**不占用可视高度**
        // (contentPadding 只加在滚动内容的首尾,视口本身不变),它的唯一作用是让第一行/最后一行
        // 也能被滚到视口正中央而不是卡在列表边缘——在矮视口下它同样不是挤压的原因,故保留。
        val verticalPaddingPx = viewportPx / 2
        val verticalPadding = with(LocalDensity.current) { verticalPaddingPx.toDp() }

        LaunchedEffect(currentIndex, autoFollow, viewportPx) {
            if (currentIndex < 0 || !autoFollow || viewportPx <= 0) return@LaunchedEffect
            // LazyColumn 把 contentPadding 也算进滚动内容:animateScrollToItem(index, offset) 最终把
            // 该行顶边放在"视口顶 + beforeContentPadding - offset"处(真机实测,见任务报告)。
            // 要让这一行居中,目标顶边是 (视口高 - 行高) / 2,于是
            //   offset = beforeContentPadding - 目标顶边
            // 原来的实现漏掉了 beforeContentPadding 这一项,当前行因此被推到视口底部并被裁掉。
            fun offsetFor(itemHeight: Int) = verticalPaddingPx - (viewportPx - itemHeight) / 2
            fun measuredHeight() = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex }?.size
            // 首帧时目标行还没被测量过,先用估算值把它滚到大致中央;滚完拿到真实行高再校正一次
            // (当前行是放大加粗的 titleLarge,和估算值差得比较多)。
            val assumedHeight = measuredHeight() ?: ESTIMATED_LINE_HEIGHT_PX
            listState.animateScrollToItem(currentIndex, offsetFor(assumedHeight))
            val actualHeight = measuredHeight()
            if (actualHeight != null && actualHeight != assumedHeight) {
                listState.animateScrollToItem(currentIndex, offsetFor(actualHeight))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = verticalPadding),
        ) {
            itemsIndexed(timeline.lines, key = { index, _ -> index }) { index, line ->
                LyricsLineRow(
                    line = line,
                    isCurrent = index == currentIndex,
                    isRead = index <= readBoundary && index != currentIndex,
                    onClick = { onLyricsLineClicked(line, onSeek) },
                    modifier = Modifier.testTag(LyricsViewTestTags.line(index)),
                )
            }
        }
    }
}

@Composable
private fun LyricsLineRow(
    line: SubtitleLine,
    isCurrent: Boolean,
    isRead: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        isRead -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    Text(
        text = line.text,
        style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

private const val MANUAL_SCROLL_RESUME_DELAY_MS = 3000L
private const val ESTIMATED_LINE_HEIGHT_PX = 140
