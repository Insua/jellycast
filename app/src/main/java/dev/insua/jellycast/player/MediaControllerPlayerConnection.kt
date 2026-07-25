package dev.insua.jellycast.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.insua.jellycast.feature.player.NowPlayingInfo
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.session.JellyfinSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private data class DefaultNowPlayingInfo(
    override val mediaItem: MediaItem,
    override val mediaSourceId: String,
    override val audioTracks: List<AudioTrack>,
    override val subtitleTracks: List<SubtitleTrackRef>,
) : NowPlayingInfo

/**
 * [PlayerConnection] 的真实实现(修正 §8e):Task 20 只声明了接口,`:feature:player` 完全没有
 * 被接到真实播放会话。这是全项目最后一个"接线缝"——把它接上。
 *
 * ## [player] 为什么走 [MediaController] 而不是直接暴露 [PlaybackService] 内部的 `ExoPlayer`
 *
 * 即使本项目 App 和 [PlaybackService] 同进程,也遵循 Media3 的标准模式:UI 层只通过
 * [MediaController] 跟播放会话对话,不直接持有 Service 内部的播放器实例。好处是生命周期正确
 * (Service 还没跑起来时 [player] 就是 null,UI 能正确表现"还没有可控制的播放器")、seek 命令走
 * 的是同一条 `MediaSession` 回调路径(会被 `SeekInterceptingPlayer` 拦截,和锁屏/蓝牙耳机按键
 * 触发的 seek 是完全一致的一条路,不因为"是不是本进程内直接调用"而有第二套行为)。
 *
 * ## [nowPlaying] 为什么不通过 MediaController(而是直接观察 [AudioPlaybackEngine] / [PlayQueue])
 *
 * [NowPlayingInfo] 需要的字幕轨/音轨列表、`mediaSourceId` 是 Jellyfin 领域数据,不是 Media3
 * 标准 `MediaMetadata` 字段能装的东西。既然本项目同进程运行,[AudioPlaybackEngine] 和 [PlayQueue]
 * 都是 Hilt `@Singleton`——和 [PlaybackService] 内部持有的是同一个实例——直接订阅它们的 `StateFlow`
 * 比硬塞进 MediaMetadata 的 extras 再解出来更直接、类型也更安全。只有当 [PlayQueue.current] 与
 * [AudioPlaybackEngine] 当前 `Ready` 的 `itemId` 一致时才发出非 null 值,避免队列推进和引擎切换
 * 之间那个短暂窗口把"上一集的元数据 + 下一集的技术信息"拼成一条自相矛盾的 [NowPlayingInfo]。
 */
@Singleton
class MediaControllerPlayerConnection @Inject constructor(
    @ApplicationContext context: Context,
    private val playQueue: PlayQueue,
    private val audioPlaybackEngine: AudioPlaybackEngine,
    private val autoPlayNextController: AutoPlayNextController,
    private val jellyfinSession: JellyfinSession,
) : PlayerConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player

    override val nowPlaying: StateFlow<NowPlayingInfo?> =
        combine(audioPlaybackEngine.state, playQueue.current) { engineState, queueItem ->
            val source = (engineState as? PlaybackEngineState.Ready)?.source ?: return@combine null
            if (queueItem == null || queueItem.id != source.itemId) return@combine null
            DefaultNowPlayingInfo(
                mediaItem = queueItem,
                mediaSourceId = source.mediaSourceId,
                audioTracks = source.audioTracks,
                subtitleTracks = source.textSubtitles,
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            { _player.value = controllerFuture.get() },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * 复审 Critical 1:位置的唯一权威是 [AudioPlaybackEngine.absolutePositionMs](条目内绝对位置),
     * 不是 [MediaController.getCurrentPosition](那是转码流内的相对位置,每次 seek/续播归零)。
     *
     * 走引擎而不是 MediaController 还顺带避免了 `MediaController` 对位置的外推(它按上次上报的位置
     * + 经过的真实时间 × 倍速估算),歌词定位对这点抖动是敏感的。
     *
     * 线程:调用方是 ViewModel 的 `viewModelScope`(Dispatchers.Main),而引擎内部读的是
     * `ExoPlayer.currentPosition`——必须在 ExoPlayer 的 application thread(本项目即主线程)上读。
     */
    override fun absolutePositionMs(): Long = audioPlaybackEngine.absolutePositionMs

    override fun setStopAfterCurrentEpisode(armed: Boolean) {
        // 修正 §8g:真正的停止决策在 AutoPlayNextController(core:player),这里只转发。
        if (armed) autoPlayNextController.armStopAfterCurrentEpisode() else autoPlayNextController.disarmStopAfterCurrentEpisode()
    }

    override fun skipToNext() {
        scope.launch {
            val next = playQueue.next() ?: return@launch
            val userId = runCatching { jellyfinSession.userId() }.getOrNull() ?: return@launch
            audioPlaybackEngine.play(next.id, userId, next.resumePositionMs)
        }
    }
}
