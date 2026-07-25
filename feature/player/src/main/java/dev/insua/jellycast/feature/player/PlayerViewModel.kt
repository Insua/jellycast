package dev.insua.jellycast.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.SubtitleTimeline
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.subtitle.SubtitleRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全屏播放页需要知道"现在在放什么":领域层 [MediaItem] 元数据(标题/系列名/季集号/封面)
 * + 该 mediaSource 的音轨与可选文本字幕轨列表(字幕不在音频流里,必须单独知道 index 才能拉取)。
 *
 * 真正把 [dev.insua.jellycast.player.PlaybackSourceResolver] 的解析结果接进来、驱动一次真实播放
 * 会话,是 Task 22(导航装配)的职责。这里只声明契约——单测/预览可以用假实现完全绕开真实服务器。
 */
interface NowPlayingInfo {
    val mediaItem: MediaItem
    val mediaSourceId: String
    val audioTracks: List<AudioTrack>
    val subtitleTracks: List<SubtitleTrackRef>
}

/**
 * 全屏播放页能拿到的"播放会话"最小抽象:一个标准 [Player](本 Task 的 UI 只调用它暴露的
 * play/pause/seek 家族,不绕过去碰裸 ExoPlayer;seek 由 core:player 的
 * [dev.insua.jellycast.player.SeekInterceptingPlayer] 结构性拦截并改写成"重新 resolve + prepare",
 * 这里完全不需要关心)+ 当前播放的领域元数据。
 *
 * 真正连到 [dev.insua.jellycast.player.PlaybackService] 的 MediaController、把 [NowPlayingInfo]
 * 接上真实播放队列,是 Task 22 提供的 Hilt 绑定的职责。
 */
interface PlayerConnection {
    val player: StateFlow<Player?>
    val nowPlaying: StateFlow<NowPlayingInfo?>
}

data class PlayerUiState(
    val mediaItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val subtitleTimeline: SubtitleTimeline = SubtitleTimeline(emptyList()),
    val isSubtitleLoading: Boolean = true,
    val subtitleTracks: List<SubtitleTrackRef> = emptyList(),
    val selectedSubtitleTrackIndex: Int? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val sleepTimerMinutesRemaining: Int? = null,
)

