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
    fun line(index: Int) = "lyrics_line_$index"
}

/**
 * 歌词区该显示什么:三选一,刻意没有 ERROR 分支——字幕铁律要求任何字幕相关异常都降级为
 * "无字幕",绝不向上抛错,所以这里在类型层面就不给"把字幕失败渲染成错误提示"留口子。
 * [PlayerScreen] 用它决定"加载中"要不要直接显示 spinner(不复用 [LyricsView] 内部的占位态,
 * 语义不同:加载中 vs. 确认没有可用字幕);[LyricsView] 内部只关心 PLACEHOLDER/CONTENT 这一半。
 */
enum class LyricsDisplayState { LOADING, PLACEHOLDER, CONTENT }

fun lyricsDisplayState(isLoading: Boolean, timeline: SubtitleTimeline): LyricsDisplayState = when {
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
 * [dev.insua.jellycast.model.SubtitleTimeline.indexAt] 算出(已在 core:model 单测覆盖的二分查找,
 * 这里绝不重写第二份查找逻辑),点击任意行只做一件事——把该行 [SubtitleLine.startMs] 交给
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
) {
    if (lyricsDisplayState(isLoading = false, timeline = timeline) == LyricsDisplayState.PLACEHOLDER) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "此内容无文本字幕",
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

    LaunchedEffect(currentIndex, autoFollow) {
        if (currentIndex < 0 || !autoFollow) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height
        val measuredHeight = layoutInfo.visibleItemsInfo.find { it.index == currentIndex }?.size
        val itemHeight = measuredHeight ?: ESTIMATED_LINE_HEIGHT_PX
        // 让当前行的可视中心落在视口垂直中央:item 顶边应该停在 (视口高 - 行高) / 2 处。
        // animateScrollToItem 的 scrollOffset 是"再向前滚多少像素",所以取负值往回退。
        val centeredOffset = -((viewportHeight - itemHeight) / 2).coerceAtLeast(0)
        listState.animateScrollToItem(currentIndex, centeredOffset)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 上下各留半个视口高度的空白,配合上面的居中滚动计算——这样第一行/最后一行也能被滚到
        // 视口正中央,而不是卡在列表边缘。
        val halfViewport = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = halfViewport),
        ) {
            itemsIndexed(timeline.lines, key = { index, _ -> index }) { index, line ->
                LyricsLineRow(
                    line = line,
                    isCurrent = index == currentIndex,
                    isRead = index <= readBoundary && index != currentIndex,
                    onClick = { onLyricsLineClicked(line, onSeek) },
                )
            }
        }
    }
}

@Composable
private fun LyricsLineRow(line: SubtitleLine, isCurrent: Boolean, isRead: Boolean, onClick: () -> Unit) {
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

private const val MANUAL_SCROLL_RESUME_DELAY_MS = 3000L
private const val ESTIMATED_LINE_HEIGHT_PX = 140
