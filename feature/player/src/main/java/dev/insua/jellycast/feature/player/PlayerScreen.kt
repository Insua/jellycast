package dev.insua.jellycast.feature.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** 定位节点用的测试标签,不依赖文案/位置做断言。 */
object PlayerScreenTestTags {
    /** 封面正方形本体:真机测试用它断言「播放中收起、暂停时恢复接近满宽」。 */
    const val COVER = "player_cover"
}

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
    PlayerScreenContent(
        uiState = uiState,
        posterUrl = if (baseUrl.isBlank()) null else uiState.mediaItem?.posterUrl(baseUrl),
        onCollapse = onCollapse,
        onSeek = viewModel::onSeek,
        onPlayPause = viewModel::onPlayPause,
        onSkipBack = viewModel::onSkipBack,
        onSkipForward = viewModel::onSkipForward,
        onCycleSpeed = viewModel::onCycleSpeed,
        onSetSleepTimer = viewModel::onSetSleepTimer,
        onCycleSubtitleTrack = viewModel::onCycleSubtitleTrack,
        onCycleAudioTrack = viewModel::onCycleAudioTrack,
        onSkipToNext = viewModel::onSkipToNext,
    )
}

/**
 * 播放页的无状态本体——和 `HomeScreenContent` 同样的模式:把 ViewModel/Hilt 摘出去,布局本身
 * 才能在 androidTest 里被真机渲染并断言"当前歌词行是不是真的看得见"。v3 的离线提示条教训
 * (节点存在于语义树里、却被挤出视口)说明这类问题只有真机布局测试能抓到。
 */
