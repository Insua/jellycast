package dev.insua.jellycast.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    /**
     * 全项目此前从未给 `MediaItem` 设过 `MediaMetadata`,唯一提到它的地方是一句"为什么没用"的
     * 注释——后果是锁屏、通知栏、Android Auto、OPPO 流体云全部空白。这里是真正落到 Media3
     * `MediaMetadata`(要调 `Uri.parse`)的那一步,未做 JVM 单测:`Uri.parse` 在
     * `testOptions.unitTests.isReturnDefaultValues = true` 下永远回 `null`,纯 JVM 环境里测不出
     * 真实内容,和 [createAudioOnlyPlayer] 是同一类取舍。[PlaybackDisplayMetadata] 那一层(纯
     * Kotlin,不认识 Uri)已经把可测的部分单测覆盖了。
     */
    override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(metadata?.title)
            .setArtist(metadata?.subtitle)
            .setArtworkUri(metadata?.artworkUri?.let(Uri::parse))
            // 通知/锁屏/流体云据此判断这是一条可播放的媒体、按音乐场景展示(专辑封面式大图,
            // 不是视频缩略图)——铁律 1:全程不渲染视频,这里也刻意不设 MEDIA_TYPE_MOVIE。
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(mediaMetadata).build())
        player.prepare()
        player.playWhenReady = true
    }

    override fun release() {
        player.release()
    }
}
