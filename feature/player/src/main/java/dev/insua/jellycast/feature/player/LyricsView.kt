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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import kotlinx.coroutines.delay

/** 定位节点用的测试标签,预留给未来的 androidTest,不依赖文案/位置做断言。 */
object LyricsViewTestTags {
    const val PLACEHOLDER = "lyrics_placeholder"
    fun line(index: Int) = "lyrics_line_$index"
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
    if (timeline.lines.isEmpty()) {
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
    var autoFollow by remember { mutableStateOf(true) }

    // 用户手指按住列表(拖拽)期间暂停自动跟随;松手后 3 秒恢复。isDragged 每次变化都会取消上一个
    // delay 协程重新开始计时,所以拖拽期间反复触摸不会提前恢复跟随。
    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoFollow = false
        } else {
            delay(MANUAL_SCROLL_RESUME_DELAY_MS)
            autoFollow = true
        }
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
                    onClick = { onSeek(line.startMs) },
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