@Composable
fun PlayerScreenContent(
    uiState: PlayerUiState,
    posterUrl: String? = null,
    onCollapse: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onPlayPause: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onCycleSpeed: () -> Unit = {},
    onSetSleepTimer: (SleepTimerOption?) -> Unit = {},
    onCycleSubtitleTrack: () -> Unit = {},
    onCycleAudioTrack: () -> Unit = {},
    onSkipToNext: () -> Unit = {},
) {
    val mediaItem = uiState.mediaItem

    val context = LocalContext.current
    var coverColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(posterUrl) {
        coverColor = extractDominantColor(context, posterUrl)
    }
    // 播放中收起、暂停时展开——两个尺寸之间**动画过渡**,不是硬切(用户 2026-07-29 的决定)。
    // 歌词区的居中偏移会跟着视口高度逐帧重算(见 [LyricsView]),所以动画过程中当前行不会被裁。
    val coverFraction by animateFloatAsState(
        targetValue = if (uiState.isPlaying) COVER_FRACTION_PLAYING else COVER_FRACTION_PAUSED,
        animationSpec = tween(durationMillis = COVER_TRANSITION_MS),
        label = "coverFraction",
    )

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

        Spacer(Modifier.height(16.dp))
        // 封面 + 标题 + 歌词共用这一块"剩余空间"(固定控件——进度条/控制行/工具栏——排完之后的
        // 部分)。封面在这块区域里拿一个**会动画变化的**边长:
        //   播放中 → [COVER_FRACTION_PLAYING] 收成小封面,把高度让给歌词(招牌功能是"当前行任何
        //            时刻完整可见 + 上下各一行上下文",这条优先于封面尺寸);
        //   暂停时 → [COVER_FRACTION_PAUSED] 恢复到 Task 1 之前的观感(接近满宽),大封面是
        //            "感觉像播客"的产品身份(v1 设计文档 §6)。
        // 这是音乐 App 的常见做法,也是用户 2026-07-29 明确选定的方案。
        // 边长同时被 [COVER_MAX_REGION_SHARE] 夹住:矮屏上封面绝不会把标题和歌词挤没。
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val coverSide = (maxWidth * coverFraction).coerceAtMost(maxHeight * COVER_MAX_REGION_SHARE)
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(coverSide),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverArt(imageUrl = posterUrl, side = coverSide)
                }

                Spacer(Modifier.height(16.dp))
                TitleBlock(mediaItem)

                Spacer(Modifier.height(16.dp))
                // 字幕加载中显示 loading,不复用 LyricsView 的"无字幕"占位文案——两者语义不同
                // (加载中 vs. 确认没有可用字幕),铁律要求字幕失败/加载都绝不打断播放,这里只是换一种展示。
                // lyricsDisplayState 结构上没有 ERROR 分支,加载/空 timeline 都不会被渲染成错误提示。
                // DISABLED 是复审 Minor 6 接上的设置开关:用户主动关掉歌词时给一句明确的说明,而不是
                // 让这一格看起来像"片源没有字幕"。
                when (lyricsDisplayState(uiState.isSubtitleLoading, uiState.subtitleTimeline, uiState.lyricsEnabled)) {
                    LyricsDisplayState.DISABLED -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "歌词式字幕已在设置中关闭",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                    LyricsDisplayState.LOADING -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    LyricsDisplayState.PLACEHOLDER, LyricsDisplayState.CONTENT -> LyricsView(
                        timeline = uiState.subtitleTimeline,
                        positionMs = uiState.positionMs,
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f),
                        subtitleIsDanmakuFallback = uiState.subtitleIsDanmakuFallback,
                    )
                }
            }
        }

        ProgressSection(
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(8.dp))
        ControlsRow(
            isPlaying = uiState.isPlaying,
            rewindSeconds = uiState.rewindSeconds,
            forwardSeconds = uiState.forwardSeconds,
            onPlayPause = onPlayPause,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
        )

        Spacer(Modifier.height(8.dp))
        ToolbarRow(
            playbackSpeed = uiState.playbackSpeed,
            sleepTimerOption = uiState.sleepTimerOption,
            onCycleSpeed = onCycleSpeed,
            onSetSleepTimer = onSetSleepTimer,
            onCycleSubtitleTrack = onCycleSubtitleTrack,
            onCycleAudioTrack = onCycleAudioTrack,
            onSkipToNext = onSkipToNext,
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

/**
 * 封面永远是正方形,边长由调用方算好([side] 已经把"播放中收起/暂停时展开"的动画值和矮屏上限
 * 都算进去了)——这里不再自己 BoxWithConstraints 取尺寸,免得同一个尺寸有两处真相。
 */
@Composable
private fun CoverArt(imageUrl: String?, side: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(side)
            .testTag(PlayerScreenTestTags.COVER)
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
    // 复审 Critical 1 第四条:总时长未知时**不能**把它钳成 1L —— 那样进度条永远显示 100%,
    // 用户一拖就 onSeek(≈0) 把这一集从头开始。总时长权威来自元数据 runTimeMs(见 PlayerViewModel);
    // 真的拿不到时(接口没返回 RunTimeTicks)就禁用滑块、时长显示 --:--,而不是给一个会误伤的假进度。
    val hasDuration = durationMs > 0L
    val playedFraction = if (hasDuration) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val displayedFraction = draggingFraction ?: playedFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayedFraction,
            enabled = hasDuration,
            onValueChange = { draggingFraction = it },
            onValueChangeFinished = {
                val fraction = draggingFraction ?: return@Slider
                if (hasDuration) onSeek((fraction * durationMs).toLong())
                draggingFraction = null
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimestamp(positionMs), style = MaterialTheme.typography.labelMedium)
            Text(
                "已听 ${formatTimestamp(positionMs)} / ${if (hasDuration) formatTimestamp(durationMs) else "--:--"}",
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
    sleepTimerOption: SleepTimerOption?,
    onCycleSpeed: () -> Unit,
    onSetSleepTimer: (SleepTimerOption?) -> Unit,
    onCycleSubtitleTrack: () -> Unit,
    onCycleAudioTrack: () -> Unit,
    onSkipToNext: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ToolbarAction(icon = Icons.Filled.Speed, label = "${playbackSpeed}x", onClick = onCycleSpeed)
        ToolbarAction(
            icon = Icons.Filled.Bedtime,
            label = sleepTimerLabel(sleepTimerOption),
            onClick = { onSetSleepTimer(nextSleepTimerOption(sleepTimerOption)) },
        )
        ToolbarAction(icon = Icons.Filled.Queue, label = "音轨", onClick = onCycleAudioTrack)
        ToolbarAction(icon = Icons.Filled.ClosedCaption, label = "字幕", onClick = onCycleSubtitleTrack)
        ToolbarAction(icon = Icons.Filled.SkipNext, label = "下一集", onClick = onSkipToNext)
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

/** 睡眠定时选项循环:关闭 → 15 → 30 → 45 → 60 分钟 → 播完本集 → 关闭(设计文档 §3.5)。 */
private fun nextSleepTimerOption(current: SleepTimerOption?): SleepTimerOption? = when (current) {
    null -> SleepTimerOption.Minutes(15)
    is SleepTimerOption.Minutes -> when (current.value) {
        15 -> SleepTimerOption.Minutes(30)
        30 -> SleepTimerOption.Minutes(45)
        45 -> SleepTimerOption.Minutes(60)
        else -> SleepTimerOption.EndOfEpisode
    }
    SleepTimerOption.EndOfEpisode -> null
}

private fun sleepTimerLabel(option: SleepTimerOption?): String = when (option) {
    null -> "定时"
    is SleepTimerOption.Minutes -> "${option.value}分"
    SleepTimerOption.EndOfEpisode -> "播完本集"
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

/**
 * 封面边长占内容区宽度的比例,两个状态各一个(Task 1b,用户 2026-07-29 决定):
 *
 * - [COVER_FRACTION_PLAYING]:播放中收成小封面。真机实测(1080×2220 / 440dpi)约 330px,
 *   歌词区因此拿到约 650px ≈ 5 行,当前行 + 上下各一行完整可见。
 * - [COVER_FRACTION_PAUSED]:暂停时恢复,和 Task 1 之前的 `COVER_MAX_WIDTH_FRACTION` 同值,
 *   即"接近满宽"的原始观感——大封面是"感觉像播客"的产品身份(v1 设计文档 §6)。
 */
private const val COVER_FRACTION_PLAYING = 0.34f
private const val COVER_FRACTION_PAUSED = 0.78f

/**
 * 封面最多吃掉"封面+标题+歌词"这块区域的多少高度。矮屏(或字体放大)时它先于宽度比例生效,
 * 保证标题和歌词永远还有 38% 的区域可用——封面绝不会把歌词挤没。
 */
private const val COVER_MAX_REGION_SHARE = 0.62f

/** 收起/展开的过渡时长:够长到看得出是动画,短到不拖沓。 */
private const val COVER_TRANSITION_MS = 320