/**
 * 播放页 ViewModel。歌词状态本身(当前行索引)由 [dev.insua.jellycast.model.SubtitleTimeline.indexAt]
 * 计算,在 [LyricsView] 里直接调用——这里不重复实现一遍查找逻辑,只负责把字幕轨拉下来解析成
 * [SubtitleTimeline] 交给它。
 *
 * 字幕铁律:[SubtitleRepository.load] 任何失败都已经降级成空 timeline,这里不做二次 try/catch,
 * 也不允许把字幕异常变成播放中断。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlayerConnection,
    private val subtitleRepository: SubtitleRepository,
    private val preferencesStore: PreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var sleepTimerJob: Job? = null

    init {
        observeNowPlaying()
        observePlaybackState()
        observePreferences()
    }

    private fun observeNowPlaying() {
        viewModelScope.launch {
            connection.nowPlaying.collectLatest { info ->
                _uiState.update {
                    it.copy(
                        mediaItem = info?.mediaItem,
                        audioTracks = info?.audioTracks.orEmpty(),
                        subtitleTracks = info?.subtitleTracks.orEmpty(),
                        isSubtitleLoading = info != null,
                    )
                }
                if (info == null) {
                    _uiState.update {
                        it.copy(
                            subtitleTimeline = SubtitleTimeline(emptyList()),
                            isSubtitleLoading = false,
                            selectedSubtitleTrackIndex = null,
                        )
                    }
                } else {
                    val preferredLanguage = preferencesStore.preferredSubtitleLanguage.first()
                    val track = info.subtitleTracks.firstOrNull { it.language == preferredLanguage }
                        ?: info.subtitleTracks.firstOrNull()
                    loadSubtitleTrack(info.mediaItem.id, info.mediaSourceId, track)
                }
            }
        }
    }

    /**
     * 字幕铁律落地处:[track] 为 null(无可用文本字幕轨)时直接给空 timeline,不发请求;
     * 有轨道时调 [SubtitleRepository.load] —— 它内部已经把网络异常/非 2xx/解析异常统统降级成
     * 空 timeline,这里不需要、也不允许再包一层 try/catch 把它变成向上抛错。
     */
    private suspend fun loadSubtitleTrack(itemId: String, mediaSourceId: String, track: SubtitleTrackRef?) {
        val timeline = if (track == null) {
            SubtitleTimeline(emptyList())
        } else {
            subtitleRepository.load(itemId, mediaSourceId, track.index)
        }
        _uiState.update {
            it.copy(subtitleTimeline = timeline, isSubtitleLoading = false, selectedSubtitleTrackIndex = track?.index)
        }
    }

    /**
     * media3 [Player] 没有原生的位置 Flow,这里用一个轻量轮询把 isPlaying/position/duration
     * 同步进 [uiState]。用 collectLatest 绑定到具体的 player 实例——一旦 [PlayerConnection.player]
     * 发出新值(重连/置空),旧的轮询协程自动取消,不会有两个轮询并存。
     */
    private fun observePlaybackState() {
        viewModelScope.launch {
            connection.player.collectLatest { player ->
                if (player == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    _uiState.update {
                        it.copy(
                            isPlaying = player.isPlaying,
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = player.duration.coerceAtLeast(0L),
                            playbackSpeed = player.playbackParameters.speed,
                        )
                    }
                    delay(POSITION_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesStore.rewindSeconds.collectLatest { seconds -> _uiState.update { it.copy(rewindSeconds = seconds) } }
        }
        viewModelScope.launch {
            preferencesStore.forwardSeconds.collectLatest { seconds -> _uiState.update { it.copy(forwardSeconds = seconds) } }
        }
    }

    fun onPlayPause() {
        val player = connection.player.value ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * 点击歌词行 / 拖动进度条统一走这一个方法——都只调标准 [Player.seekTo],绝不自己实现 seek
     * 逻辑。真正"转码流不支持 Range,必须重新 resolve+prepare"的处理在 core:player 的
     * SeekInterceptingPlayer 里,这层完全不用关心。
     */
    fun onSeek(positionMs: Long) {
        connection.player.value?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun onSkipBack() {
        val player = connection.player.value ?: return
        val target = player.currentPosition - _uiState.value.rewindSeconds * 1000L
        onSeek(target.coerceAtLeast(0L))
    }

    fun onSkipForward() {
        val player = connection.player.value ?: return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = player.currentPosition + _uiState.value.forwardSeconds * 1000L
        onSeek(target.coerceAtMost(duration))
    }

    fun onCycleSpeed() {
        val player = connection.player.value ?: return
        val current = _uiState.value.playbackSpeed
        val next = SPEED_STEPS.firstOrNull { it > current + SPEED_EPSILON } ?: SPEED_STEPS.first()
        player.setPlaybackSpeed(next)
        viewModelScope.launch { preferencesStore.setPlaybackSpeed(next) }
    }

    /** 字幕语言:在已知的文本字幕轨里循环切换到下一条,重新拉取解析,并记住偏好供下次默认选中。 */
    fun onCycleSubtitleTrack() {
        val state = _uiState.value
        val tracks = state.subtitleTracks
        if (tracks.isEmpty()) return
        val mediaItem = state.mediaItem ?: return
        val mediaSourceId = connection.nowPlaying.value?.mediaSourceId ?: return

        val currentPosition = tracks.indexOfFirst { it.index == state.selectedSubtitleTrackIndex }
        val next = tracks[(currentPosition + 1).mod(tracks.size)]

        viewModelScope.launch {
            preferencesStore.setPreferredSubtitleLanguage(next.language)
            _uiState.update { it.copy(isSubtitleLoading = true) }
            loadSubtitleTrack(mediaItem.id, mediaSourceId, next)
        }
    }

    fun onSetSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerMinutesRemaining = minutes) }
        if (minutes == null) return
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            connection.player.value?.pause()
            _uiState.update { it.copy(sleepTimerMinutesRemaining = null) }
        }
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 500L
        const val SPEED_EPSILON = 0.001f
        val SPEED_STEPS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
