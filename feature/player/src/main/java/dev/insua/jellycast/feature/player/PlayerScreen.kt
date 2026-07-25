package dev.insua.jellycast.feature.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全屏播放页——产品门面。铁律:全程不渲染视频,不引入 media3-ui,不创建 PlayerView,不绑定
 * Surface。大封面占据"专辑封面"的位置,背景是封面主色渐变(设计文档 §6 的核心视觉决定)。
 *
 * [baseUrl] 用于拼封面 URL(和 HomeScreen/LibraryScreen 同样的模式),默认空串——:feature:player
 * 目前还没有接入"当前激活服务器"的会话解析,那是 Task 22 导航装配的职责。
 */
@Composable
fun PlayerScreen(
    onCollapse: () -> Unit,
    baseUrl: String = "",
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val mediaItem = uiState.mediaItem
    val posterUrl = if (baseUrl.isBlank()) null else mediaItem?.posterUrl(baseUrl)

    val context = LocalContext.current
    var coverColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(posterUrl) {
        coverColor = extractDominantColor(context, posterUrl)
    }
    val defaultGradientColor = MaterialTheme.colorScheme.primaryContainer
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            (coverColor ?: defaultGradientColor).copy(alpha = 0.55f),
            MaterialTheme.colorScheme.background,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 20.dp),
    ) {
        TopBar(seriesName = mediaItem?.seriesName.orEmpty(), onCollapse = onCollapse)

        Spacer(Modifier.height(24.dp))
        CoverArt(imageUrl = posterUrl, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(20.dp))
        TitleBlock(mediaItem)

        Spacer(Modifier.height(16.dp))
        // 字幕加载中显示 loading,不复用 LyricsView 的"无字幕"占位文案——两者语义不同
        // (加载中 vs. 确认没有可用字幕),铁律要求字幕失败/加载都绝不打断播放,这里只是换一种展示。
        if (uiState.isSubtitleLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LyricsView(
                timeline = uiState.subtitleTimeline,
                positionMs = uiState.positionMs,
                onSeek = viewModel::onSeek,
                modifier = Modifier.weight(1f),
            )
        }

        ProgressSection(
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            onSeek = viewModel::onSeek,
        )

        Spacer(Modifier.height(8.dp))
        ControlsRow(
            isPlaying = uiState.isPlaying,
            rewindSeconds = uiState.rewindSeconds,
            forwardSeconds = uiState.forwardSeconds,
            onPlayPause = viewModel::onPlayPause,
            onSkipBack = viewModel::onSkipBack,
            onSkipForward = viewModel::onSkipForward,
        )

        Spacer(Modifier.height(8.dp))
        ToolbarRow(
            playbackSpeed = uiState.playbackSpeed,
            sleepTimerMinutesRemaining = uiState.sleepTimerMinutesRemaining,
            onCycleSpeed = viewModel::onCycleSpeed,
            onSetSleepTimer = viewModel::onSetSleepTimer,
            onCycleSubtitleTrack = viewModel::onCycleSubtitleTrack,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TopBar(seriesName: String, onCollapse: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.ExpandMore, contentDescription = "收起")
        }
        Text(
            text = seriesName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CoverArt(imageUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.78f)
            .aspectRatio(1f)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TitleBlock(mediaItem: MediaItem?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = mediaItem?.name.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val subtitle = mediaItem?.displaySubtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 进度条拖动和歌词行点击走同一条路:只在松手时调一次 [onSeek](拖拽中不断 seek 会不断触发
 * "重新 resolve+prepare",既浪费网络也没有意义)。拖拽期间用本地状态覆盖显示值,不被
 * ViewModel 轮询回来的旧位置打断。
 */
@Composable
private fun ProgressSection(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var draggingFraction by remember { mutableStateOf<Float?>(null) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    val playedFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayedFraction = draggingFraction ?: playedFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayedFraction,
            onValueChange = { draggingFraction = it },
            onValueChangeFinished = {
                val fraction = draggingFraction ?: return@Slider
                onSeek((fraction * safeDuration).toLong())
                draggingFraction = null
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimestamp(positionMs), style = MaterialTheme.typography.labelMedium)
            Text(
                "已听 ${formatTimestamp(positionMs)} / ${formatTimestamp(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(icon = Icons.Filled.FastRewind, seconds = rewindSeconds, onClick = onSkipBack)

        IconButton(onClick = onPlayPause, modifier = Modifier.width(72.dp).height(72.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier = Modifier.fillMaxSize(),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        SkipButton(icon = Icons.Filled.FastForward, seconds = forwardSeconds, onClick = onSkipForward)
    }
}

@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, seconds: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Min)) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null)
        }
        Text("${seconds}s", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToolbarRow(
    playbackSpeed: Float,
    sleepTimerMinutesRemaining: Int?,
    onCycleSpeed: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onCycleSubtitleTrack: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ToolbarAction(icon = Icons.Filled.Speed, label = "${playbackSpeed}x", onClick = onCycleSpeed)
        ToolbarAction(
            icon = Icons.Filled.Bedtime,
            label = sleepTimerMinutesRemaining?.let { "${it}分" } ?: "定时",
            onClick = { onSetSleepTimer(nextSleepTimerOption(sleepTimerMinutesRemaining)) },
        )
        // 音轨切换需要在 ExoPlayer TrackSelector 上做覆盖选择,超出本 Task 范围(见任务报告),
        // 这里先保留入口位置,点击暂不生效。
        ToolbarAction(icon = Icons.Filled.Queue, label = "音轨", onClick = {})
        ToolbarAction(icon = Icons.Filled.ClosedCaption, label = "字幕", onClick = onCycleSubtitleTrack)
        // "下一集"依赖 Task 22 才会接上的真实播放队列,这里同样只保留入口。
        ToolbarAction(icon = Icons.Filled.SkipNext, label = "下一集", onClick = {})
    }
}

@Composable
private fun ToolbarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Min)) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun nextSleepTimerOption(current: Int?): Int? = when (current) {
    null -> 15
    15 -> 30
    30 -> 45
    45 -> 60
    else -> null
}

