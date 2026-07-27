package dev.insua.jellycast.player

import androidx.media3.common.Player
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 全支线复审 Critical 1 的核心验收:**位置在整条链路上必须是"条目内绝对位置",不是"当前这条流内的
 * 相对位置"**。
 *
 * 为什么这是个陷阱:Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)转码音频流
 * `Accept-Ranges: none`,所以 seek/续播都实现成"带新的 startTimeTicks 重新 resolve 一条流再
 * setMediaItem + prepare"。每换一条流,`ExoPlayer.currentPosition` 就从 0 重新开始——它只回答
 * "在这条流里播了多久",不回答"这一集听到第几分钟"。
 *
 * 从 8:00 续播的那一刻,底层 `currentPosition == 0`。此时:
 * - 快进 30s 若以底层位置为基准,会算出 0:30,**往回跳了 7 分半**;
 * - 歌词(字幕时间戳是绝对的)会高亮第 1 行,而音频在 8:00。
 *
 * 修正:[AudioPlaybackEngine.absolutePositionMs] 是唯一权威 = 本条流的起始位置 + 播放器流内相对
 * 位置。所有 seek 起点、进度上报、歌词定位都必须读它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbsolutePositionTest {

    private fun source(startPositionMs: Long) = PlaybackSource(
        itemId = "ep1",
        mediaSourceId = "ms1",
        streamUrl = "https://nas.example.com/Audio/ep1/universal?startTimeTicks=${startPositionMs * 10_000}",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = "session-1",
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    /** 流内相对位置可变的假 PlayerControl:模拟"每换一条流就从 0 重新计时"。 */
    private class StreamRelativeControl(override var currentPositionMs: Long = 0L) : PlayerControl {
        val preparedUrls = mutableListOf<String>()
        override fun setMediaItemAndPrepare(url: String) {
            preparedUrls += url
            currentPositionMs = 0L      // 新流刚 prepare,流内相对位置归零
        }
        override fun release() {}
    }

    @Test fun `absolutePositionMs 等于本条流的起始位置加上播放器的流内相对位置`() = runTest {
        val control = StreamRelativeControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)

        engine.play("ep1", "u1", startPositionMs = 480_000L)
        control.currentPositionMs = 20_000L

        assertEquals(500_000L, engine.absolutePositionMs)
    }

    @Test fun `Idle 时 absolutePositionMs 为 0`() {
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { _, _, pos -> source(pos) },
            StreamRelativeControl(currentPositionMs = 99_000L),
        )

        assertEquals(0L, engine.absolutePositionMs)
    }

    @Test fun `seek 之后基准换成新的起始位置,不是从 0 重新算`() = runTest {
        val control = StreamRelativeControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)

        engine.play("ep1", "u1", startPositionMs = 480_000L)
        control.currentPositionMs = 20_000L
        engine.seekTo(300_000L)         // 新流从 5:00 开始,流内相对位置归零

        assertEquals(300_000L, engine.absolutePositionMs)
        control.currentPositionMs = 7_000L
        assertEquals(307_000L, engine.absolutePositionMs)
    }

    @Test fun `resolve 失败时绝对位置基准不动,仍然指向真正在放的那条流`() = runTest {
        val control = StreamRelativeControl()
        var failNext = false
        val provider = PlaybackSourceProvider { _, _, pos ->
            if (failNext) error("PlaybackInfo 调用失败") else source(pos)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.play("ep1", "u1", startPositionMs = 480_000L)
        control.currentPositionMs = 20_000L
        failNext = true
        engine.seekTo(300_000L)         // 失败:播放器还在放那条 8:00 起的流

        assertEquals(500_000L, engine.absolutePositionMs)
    }

    @Test fun `Ready 状态带上本条流的起始位置,供进度上报判断绝对位置`() = runTest {
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { _, _, pos -> source(pos) },
            StreamRelativeControl(),
        )

        engine.play("ep1", "u1", startPositionMs = 480_000L)

        assertEquals(480_000L, (engine.state.value as PlaybackEngineState.Ready).startPositionMs)
    }

    /**
     * 核心验收(复审 Critical 1 点名的用例):**从 8:00 续播后快进 30 秒必须落到 8:30,不是 0:30。**
     * 走的是和锁屏/蓝牙耳机完全相同的一条路:`Player.seekForward()` → [SeekInterceptingPlayer] →
     * [EngineSeekRouter] → [AudioPlaybackEngine.seekTo] → 以新位置重新 resolve。
     *
     * 底层 player 的 `currentPosition` 刻意设成 0(续播那一刻的真实值),所以只要还以它为基准就必然
     * 算出 30_000 而不是 510_000——这个测试在修正前是红的。
     */
    @Test fun `从 8 分 00 秒续播后快进 30 秒落到 8 分 30 秒,而不是 0 分 30 秒`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 0L
        every { underlying.seekForwardIncrement } returns 30_000L
        val requestedPositions = mutableListOf<Long>()
        val control = StreamRelativeControl()
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { _, _, pos -> requestedPositions += pos; source(pos) },
            control,
        )
        engine.play("ep1", "u1", startPositionMs = 480_000L)

        val sessionPlayer = SeekInterceptingPlayer(
            underlying,
            EngineSeekRouter(engine, this),
            engine.asAbsoluteTimeline(),
        )
        sessionPlayer.seekForward()
        advanceUntilIdle()

        assertEquals(listOf(480_000L, 510_000L), requestedPositions)
        assertEquals(510_000L, engine.absolutePositionMs)
    }

    /** 快退同理:8:00 续播后快退 15 秒是 7:45,不是被钳到 0。 */
    @Test fun `从 8 分 00 秒续播后快退 15 秒落到 7 分 45 秒,而不是被钳制到 0`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 0L
        every { underlying.seekBackIncrement } returns 15_000L
        val requestedPositions = mutableListOf<Long>()
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { _, _, pos -> requestedPositions += pos; source(pos) },
            StreamRelativeControl(),
        )
        engine.play("ep1", "u1", startPositionMs = 480_000L)

        val sessionPlayer = SeekInterceptingPlayer(
            underlying,
            EngineSeekRouter(engine, this),
            engine.asAbsoluteTimeline(),
        )
        sessionPlayer.seekBack()
        advanceUntilIdle()

        assertEquals(listOf(480_000L, 465_000L), requestedPositions)
    }

    /**
     * 锁屏/通知栏读到的是 `MediaSession` 背后这个 Player 报告的位置与时长。
     * `getCurrentPosition()` 必须报绝对位置,`getDuration()` 必须报元数据总时长——转码流是
     * chunked AAC,底层 `duration` 往往是 `C.TIME_UNSET`(mockk relaxed 下为 0),不能直接用。
     */
    @Test fun `currentPosition 报绝对位置,duration 报元数据总时长,都不取底层 player 的值`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 20_000L
        every { underlying.duration } returns 0L
        val control = StreamRelativeControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)
        engine.play("ep1", "u1", startPositionMs = 480_000L)
        control.currentPositionMs = 20_000L      // 新流已经播了 20 秒

        val sessionPlayer = SeekInterceptingPlayer(
            underlying,
            SeekRouter { },
            engine.asAbsoluteTimeline(durationProvider = { 1_500_000L }),
        )

        assertEquals(500_000L, sessionPlayer.currentPosition)
        assertEquals(500_000L, sessionPlayer.contentPosition)
        assertEquals(1_500_000L, sessionPlayer.duration)
        assertEquals(1_500_000L, sessionPlayer.contentDuration)
    }
}
