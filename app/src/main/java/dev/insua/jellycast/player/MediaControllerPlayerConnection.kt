package dev.insua.jellycast.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    @ApplicationContext private val context: Context,
    private val playQueue: PlayQueue,
    private val audioPlaybackEngine: AudioPlaybackEngine,
    private val autoPlayNextController: AutoPlayNextController,
    private val jellyfinSession: JellyfinSession,
) : PlayerConnection {

    /**
     * `Main.immediate` 而不是 `Main`:controller 的全部状态变更([ControllerConnectionHolder] 的
     * 线程契约)都在这个 scope 里发生,用 immediate 时"已经在主线程上"的调用会同步执行,不会被
     * 排到下一个消息循环——省掉一次"命令下发时 controller 还没被发布出去"的时间窗。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /**
     * ⚠️ 复审 Finding 1(已闭合):controller **不是**建一次就完事的。会话会被释放(暂停 → 从最近
     * 任务划掉 App → `MediaSessionService.onTaskRemoved` 在没在播放时 `stopSelf` →
     * `PlaybackService.onDestroy` 里 `mediaSession.release()`),此后那个 controller 永久断开:
     * 音频还能播(引擎直接操作单例 ExoPlayer),但所有经 [player] 下发的传输命令全部静默 no-op,
     * `isPlaying` 恒为 false —— UI 显示"已暂停",耳机里在响。
     *
     * 修正:状态机交给 [ControllerConnectionHolder](纯逻辑,已离线单测),这里只负责把 media3 的
     * 回调接上去,并在三个时机调 [ensureConnected]。
     */
    private val holder = ControllerConnectionHolder<MediaController>(
        isConnected = { it.isConnected },
        release = { it.release() },
        publish = { _player.value = it },
        connect = ::buildController,
    )

    /**
     * 建连的三个生产触发点(都在"绑定必定合法"的时刻,所以 [ControllerConnectionHolder.onDisconnected]
     * 刻意不立刻重连,不去把用户刚划掉的 Service 又拉起来):
     *
     * 1. 单例初始化 —— App 冷启动。
     * 2. `MainActivity.onStart` —— App 回到前台(Media3 推荐的 controller 生命周期时机),这是"划掉
     *    App 再重开"这条路径上最早能恢复的点,用户还没点任何按钮传输控制就已经活了。
     * 3. [nowPlaying] 变成非 null —— 真的开始播了。`AppSessionViewModel.play()` 会先
     *    `startForegroundService` 再调 `engine.play`,所以此刻 Service 一定在,绑定必定成功;
     *    这一条兜住"前台绑定因为任何原因失败了"的情况。
     */
    init {
        ensureConnected()
        scope.launch {
            nowPlaying.collect { info -> if (info != null) ensureConnected() }
        }
    }

    /** 见 [holder] 与 [init] 的说明。可从任意线程调用,内部统一切到主线程上执行。 */
    fun ensureConnected() {
        scope.launch { holder.ensureConnected() }
    }

    /**
     * 线程:整个方法在主线程上跑(由 [scope] 保证),于是 `buildAsync` 记下的 application looper
     * 就是主线程 —— `MediaController.Listener.onDisconnected` 与 future 的完成回调(用主线程
     * executor)都回到同一个线程,[ControllerConnectionHolder] 的"单线程"契约得到满足。
     *
     * `future.get()` 必须包 `runCatching`:绑定失败时它抛 `ExecutionException`,而这里是在
     * executor 回调里,抛出去没人接得住。失败按"暂时没有 controller"处理,等下一次 [ensureConnected]。
     */
    private fun buildController(onResult: (MediaController?) -> Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    holder.onDisconnected(controller)
                }
            })
            .buildAsync()
        future.addListener(
            { onResult(runCatching { future.get() }.getOrNull()) },
            ContextCompat.getMainExecutor(context),
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
