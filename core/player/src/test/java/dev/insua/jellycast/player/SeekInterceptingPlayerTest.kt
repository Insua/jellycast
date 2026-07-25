package dev.insua.jellycast.player

import androidx.media3.common.Player
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 闭合 Task 9/10 遗留的 Important 缺陷:[PlaybackService] 曾经把 [androidx.media3.session.MediaSession]
 * 建在裸 ExoPlayer 上,没有任何拦截层。Media3 默认的 MediaSession 回调会把 seek 家族命令
 * (锁屏拖动进度条、`MediaController.seekTo`、快进快退按钮)直接转发给 `player.seekTo()` ——
 * 而 Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)转码音频流
 * `Accept-Ranges: none`,`player.seekTo()` 在这条流上不可靠。
 *
 * [SeekInterceptingPlayer] 用 `ForwardingPlayer` 包一层,覆写全部"能实际移动播放位置"的 seek
 * 方法,统一委派给 [SeekRouter],绝不调用 `super.xxx()`——结构上杜绝了落到底层 player 字节级
 * seek 的可能。
 *
 * `第一个测试是本 Task 的核心验收项`:证明通过 Player 接口发出的 seek 不落到底层
 * `player.seekTo`,而是走完整链路(SeekInterceptingPlayer -> EngineSeekRouter ->
 * AudioPlaybackEngineImpl -> PlaybackSourceProvider)触发以新位置重新 resolve + prepare。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekInterceptingPlayerTest {

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

    @Test fun `核心验收 -- 通过 Player 接口发出的 seek 不落到底层 player seekTo,而是触发完整重新 resolve 链路`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            requestedPositions += startPositionMs
            source(startPositionMs)
        }
        val preparedUrls = mutableListOf<String>()
        val playerControl = object : PlayerControl {
            override val currentPositionMs: Long = 0L
            override fun setMediaItemAndPrepare(url: String) { preparedUrls += url }
            override fun release() {}
        }
        val engine = AudioPlaybackEngineImpl(provider, playerControl)
        engine.play("ep1", "u1", startPositionMs = 0L)

        val router = EngineSeekRouter(engine, this)
        val sessionPlayer = SeekInterceptingPlayer(underlying, router)

        sessionPlayer.seekTo(90_000L)
        advanceUntilIdle()

        verify(exactly = 0) { underlying.seekTo(any()) }
        assertEquals(listOf(0L, 90_000L), requestedPositions)
        assertTrue(preparedUrls.last().contains("startTimeTicks=900000000"), preparedUrls.last())
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    @Test fun `seekTo(long) 路由给 SeekRouter,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekTo(12_345L)

        assertEquals(listOf(12_345L), seen)
        verify(exactly = 0) { underlying.seekTo(any()) }
    }

    @Test fun `seekTo(mediaItemIndex, positionMs) 路由给 SeekRouter,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekTo(0, 7_000L)

        assertEquals(listOf(7_000L), seen)
        verify(exactly = 0) { underlying.seekTo(any(), any()) }
    }

    @Test fun `seekToDefaultPosition 家族路由为 seek 到 0,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekToDefaultPosition()
        sessionPlayer.seekToDefaultPosition(0)

        assertEquals(listOf(0L, 0L), seen)
        verify(exactly = 0) { underlying.seekToDefaultPosition() }
        verify(exactly = 0) { underlying.seekToDefaultPosition(any()) }
    }

    /**
     * 只提供绝对位置、不提供总时长的假 [AbsoluteTimeline]。
     *
     * ⚠️ **重新基线说明(全支线复审 Critical 1)**:下面三个 seekBack/seekForward 测试原来把
     * `underlying.currentPosition` 打桩成 20_000 / 5_000,并断言目标位置由**底层 player 的位置**
     * 算出。那个断言把缺陷写死成了规格:底层 `currentPosition` 是"当前这条转码流里播了多久",
     * 每次 seek/续播换流都从 0 重新开始,不是条目内绝对位置。以它为快进快退起点,从 8:00 续播后
     * 按一下快进就会跳到 0:30(往回 7 分半)。
     *
     * 现在断言的期望值一个都没放松(仍然是 5_000 / 0 / 50_000),但**位置的来源换成了权威的
     * 绝对时间轴**,并且把 `underlying.currentPosition` 打桩成一个明显不同的值——如果实现回退去读
     * 底层位置,这三个测试立刻变红。
     */
    private fun absoluteTimeline(positionMs: Long) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = positionMs
        override fun absoluteDurationMs(): Long? = null
    }

    @Test fun `seekBack 用绝对位置和回退步长算出目标位置再路由,不读底层流内相对位置,不调用底层 seekBack`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 3_000L      // 流内相对位置:不该被用到
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(20_000L))

        sessionPlayer.seekBack()

        assertEquals(listOf(5_000L), seen)
        verify(exactly = 0) { underlying.seekBack() }
    }

    @Test fun `seekBack 不会算出负数位置`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 999_000L     // 流内相对位置:不该被用到
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(5_000L))

        sessionPlayer.seekBack()

        assertEquals(listOf(0L), seen)
    }

    @Test fun `seekForward 用绝对位置和快进步长算出目标位置再路由,不读底层流内相对位置,不调用底层 seekForward`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 3_000L       // 流内相对位置:不该被用到
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(20_000L))

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
        verify(exactly = 0) { underlying.seekForward() }
    }

    /** 没有接绝对时间轴时(默认 [AbsoluteTimeline.Unknown])必须安全回退到底层 player,而不是报 0。 */
    @Test fun `未接绝对时间轴时回退到底层 player 的位置,不至于把起点当成 0`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 20_000L
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
    }

    /**
     * 复审发现(Task 11/12 review Finding 1):`ForwardingPlayer.seekToPrevious()` 未被覆写时,
     * 对单条目非直播场景(本项目模型),`BasePlayer.seekToPrevious()`(media3-common 1.10.1
     * 字节码核实)在"没有上一条目 或 currentPosition > maxSeekToPreviousPosition"时会调用
     * `seekToCurrentItem(0, ...)`——对当前条目做一次真正的位置 0 的 seek,直接转发到底层裸
     * ExoPlayer,完全绕开本类的拦截。这正是蓝牙/锁屏"上一曲"键通过 MediaSession 的
     * SEEK_TO_PREVIOUS 命令触发的路径。用和核心验收测试一样的真实
     * AudioPlaybackEngineImpl + 假 PlaybackSourceProvider/PlayerControl 编排,证明
     * seekToPrevious() 也必须经过完整重新 resolve 链路,而不是落到底层 seekToPrevious()。
     */
    @Test fun `seekToPrevious 不落到底层 player seekToPrevious,而是路由为 seek 到 0 并触发完整重新 resolve 链路`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            requestedPositions += startPositionMs
            source(startPositionMs)
        }
        val preparedUrls = mutableListOf<String>()
        val playerControl = object : PlayerControl {
            override val currentPositionMs: Long = 0L
            override fun setMediaItemAndPrepare(url: String) { preparedUrls += url }
            override fun release() {}
        }
        val engine = AudioPlaybackEngineImpl(provider, playerControl)
        engine.play("ep1", "u1", startPositionMs = 90_000L)

        val router = EngineSeekRouter(engine, this)
        val sessionPlayer = SeekInterceptingPlayer(underlying, router)

        sessionPlayer.seekToPrevious()
        advanceUntilIdle()

        verify(exactly = 0) { underlying.seekToPrevious() }
        assertEquals(listOf(90_000L, 0L), requestedPositions)
        assertTrue(preparedUrls.last().contains("startTimeTicks=0"), preparedUrls.last())
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }
}
