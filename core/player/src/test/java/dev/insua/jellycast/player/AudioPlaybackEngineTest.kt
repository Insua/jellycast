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
        /** 流内相对位置(见 [PlayerControl.currentPositionMs]);绝对位置语义在 AbsolutePositionTest 里覆盖。 */
        override var currentPositionMs: Long = 0L
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

    // ---- 复审 Finding 2:播放宿主销毁后,引擎必须承认"什么都没在播" ----

    /**
     * `PlaybackService.onDestroy` 会 `stop()` + `clearMediaItems()`(但刻意**不** release 那个
     * `@Singleton` ExoPlayer,见该方法 KDoc)。此后引擎手里的两样东西全都过期了:
     * - `currentStartPositionMs` 这个流基准 —— 播放器位置已归零,绝对位置会衰减成一个几分钟前的值;
     * - `Ready` 状态 —— Service 重建后重新订阅时,它会被 `StateFlow` 重放成"看起来在播",
     *   进度协调器于是给一个没在播的条目发 `Sessions/Playing`(Finding 2 的幽灵会话)。
     *
     * [AudioPlaybackEngine.reset] 就是"把这两样都清干净"。
     */
    @Test fun `reset 之后状态回到 Idle,不会把陈旧的 Ready 重放给重建后的 Service`() = runTest {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 480_000L)

        engine.reset()

        assertEquals(PlaybackEngineState.Idle, engine.state.value)
    }

    @Test fun `reset 之后绝对位置归零,不再报那个已经过期的流基准`() = runTest {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 480_000L)
        control.currentPositionMs = 0L      // clearMediaItems() 之后播放器位置归零

        engine.reset()

        assertEquals(0L, engine.absolutePositionMs)
    }

    /** reset 之后没有"当前条目"可言,迟到的 seek(锁屏残留的命令)必须安全地什么都不做。 */
    @Test fun `reset 之后的 seekTo 不会凭空起一条新流`() = runTest {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)

        engine.reset()
        engine.seekTo(90_000L)

        assertEquals(1, control.preparedUrls.size)
        assertEquals(PlaybackEngineState.Idle, engine.state.value)
    }

    /** reset **不是** release:那个播放器是 `@Singleton`,比 Service 活得久,不能被放掉。 */
    @Test fun `reset 不会释放播放器`() = runTest {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)

        engine.reset()

        assertTrue(!control.released)
    }

    @Test fun `release 委托给 PlayerControl`() {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)

        engine.release()

        assertTrue(control.released)
    }
}
