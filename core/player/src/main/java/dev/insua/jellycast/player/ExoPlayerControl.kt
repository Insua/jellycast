package dev.insua.jellycast.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * [PlayerControl] 接到真实 ExoPlayer 的薄适配层。只做 setMediaItem + prepare + 释放,
 * 不调用任何 `setVideoSurface*` 方法,不创建 PlayerView —— 播放页只显示封面,这是全项目第一条铁律。
 */
class ExoPlayerControl(private val player: ExoPlayer) : PlayerControl {
    override fun setMediaItemAndPrepare(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    override fun release() {
        player.release()
    }
}
