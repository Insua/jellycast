package dev.insua.jellycast.navigation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.PlayQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 常驻迷你播放条(修正 §4/§9)需要的最小展示状态。 */
data class MiniPlayerUiState(
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val isPlaying: Boolean,
    val progress: Float,
)

/**
 * 迷你条进度比例。纯函数,可离线单测(项目铁律 6)。
 *
 * 复审 Critical 1:[positionMs] 必须是 `AudioPlaybackEngine.absolutePositionMs` 给的**绝对**位置,
 * [durationMs] 必须是元数据 `MediaItem.runTimeMs` —— 转码流的 `Player.currentPosition` 每次 seek/
 * 续播归零、`Player.duration` 常是 `C.TIME_UNSET`,两个都不能用。
 *
 * 时长未知(null / <= 0)时返回 0f 而不是除零或钉在 100%。
 */
internal fun miniPlayerProgress(positionMs: Long, durationMs: Long?): Float {
    if (durationMs == null || durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * [JellyCastNavHost] 顶层需要、但不属于任何单一 `:feature` 模块的会话/播放启动状态:
 * - 起点判定([startDestination]):有已登录服务器 → `home`,否则 → `servers`(修正 §9)。
 * - 常驻迷你播放条的数据源([miniPlayer])。
 * - 从 Home/Library 点击条目 → 真正驱动一次播放([play])——这是"打开 App 到开始播放'下一集'
 *   不超过 3 次点击"这个设计目标在导航层的落地:启动 [PlaybackService]、把队列交给 [PlayQueue]、
 *   调 [AudioPlaybackEngine.play]。
 */
@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val serverStore: ServerStore,
    private val session: JellyfinSession,
    private val playQueue: PlayQueue,
    private val audioPlaybackEngine: AudioPlaybackEngine,
    private val playerConnection: PlayerConnection,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _miniPlayer = MutableStateFlow<MiniPlayerUiState?>(null)
    val miniPlayer: StateFlow<MiniPlayerUiState?> = _miniPlayer.asStateFlow()

    init {
        viewModelScope.launch {
            val activeId = serverStore.activeServerId.first()
            _startDestination.value = if (activeId != null) Routes.HOME else Routes.SERVERS
            if (activeId != null) refreshBaseUrl()
        }
        observeMiniPlayer()
    }

    /** 登录/添加服务器成功后由导航层调用,让首页/媒体库尽快拿到正确的封面 baseUrl。 */
    fun onServerConnected() {
        refreshBaseUrl()
    }

    private fun refreshBaseUrl() {
        viewModelScope.launch {
            runCatching { session.baseUrl() }.getOrNull()?.let { _baseUrl.value = it }
        }
    }

    /**
     * 和 [dev.insua.jellycast.feature.player.PlayerViewModel.observePlaybackState] 同样的
     * "绑定 nowPlaying 生命周期的轻量轮询"模式:一旦 [PlayerConnection.nowPlaying] 变化(新条目/
     * 置空),[collectLatest] 自动取消上一个轮询协程,不会有多个轮询并存。
     */
    private fun observeMiniPlayer() {
        viewModelScope.launch {
            playerConnection.nowPlaying.collectLatest { info ->
                if (info == null) {
                    _miniPlayer.value = null
                    return@collectLatest
                }
                val posterUrl = baseUrl.value.takeIf { it.isNotBlank() }?.let { info.mediaItem.posterUrl(it) }
                while (currentCoroutineContext().isActive) {
                    val player = playerConnection.player.value
                    _miniPlayer.value = MiniPlayerUiState(
                        title = info.mediaItem.name,
                        subtitle = info.mediaItem.displaySubtitle,
                        posterUrl = posterUrl,
                        isPlaying = player?.isPlaying == true,
                        // 复审 Critical 1:位置走引擎的绝对位置、时长走元数据 runTimeMs。
                        progress = miniPlayerProgress(
                            positionMs = audioPlaybackEngine.absolutePositionMs,
                            durationMs = info.mediaItem.runTimeMs,
                        ),
                    )
                    delay(MINI_PLAYER_POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun onMiniPlayerPlayPause() {
        playerConnection.player.value?.let { player -> if (player.isPlaying) player.pause() else player.play() }
    }

    /** 首页/媒体库点条目播放的唯一入口(见类注释)。[queue] 必须是整季/单集自身,由调用方决定。 */
    fun play(item: MediaItem, queue: List<MediaItem>) {
        viewModelScope.launch {
            playQueue.setQueue(queue, queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            val userId = runCatching { session.userId() }.getOrNull() ?: return@launch
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            audioPlaybackEngine.play(item.id, userId, item.resumePositionMs)
            refreshBaseUrl()
        }
    }

    private companion object {
        const val MINI_PLAYER_POLL_INTERVAL_MS = 500L
    }
}