private fun formatTimestamp(ms: Long): String {
    val duration = ms.coerceAtLeast(0L).milliseconds
    val totalSeconds = duration.inWholeSeconds
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 封面主色提取(Task 16 明确留给本 Task 的活):用 Coil 3 的单例 [coil3.ImageLoader] 把封面解码成
 * Bitmap,均匀网格采样求平均色,作为播放页背景渐变的起点。刻意不引入 androidx.palette——项目
 * 版本目录里没有这个依赖,而"渐变背景"这种模糊用途,均匀采样平均色已经够用。
 *
 * 任何失败(没有封面 URL、网络失败、非位图图像、解码异常)都用 [runCatching] 静默降级为 null,
 * 调用方回退到主题默认渐变色——和字幕/进度上报同样的"次要功能失败不得影响主流程"原则,这里
 * 绝不能因为取色失败就让播放页崩掉或者卡住。
 */
private suspend fun extractDominantColor(context: Context, imageUrl: String?): Color? {
    if (imageUrl.isNullOrBlank()) return null
    return runCatching {
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context).data(imageUrl).build()
        val result = loader.execute(request) as? SuccessResult ?: return@runCatching null
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@runCatching null
        bitmap.averageColor()
    }.getOrNull()
}

private fun Bitmap.averageColor(): Color {
    val stepX = max(1, width / SAMPLE_GRID)
    val stepY = max(1, height / SAMPLE_GRID)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0
    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val pixel = getPixel(x, y)
            r += (pixel shr 16) and 0xFF
            g += (pixel shr 8) and 0xFF
            b += pixel and 0xFF
            count++
            y += stepY
        }
        x += stepX
    }
    if (count == 0) return Color(0xFF2A2A2A)
    return Color(red = (r / count) / 255f, green = (g / count) / 255f, blue = (b / count) / 255f, alpha = 1f)
}

private const val SAMPLE_GRID = 8
