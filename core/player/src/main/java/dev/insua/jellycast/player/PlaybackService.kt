package dev.insua.jellycast.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * [bindEngine] 是有意留出的接缝:真正解析 Jellyfin 播放源需要 `JellyfinApi`/`StreamProbe`/
 * 当前登录态,这些还没有 Hilt 装配(留给驱动实际播放的上层模块,如 :feature:player,在播放会话
 * 真正开始时调用)。在 [bindEngine] 之前收到的 seek 请求会被安全地丢弃(不落到底层
 * `player.seekTo`,也不崩溃),一旦 engine 就绪,后续所有 seek 立即改走"重新 resolve +
 * prepare"这条路径,不需要重建 Session。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
    )

    @Volatile
    private var engine: AudioPlaybackEngine? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = createAudioOnlyPlayer(this)
        val router = SeekRouter { positionMs ->
            engine?.let { e -> serviceScope.launch { e.seekTo(positionMs) } }
        }
        val sessionPlayer = SeekInterceptingPlayer(exoPlayer, router)
        session = MediaSession.Builder(this, sessionPlayer).build()
    }

    /** 播放会话真正开始时由上层(驱动实际播放的模块)调用,把 seek 接到真实的重新 resolve 逻辑。 */
    fun bindEngine(engine: AudioPlaybackEngine) {
        this.engine = engine
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        engine = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
