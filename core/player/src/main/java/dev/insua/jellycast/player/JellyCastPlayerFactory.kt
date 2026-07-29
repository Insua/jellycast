package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
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
 * 「纯音频转码流」专用的缓冲策略(设计文档 §4 第三项:播放卡顿)。
 *
 * ## ⚠️ 卡顿不是码率问题
 *
 * 用户建议"模仿 web mobile 接受最低码率但不显示视频"。事实是 JellyCast 的 L1 **已经只接收音频**
 * (实测 132 kbps),服务端一个视频字节都不传,比 web mobile 最低档**视频**流还省一个量级。
 * **所以方向是调缓冲,不是降码率。**
 *
 * ## 为什么默认值不合适
 *
 * 在这个函数存在之前,[createAudioOnlyPlayer] 从没配过 `LoadControl`,用的是 media3 1.10.1 的默认值,
 * 而那套默认值是给**点播视频**调的:
 *
 * | 参数 | media3 默认 | 对一条 132 kbps 音频流意味着什么 |
 * |---|---|---|
 * | `bufferForPlaybackMs` | **1 000** | 只缓冲 1 秒就开始放,网络抖一下立刻断 |
 * | `bufferForPlaybackAfterRebufferMs` | **2 000** | 卡住后只等 2 秒就复播,于是继续卡:典型的卡顿循环 |
 * | `maxBufferMs` | 50 000 | 上限 50 秒 ≈ 800 KB,对音频毫无必要地保守 |
 *
 * 而本项目还有一个别的播放器没有的负担:**每次 seek 都要等服务端重起转码**(转码流
 * `Accept-Ranges: none`,seek 实现成重新 resolve,见 [AudioPlaybackEngine]),那几秒空窗后
 * 缓冲从零开始 —— 起播阈值定得越低,越容易刚出声就再断一次。
 *
 * ## 选定值与理由(单测钉在 `AudioStreamLoadControlTest`)
 *
 * - [BUFFER_FOR_PLAYBACK_MS] = 5 000:5 秒 ≈ 82 KB。上行只有 500 kbps 时也只多等约 1 秒才出声,
 *   而起播本来就要等服务端起转码好几秒,这 1 秒感知不到;换来能扛住 5 秒网络空窗。
 * - [BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] = 20 000:**真正止住卡顿循环的那个参数**。
 *   卡过一次说明链路正在恶化,只等 2 秒复播必然马上再卡。一次 20 秒的等待,好过连续十次 2 秒的断续。
 * - [MIN_BUFFER_MS] = 120 000 / [MAX_BUFFER_MS] = 240 000:4 分钟 ≈ 4 MB,对音频便宜到可以忽略
 *   (同样的时长对视频是几百 MB,这正是默认值只有 50 秒的原因)。上限也落在 Jellyfin 转码节流窗口
 *   (默认 180s)附近 —— 再多缓冲服务端也不会更早产出。
 * - `prioritizeTimeOverSizeThresholds = true`:否则字节预算(音频默认 12.5 MB)能越过上面这些时间
 *   阈值提前放行,`bufferForPlaybackAfterRebufferMs` 就不再是硬约束。
 *
 * `targetBufferBytes` 刻意**不动**:音频默认已是 12.5 MB > 4 分钟 × 16.5 KB/s ≈ 4 MB,
 * 时间阈值才是起作用的那个约束。
 *
 * ## 局限(诚实记录)
 *
 * 卡顿本身在模拟器 + 局域网上**复现不出来**(实测缓冲深度轻松到 131 秒,60 秒内一次都没离开
 * `STATE_READY`)。这里改的是按实测码率与 media3 默认值推出来的**机理**,不是一次现场复现的修复;
 * 真机公网下的效果列为用户验收项。
 */
fun audioStreamLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

/** 见 [audioStreamLoadControl]:低于这个水位就继续拉流。 */
private const val MIN_BUFFER_MS = 120_000

/** 见 [audioStreamLoadControl]:缓冲上限,≈ 4 MB。 */
private const val MAX_BUFFER_MS = 240_000

/** 见 [audioStreamLoadControl]:首次起播的缓冲门槛(media3 默认只有 1 000)。 */
private const val BUFFER_FOR_PLAYBACK_MS = 5_000

/** 见 [audioStreamLoadControl]:卡顿之后复播的门槛(media3 默认只有 2 000)——止住卡顿循环的关键。 */
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 20_000

/**
 * 构建一个永不渲染视频的播放器:
 * 1. 用 [audioOnlyTrackSelectionParameters] 全局禁用视频轨。
 * 2. 永不绑定 Surface / SurfaceView / TextureView,不创建 PlayerView(本模块也没有引入
 *    media3-ui 依赖)。播放页只显示封面,这是全项目第一条铁律。
 * 3. 设为 MUSIC 用途并交出音频焦点控制权,让系统按音乐处理(耳机拔出暂停、音频焦点抢占)。
 * 4. 用 [audioStreamLoadControl] 替掉 media3 那套给点播视频调的默认缓冲参数 —— 见该函数 KDoc。
 *
 * 本函数需要真实 Android Context 才能实例化 ExoPlayer/DefaultTrackSelector,未做 JVM 单测——
 * 取舍说明见任务报告。已单测覆盖的是它内部用到的纯逻辑 [audioOnlyTrackSelectionParameters]。
 */
fun createAudioOnlyPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context, audioOnlyTrackSelectionParameters())
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(audioStreamLoadControl())
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
