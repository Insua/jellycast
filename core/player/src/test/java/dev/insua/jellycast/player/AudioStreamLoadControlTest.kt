package dev.insua.jellycast.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * # 缺陷「播放卡顿」的缓冲参数(设计文档 §4 第三项)
 *
 * ## ⚠️ 先纠正一个误解:这不是码率问题
 *
 * 用户建议"模仿 web mobile 接受最低码率但不显示视频"。实际上 JellyCast 的 L1 已经**只接收音频**
 * (实测 132 kbps),服务端一个视频字节都不传,比 web mobile 最低档**视频**流还省一个量级。
 * **所以修法不是降码率,是调缓冲策略。**
 *
 * ## 证据
 *
 * `createAudioOnlyPlayer` 从来没有配过 `LoadControl`,用的是 media3 1.10.1 的默认值,而那套默认值
 * 是给**点播视频**调的:
 *
 * | 参数 | media3 默认 | 对一条 132 kbps 的音频流意味着什么 |
 * |---|---|---|
 * | `bufferForPlaybackMs` | **1 000** | 只缓冲 1 秒就开始放 —— 网络抖一下立刻断 |
 * | `bufferForPlaybackAfterRebufferMs` | **2 000** | 卡住之后只等 2 秒就重新放,于是继续卡:典型的卡顿循环 |
 * | `maxBufferMs` | 50 000 | 上限 50 秒 ≈ 800 KB。对音频流毫无必要地保守 |
 *
 * ## 选定值与理由
 *
 * | 参数 | 取值 | 理由 |
 * |---|---|---|
 * | `bufferForPlaybackMs` | 5 000 | 5 秒 ≈ 82 KB。就算上行只有 500 kbps 也只多等约 1 秒才出声,而每次起播本来就要等服务端起转码好几秒,这 1 秒感知不到;换来的是能扛住 5 秒的网络空窗 |
 * | `bufferForPlaybackAfterRebufferMs` | 20 000 | **真正止住卡顿循环的那个参数**。卡过一次说明链路正在恶化,此时只等 2 秒复播必然马上再卡。等一次 20 秒,好过连续十次 2 秒的断断续续 |
 * | `minBufferMs` | 120 000 | 低于 2 分钟就继续拉,给公网/移动网络留足余量 |
 * | `maxBufferMs` | 240 000 | 4 分钟 ≈ 4 MB。对音频便宜到可以忽略(对视频则会是几百 MB,这正是默认值只有 50 秒的原因);也落在 Jellyfin 转码节流窗口(默认 180 s)附近,再多缓冲服务端也不会更早产出 |
 * | `prioritizeTimeOverSizeThresholds` | true | 否则字节预算(音频默认 12.5 MB)可以越过上面这些时间阈值提前放行,`bufferForPlaybackAfterRebufferMs` 就不再是硬约束 |
 *
 * `targetBufferBytes` 刻意**不动**:音频默认就是 12.5 MB > 4 分钟 × 16.5 KB/s ≈ 4 MB,
 * 时间阈值才是真正起作用的那个约束。
 *
 * ## 局限(诚实记录)
 *
 * 卡顿本身在模拟器 + 局域网上**复现不出来**:实测缓冲深度轻松到 131 秒、60 秒内一次都没离开
 * `STATE_READY`。所以这里改的是**按实测码率与 media3 默认值推出来的机理**,不是一次现场复现的修复。
 * 真机公网下的效果列为用户验收项。
 */
class AudioStreamLoadControlTest {

    private val loadControl: LoadControl = audioStreamLoadControl()

    private val playerId = PlayerId.UNSET

    /**
     * `DefaultLoadControl` 会先经 `isLocalPlayback(parameters)` 判断走"流式"还是"本地文件"那套阈值,
     * 而那个判断要从 timeline 里按 uid 取出 period 和 window —— `Timeline.EMPTY` 会直接
     * `IndexOutOfBoundsException`。这里给一条最小的单周期时间轴,`MediaItem.EMPTY` 没有
     * `localConfiguration`,于是判定为**流式**,和生产上那条 https 转码流一致。
     */
    private val timeline = SinglePeriodTimeline(
        /* durationUs = */ C.TIME_UNSET,
        /* isSeekable = */ false,
        /* isDynamic = */ false,
        /* useLiveConfiguration = */ false,
        /* manifest = */ null,
        MediaItem.EMPTY,
    )

    private val mediaPeriodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

    private fun parameters(bufferedMs: Long, rebuffering: Boolean): LoadControl.Parameters =
        LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            /* playbackPositionUs = */ 0L,
            /* bufferedDurationUs = */ bufferedMs * 1_000L,
            /* playbackSpeed = */ 1.0f,
            /* playWhenReady = */ true,
            rebuffering,
            /* targetLiveOffsetUs = */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
        )

    private fun prepared(): LoadControl = loadControl.also {
        it.onPrepared(playerId)
        it.onTracksSelected(parameters(0L, rebuffering = false), TrackGroupArray.EMPTY, emptyArray<ExoTrackSelection>())
    }

    @Test fun `起播前必须先攒够缓冲,而不是media3默认的一秒就放`() {
        val control = prepared()

        assertFalse(
            control.shouldStartPlayback(parameters(bufferedMs = 2_000L, rebuffering = false)),
            "只缓冲 2 秒就开始放,网络抖一下立刻断;media3 默认阈值是 " +
                "${DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS}ms",
        )
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 6_000L, rebuffering = false)))
    }

    @Test fun `卡住之后必须攒够明显更多的缓冲再复播 —— 这是止住卡顿循环的关键参数`() {
        val control = prepared()

        assertFalse(
            control.shouldStartPlayback(parameters(bufferedMs = 10_000L, rebuffering = true)),
            "卡过一次说明链路正在恶化,此时只等 " +
                "${DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS}ms(media3 默认)" +
                "复播必然马上再卡",
        )
        assertTrue(control.shouldStartPlayback(parameters(bufferedMs = 25_000L, rebuffering = true)))
    }

    @Test fun `缓冲上限必须远高于media3给点播视频调的五十秒`() {
        val control = prepared()

        assertTrue(
            control.shouldContinueLoading(parameters(bufferedMs = 60_000L, rebuffering = false)),
            "已经缓冲 60 秒就停止拉流,是 media3 默认值(maxBufferMs=" +
                "${DefaultLoadControl.DEFAULT_MAX_BUFFER_MS}ms)的行为;对一条 132 kbps 的音频流," +
                "60 秒只有 ~1 MB,没有任何理由停在这里",
        )
        assertFalse(
            control.shouldContinueLoading(parameters(bufferedMs = 250_000L, rebuffering = false)),
            "也不能无上限地拉:超过设定的上限就该停,否则一次 seek 丢掉的缓冲会白花流量",
        )
    }
}
