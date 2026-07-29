package dev.insua.jellycast.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** [MiniPlayerBarTest] 用来定位节点的测试标签,避免依赖文案/图标做断言。 */
object MiniPlayerBarTestTags {
    const val ROOT = "mini_player_bar"
    const val PLAY_PAUSE_BUTTON = "mini_player_bar_play_pause"
    const val SKIP_NEXT_BUTTON = "mini_player_bar_skip_next"
}

/**
 * 常驻迷你播放条:高 64dp,左侧方形封面 + 中间双行文字 + 右侧播放/暂停 + 下一集,底部 2dp 进度条。
 * 整条可点击展开全屏播放页(Task 20)。播放/暂停、下一集按钮独立可点,不冒泡到整条的 onExpand。
 *
 * [hasNext] 为 false(队列没有下一条可以推进,比如单集播放/已在最后一集)时下一集按钮禁用——
 * 保留在布局里而不是整个消失,避免播放/暂停按钮的位置随播放状态左右跳动。
 */
@Composable
fun MiniPlayerBar(
    title: String,
    subtitle: String,
    posterUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "展开播放页", role = Role.Button, onClick = onExpand)
            .testTag(MiniPlayerBarTestTags.ROOT),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.testTag(MiniPlayerBarTestTags.PLAY_PAUSE_BUTTON),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                )
            }
            IconButton(
                onClick = onSkipNext,
                enabled = hasNext,
                modifier = Modifier.testTag(MiniPlayerBarTestTags.SKIP_NEXT_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "下一集",
                    tint = if (hasNext) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current.copy(alpha = 0.38f)
                    },
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}
