package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ITEM_ID = "ep1"
private const val USER_ID = "u1"

/**
 * 覆盖 Spike 约束下最容易做错的两件事:
 * 1. seek 必须实现为"带新的 startPositionMs 重新 resolve + 重新 prepare",不是 player.seekTo
 *    (docs/superpowers/specs/2026-07-25-spike-results.md:转码流 `Accept-Ranges: none`)。
 * 2. resolve() 抛异常(Task 8 契约:无 mediaSource / PlaybackInfo 调用失败)必须变成可观察的
 *    Error 状态,不得向上抛出、不得崩掉调用方。
 *
 * [PlayerControl] 是假实现,完全脱离 ExoPlayer/Context,纯 JVM 可跑。
 */
class AudioPlaybackEngineTest {

    private fun source(startPositionMs: Long) = PlaybackSource(
        itemId = ITEM_ID,
        mediaSourceId = "ms1",
        streamUrl = "https://nas.example.com/Audio/$ITEM_ID/universal?startTimeTicks=${startPositionMs * 10_000}",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = "session-1",
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    /** 记录每次被要求"重新 prepare"的 URL。这个假实现压根没有 seekTo 方法可调 ——
     *  结构上就杜绝了 AudioPlaybackEngine 误用 player.seekTo 的可能。 */
    private class RecordingPlayerControl : PlayerControl {
        val preparedUrls = mutableListOf<String>()
        var released = false
        override fun setMediaItemAndPrepare(url: String) { preparedUrls += url }
        override fun release() { released = true }
    }

    @Test fun `play 成功后状态变为 Ready 并把解析出的 URL 交给播放器`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, startPositionMs -> source(startPositionMs) }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)

        val state = engine.state.value
        assertTrue(state is PlaybackEngineState.Ready)
        assertEquals(1, control.preparedUrls.size)
    }

    @Test fun `seekTo 用新的 startPositionMs 重新 resolve 并重新 prepare,不是 player 的 seekTo`() = runTest {
        val control = RecordingPlayerControl()
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            requestedPositions += startPositionMs
            source(startPositionMs)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)
        engine.seekTo(90_000L)

        assertEquals(listOf(0L, 90_000L), requestedPositions)
        assertEquals(2, control.preparedUrls.size)
        assertTrue(control.preparedUrls[1].contains("startTimeTicks=900000000"), control.preparedUrls[1])
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    @Test fun `resolve 抛异常时变为 Error 状态而不是向上抛出`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, _ -> error("PlaybackInfo 调用失败") }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.play(ITEM_ID, USER_ID)   // 不应抛出

        val state = engine.state.value
        assertTrue(state is PlaybackEngineState.Error)
        assertEquals(ITEM_ID, (state as PlaybackEngineState.Error).itemId)
        assertTrue(control.preparedUrls.isEmpty())
    }

    @Test fun `seekTo 前未 play 过时安全地什么都不做`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, pos -> source(pos) }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.seekTo(5_000L)

        assertEquals(PlaybackEngineState.Idle, engine.state.value)
        assertTrue(control.preparedUrls.isEmpty())
    }

    @Test fun `release 委托给 PlayerControl`() {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)

        engine.release()

        assertTrue(control.released)
    }
}
