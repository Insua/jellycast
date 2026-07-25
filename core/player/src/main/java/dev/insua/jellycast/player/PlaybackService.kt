package dev.insua.jellycast.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint

/**
 * 后台播放宿主:承载 [MediaSession],让系统提供锁屏媒体卡片、通知栏控制、蓝牙耳机按键路由,
 * 并在按 Home 键退到后台后继续播放。
 *
 * 铁律提醒:这里创建的 player 来自 [createAudioOnlyPlayer] —— 视频轨在 TrackSelector 层已经
 * 被全局禁用;本类也绝不调用任何 `setVideoSurface*` 方法,不创建 PlayerView,播放页只显示封面。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = createAudioOnlyPlayer(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
