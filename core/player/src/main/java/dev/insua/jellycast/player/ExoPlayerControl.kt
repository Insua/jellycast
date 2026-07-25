package dev.insua.jellycast.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * [PlayerControl] 接到真实 ExoPlayer 的薄适配层。只做 setMediaItem + prepare + 释放,
 * 不调用任何 `setVideoSurface*` 方法,不创建 PlayerView —— 播放页只显示封面,这是全项目第一条铁律。
 */
class ExoPlayerControl(private val player: ExoPlayer) : PlayerControl {

    /**
     * ⚠️ 流内相对位置,不是绝对位置(见 [PlayerControl.currentPositionMs])。
     * `ExoPlayer.currentPosition` 在还没 prepare 时可能是 `C.TIME_UNSET`(负数),钳到 0。
     *
     * 必须在 ExoPlayer 的 application thread(本项目是主线程)上读——所有调用方
     * ([AudioPlaybackEngineImpl.absolutePositionMs] 的消费者:ViewModel 的 viewModelScope、
     * PlaybackService 的 main-looper scope、MediaSession 回调)都在主线程,见各处注释。
     */
    override val currentPositionMs: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    override fun setMediaItemAndPrepare(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    override fun release() {
        player.release()
    }
}
