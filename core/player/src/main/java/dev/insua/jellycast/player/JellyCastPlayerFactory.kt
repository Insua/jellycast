package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * 音频专用的 TrackSelector 参数:全局禁用视频轨。
 *
 * 刻意不依赖 Context —— `TrackSelectionParameters.Builder()` 有无参构造函数,构建的是纯数据,
 * 不触碰任何 Android 运行时 API,因此可以直接在 JVM 单测里断言 `disabledTrackTypes` 包含
 * `C.TRACK_TYPE_VIDEO`,不需要 Robolectric 或真机。
 *
 * 铁律:即使降级到 L3 拿到的是完整视频+音频混流,这个参数也保证视频轨永远不会被选中、不会被解码。
 */
fun audioOnlyTrackSelectionParameters(): TrackSelectionParameters =
    DefaultTrackSelector.Parameters.Builder()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .build()

/**
 * 构建一个永不渲染视频的播放器:
 * 1. 用 [audioOnlyTrackSelectionParameters] 全局禁用视频轨。
 * 2. 永不绑定 Surface / SurfaceView / TextureView,不创建 PlayerView(本模块也没有引入
 *    media3-ui 依赖)。播放页只显示封面,这是全项目第一条铁律。
 * 3. 设为 MUSIC 用途并交出音频焦点控制权,让系统按音乐处理(耳机拔出暂停、音频焦点抢占)。
 *
 * 本函数需要真实 Android Context 才能实例化 ExoPlayer/DefaultTrackSelector,未做 JVM 单测——
 * 取舍说明见任务报告。已单测覆盖的是它内部用到的纯逻辑 [audioOnlyTrackSelectionParameters]。
 */
fun createAudioOnlyPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context, audioOnlyTrackSelectionParameters())
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)   // 拔耳机自动暂停
        .build()
}
