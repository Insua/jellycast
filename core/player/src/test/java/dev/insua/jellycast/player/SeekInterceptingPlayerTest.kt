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
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekTo(12_345L)

        assertEquals(listOf(12_345L), seen)
        verify(exactly = 0) { underlying.seekTo(any()) }
    }

    @Test fun `seekTo(mediaItemIndex, positionMs) 路由给 SeekRouter,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekTo(0, 7_000L)

        assertEquals(listOf(7_000L), seen)
        verify(exactly = 0) { underlying.seekTo(any(), any()) }
    }

    @Test fun `seekToDefaultPosition 家族路由为 seek 到 0,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekToDefaultPosition()
        sessionPlayer.seekToDefaultPosition(0)

        assertEquals(listOf(0L, 0L), seen)
        verify(exactly = 0) { underlying.seekToDefaultPosition() }
        verify(exactly = 0) { underlying.seekToDefaultPosition(any()) }
    }

    @Test fun `seekBack 用底层当前进度和回退步长算出目标位置再路由,不调用底层 seekBack`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 20_000L
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekBack()

        assertEquals(listOf(5_000L), seen)
        verify(exactly = 0) { underlying.seekBack() }
    }

    @Test fun `seekBack 不会算出负数位置`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 5_000L
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekBack()

        assertEquals(listOf(0L), seen)
    }

    @Test fun `seekForward 用底层当前进度和快进步长算出目标位置再路由,不调用底层 seekForward`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 20_000L
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying) { seen += it }

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
        verify(exactly = 0) { underlying.seekForward() }
    }
}
