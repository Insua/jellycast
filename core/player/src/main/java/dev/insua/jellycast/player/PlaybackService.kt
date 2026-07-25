package dev.insua.jellycast.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import android.os.Handler
import android.os.Looper
import dev.insua.jellycast.network.session.JellyfinSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 后台播放宿主:承载 [MediaSession],让系统提供锁屏媒体卡片、通知栏控制、蓝牙耳机按键路由,
 * 并在按 Home 键退到后台后继续播放。
 *
 * 铁律提醒:这里创建的 player 来自 [createAudioOnlyPlayer] —— 视频轨在 TrackSelector 层已经
 * 被全局禁用;本类也绝不调用任何 `setVideoSurface*` 方法,不创建 PlayerView,播放页只显示封面。
 *
 * ⚠️ Task 9/10 遗留的 Important 缺陷(已闭合):`MediaSession` **不** 直接建在裸 ExoPlayer 上。
 * Media3 默认回调会把 seek 命令直接转发给 `player.seekTo()`,而 Spike 实测转码流
 * `Accept-Ranges: none`,`seekTo()` 在这条流上不可靠(见 [SeekInterceptingPlayer] 类注释)。
 * 这里用 [SeekInterceptingPlayer] 包一层 ExoPlayer 再交给 `MediaSession.Builder`,所有 seek
 * 家族调用都被结构性地拦截,委派给 [bindEngine] 注入的 [AudioPlaybackEngine]。
 *
 * ⚠️ 修正 §1(a)/(b)(已闭合):[exoPlayer] / [engine] / [autoPlayNextController] /
 * [jellyfinSession] 全部经 Hilt 的 `PlayerModule`(见 `di/PlayerModule.kt`)装配注入,不再是
 * "写好了但没有生产调用方"的死代码——[onCreate] 里真的调用了 [bindEngine],`engine` 就是
 * [Player.STATE_ENDED] 那一刻驱动自动连播的同一个实例,和 `exoPlayer` 也是同一个单例
 * (见 `PlayerModule.provideExoPlayer` 的 KDoc)。
 *
 * ⚠️ Finding 1(已闭合):[Player.STATE_ENDED] 的自动连播曾经直接调
 * `ExoPlayerControl(exoPlayer).setMediaItemAndPrepare(next.streamUrl)`,绕开了
 * [AudioPlaybackEngine.play]——`engine` 内部的 `currentItemId`/`currentUserId` 从没更新过,
 * 自动连播之后锁屏拖进度条会悄悄跳回上一集。现在经 [PlaybackEndedAdvancer] 统一走
 * `engine.play(...)`,和 `MediaControllerPlayerConnection.skipToNext()` / `AppSessionViewModel.play()`
 * 是同一条路,详见该类 KDoc。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var playbackEngine: AudioPlaybackEngine

    @Inject
    lateinit var autoPlayNextController: AutoPlayNextController

    @Inject
    lateinit var playQueue: PlayQueue

    @Inject
    lateinit var jellyfinSession: JellyfinSession

    @Inject
    lateinit var sessionByteCounter: SessionByteCounter

    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
    )

    @Volatile
    private var engine: AudioPlaybackEngine? = null

    override fun onCreate() {
        super.onCreate()
        val router = SeekRouter { positionMs ->
            engine?.let { e -> serviceScope.launch { e.seekTo(positionMs) } }
        }
        // 复审 Critical 1:交给 MediaSession 的这个 Player 必须报**条目内绝对位置**和**元数据总时长**。
        // 锁屏/通知栏/蓝牙的进度条与快进快退全部读它;底层 ExoPlayer 报的是转码流内的相对位置
        // (每次 seek/续播归零)和常为 C.TIME_UNSET 的时长,两个都不能直接用。
        // 时长取自 PlayQueue 当前项的 runTimeMs —— PlayQueue 只认识 :core:model 的 MediaItem,
        // 这里没有引入任何 Jellyfin DTO 依赖(设计文档 §5 的模块边界)。
        val sessionPlayer = SeekInterceptingPlayer(
            exoPlayer,
            router,
            playbackEngine.asAbsoluteTimeline { playQueue.current.value?.runTimeMs },
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()

        // 修正 §1(a):真正的生产调用方——没有这一行,锁屏/通知栏/蓝牙拖进度条会被
        // SeekInterceptingPlayer 拦截后静默丢弃(engine 恒为 null)。
        bindEngine(playbackEngine)

        // 修正 §1(b)/Finding 1:自动连播接到 Player.STATE_ENDED。播完一集后,拿当前登录用户 id 问
        // AutoPlayNextController 要不要接着播下一条——它内部已经处理好"关闭自动连播/播完本集
        // 睡眠定时/队列耗尽回退 NextUp"这些分支,这里只负责把结果喂给 engine,不重复决策逻辑。
        // 必须经 PlaybackEndedAdvancer → engine.play(...),不能直接操作 exoPlayer/ExoPlayerControl——
        // 否则 engine 内部状态和实际在放的条目就对不上了(详见 PlaybackEndedAdvancer 类注释)。
        val playbackEndedAdvancer = PlaybackEndedAdvancer(
            engine = playbackEngine,
            autoPlayNextController = autoPlayNextController,
            userIdProvider = { runCatching { jellyfinSession.userId() }.getOrNull() },
        )
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                serviceScope.launch { playbackEndedAdvancer.onPlaybackEnded() }
            }
        })

        // 设置页"开发者信息 → 本次会话已传输字节数"的数据来源(修正 §3)。
        // totalBytesLoaded 是 ExoPlayer 侧的累计值,SessionByteCounter.update 本身也做了
        // "只增不减"的钳制,两者叠加保证这个数字单调递增、不会因为某次回调乱序而跳变变小。
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                sessionByteCounter.update(totalBytesLoaded)
            }
        })
    }

    /** 播放会话真正开始时由上层(驱动实际播放的模块)调用,把 seek 接到真实的重新 resolve 逻辑。 */
    fun bindEngine(engine: AudioPlaybackEngine) {
        this.engine = engine
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        engine = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
