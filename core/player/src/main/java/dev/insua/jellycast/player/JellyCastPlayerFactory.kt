package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory

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
 * 音频缓存(L1,`Content-Type: audio/aac`,裸 ADTS,无容器)专用的媒体源工厂。
 *
 * ## Critical 复审:缓存文件其实不能 seek
 *
 * media3 1.10.1 的 [DefaultExtractorsFactory] 默认 `constantBitrateSeekingEnabled = false`,
 * 于是 `AdtsExtractor` 对裸 ADTS 流只会给出 `SeekMap.Unseekable`——`ExoPlayer.isCurrentMediaItemSeekable`
 * 是 `false`。[createAudioOnlyPlayer] 在这个函数存在之前从没覆盖过 media-source 工厂,直接用的
 * media3 默认值。后果是:
 *
 * - 拖已缓存那一集的进度条 → [PlayerControl.seekTo] 落到 `player.seekTo()`,在不可 seek 的媒体上
 *   要么整个被忽略要么把播放拉回 0——设计文档 §5.2「已缓存的集可以真正 seek」这条卖点根本没兑现。
 * - 从中间续播一集已缓存的条目([AudioPlaybackEngineImpl.resolveAndPrepare] 本地文件分支里那次
 *   `playerControl.seekTo(startPositionMs)`)同样落空,实际从 0 开始放。
 * - 因为本地源的 [AudioPlaybackEngine.absolutePositionMs] 直接读 `player.currentPosition` 当绝对
 *   位置(见该属性 KDoc),播放器実际停在 0 而进度上报以为在 `startPositionMs`,10 秒心跳会把这个
 *   错误的、比实际进度更早的时间点报给 Jellyfin——这正是"报一个更早的时间点比不上报更糟"这个
 *   本仓库已经在别处修过的坑,在缓存路径上原样复现。
 *
 * 实测(见任务报告):同一个 ffmpeg 生成的裸 ADTS 文件,`ExoPlayer.Builder(context)` 默认配置下
 * `seekable=false`;换成这里的 [DefaultExtractorsFactory.setConstantBitrateSeekingEnabled] `true`
 * 之后 `seekable=true`。ADTS AAC 是恒定码率编码,`ConstantBitrateSeekMap` 用首帧码率估算跳转位置
 * 这个前提在这里成立。
 *
 * ## 为什么不影响远端流
 *
 * 远端转码流(同样是裸 ADTS)从不调 `player.seekTo()`——[AudioPlaybackEngineImpl] 对远端源的 seek
 * 走的是"重新 resolve 一条新 URL 再 prepare"这条完全不同的路径(见该类类注释),这个开关只决定
 * `isCurrentMediaItemSeekable` 上报什么、`seekTo()` 是否可靠,不改变 buffering/下载行为,因此对
 * 远端流的既有 200+ 条测试没有任何影响。
 */
fun audioOnlyMediaSourceFactory(context: Context): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        context,
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    )

/**
 * 构建一个永不渲染视频的播放器:
 * 1. 用 [audioOnlyTrackSelectionParameters] 全局禁用视频轨。
 * 2. 永不绑定 Surface / SurfaceView / TextureView,不创建 PlayerView(本模块也没有引入
 *    media3-ui 依赖)。播放页只显示封面,这是全项目第一条铁律。
 * 3. 设为 MUSIC 用途并交出音频焦点控制权,让系统按音乐处理(耳机拔出暂停、音频焦点抢占)。
 * 4. 用 [audioStreamLoadControl] 替掉 media3 那套给点播视频调的默认缓冲参数 —— 见该函数 KDoc。
 * 5. 用 [audioOnlyMediaSourceFactory] 开启恒定码率 seek——见该函数 KDoc(Critical 复审):
 *    没有这一步,已缓存文件的 seek/续播会静默失败,还会污染进度上报。
 *
 * 本函数需要真实 Android Context 才能实例化 ExoPlayer/DefaultTrackSelector,未做 JVM 单测——
 * 取舍说明见任务报告。已单测覆盖的是它内部用到的纯逻辑 [audioOnlyTrackSelectionParameters]。
 * [audioOnlyMediaSourceFactory] 本身的效果由 `:core:player` androidTest 里拿真实裸 ADTS 文件
 * 钉死(见 `LocalAdtsSeekableDeviceTest`)。
 */
fun createAudioOnlyPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context, audioOnlyTrackSelectionParameters())
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(audioStreamLoadControl())
        .setMediaSourceFactory(audioOnlyMediaSourceFactory(context))
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
