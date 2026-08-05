package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ITEM_ID = "ep1"
private const val USER_ID = "u1"

/** 先按下的那一次快进(resolve 慢),和随后按下的那一次(resolve 快)。 */
private const val SLOW_SEEK_MS = 60_000L
private const val FAST_SEEK_MS = 120_000L

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

    /** 本地缓存文件源(Task 4):streamUrl 指向本地文件,`isLocalFile = true`。 */
    private fun localSource(startPositionMs: Long) = PlaybackSource(
        itemId = ITEM_ID,
        mediaSourceId = "ms1",
        streamUrl = "file:///cache/$ITEM_ID.m4a",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = null,
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
        isLocalFile = true,
    )

    /**
     * 记录每次被要求"重新 prepare"的 URL,以及每次被要求直接 seek 的位置。
     *
     * 后者只应该在本地缓存源上发生——远端源的 seek 走"重新 resolve + prepare"那条路,
     * [seekedPositions] 应该始终为空;这正是「远端源行为完全不变」这条回归防线的观测点。
     */
    private class RecordingPlayerControl : PlayerControl {
        val preparedUrls = mutableListOf<String>()
        val seekedPositions = mutableListOf<Long>()
        var released = false
        /** 流内相对位置(见 [PlayerControl.currentPositionMs]);绝对位置语义在 AbsolutePositionTest 里覆盖。 */
        override var currentPositionMs: Long = 0L
        override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) { preparedUrls += url }
        override fun seekTo(positionMs: Long) { seekedPositions += positionMs; currentPositionMs = positionMs }
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
        // 防回归核心:远端源整个过程绝不能碰 PlayerControl.seekTo —— 这是本任务风险最高的一条断言。
        assertTrue(control.seekedPositions.isEmpty(), "远端源 seek 不该调用 player 的 seekTo,应为空:${control.seekedPositions}")
    }

    // ---- Task 4:本地缓存源(isLocalFile = true)的 seek 走另一条分支 ----

    @Test fun `本地缓存源首次 play 时,prepare 之后播放器被 seek 到 startPositionMs`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, startPositionMs -> localSource(startPositionMs) }
        val engine = AudioPlaybackEngineImpl(provider, control)

        engine.play(ITEM_ID, USER_ID, startPositionMs = 480_000L)

        assertEquals(1, control.preparedUrls.size)
        assertEquals(listOf(480_000L), control.seekedPositions)
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    @Test fun `本地缓存源的 seekTo 不重新 resolve,直接让播放器 seek`() = runTest {
        val control = RecordingPlayerControl()
        var resolveCallCount = 0
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            resolveCallCount++
            localSource(startPositionMs)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 10_000L)
        val resolveCallsAfterPlay = resolveCallCount

        engine.seekTo(90_000L)

        assertEquals(
            resolveCallsAfterPlay,
            resolveCallCount,
            "本地源 seek 不该再调用一次 PlaybackSourceProvider.resolve",
        )
        assertEquals(1, control.preparedUrls.size, "本地源 seek 不该重新 prepare")
        assertEquals(listOf(10_000L, 90_000L), control.seekedPositions)
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

    // ---- 稳定性根因 #2:连点快进时,先发出的慢 resolve 不得覆盖后发出的快 resolve ----

    /**
     * 生产路径上唯一存在并发的地方:`SeekInterceptingPlayer.seekForward()` → [EngineSeekRouter] →
     * `scope.launch { engine.seekTo(...) }`。**每一次按键都新起一个协程,谁也不等谁。**
     * 用户连点两下快进,就是两个并发的 [resolveAndPrepare] 在跑,各自一次
     * `PlaybackInfo` 往返 + 一次 L1 探测 GET。
     *
     * 修复前:哪一次**先完成**哪一次说了算。而"先按下的那次"完全可能后完成 —— 它撞上一次慢的
     * `PlaybackInfo`、或者服务端起转码起得慢。用户在真机上看到的就是:连点三下快进,画面/进度条
     * 先跳到最后一个目标,一两秒后**自己退回**到第一个目标。这也正是"seek 之后卡住/乱跳"的形状。
     *
     * 修复后:**最后请求的那一次说了算**,迟到的旧结果被整条丢弃(不 prepare、不动流基准、
     * 不改 state)。
     *
     * 本用例用虚拟时间精确编排这个交错,不依赖任何真实网络时序 —— 项目铁律 6。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `连点 seek 时先发出的慢 resolve 不得覆盖后发出的快 resolve`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            // 先按下的那一次(SLOW_SEEK_MS)慢,后按下的那一次快 —— 真机上这个交错很常见。
            delay(if (startPositionMs == SLOW_SEEK_MS) 3_000L else 50L)
            source(startPositionMs)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)

        launch { engine.seekTo(SLOW_SEEK_MS) }
        advanceTimeBy(100L)             // 用户在 100ms 后又按了一下
        launch { engine.seekTo(FAST_SEEK_MS) }
        advanceUntilIdle()

        assertEquals(
            source(FAST_SEEK_MS).streamUrl,
            control.preparedUrls.last(),
            "最终留在播放器上的必须是最后请求的那一次 seek(${FAST_SEEK_MS}ms),不是先发出但后完成的那一次",
        )
        assertEquals(
            FAST_SEEK_MS,
            (engine.state.value as PlaybackEngineState.Ready).startPositionMs,
            "state 里的流基准也必须是最后请求的那一次 —— 否则绝对位置会整体偏掉",
        )
        assertEquals(FAST_SEEK_MS, engine.absolutePositionMs)
    }

    /**
     * 同一件事的另一面:**过期请求失败了也不许把状态打成 Error。**
     *
     * 用户连点两下快进,第一下的 resolve 慢慢地失败了(超时/服务端 503),第二下已经成功接上流
     * 正在出声。修复前那个迟到的失败会把 `state` 打成 `Error`,UI 弹出「该条目无法播放」——
     * 而耳机里明明在响。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `过期 seek 失败时不得把已经成功的新一次 seek 打成 Error`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            if (startPositionMs == SLOW_SEEK_MS) {
                delay(3_000L)
                error("这次 resolve 超时失败了")
            }
            delay(50L)
            source(startPositionMs)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)
        engine.play(ITEM_ID, USER_ID, startPositionMs = 0L)

        launch { engine.seekTo(SLOW_SEEK_MS) }
        advanceTimeBy(100L)
        launch { engine.seekTo(FAST_SEEK_MS) }
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue(
            state is PlaybackEngineState.Ready && state.startPositionMs == FAST_SEEK_MS,
            "迟到的失败必须被丢弃,状态应停在最后一次成功的 seek,实际是 $state",
        )
    }

    /**
     * 同一个机制的第三面:`PlaybackService.onDestroy` 调 [AudioPlaybackEngine.reset] 那一刻,
     * 可能正有一次 resolve 在飞。它落地时播放器已经被 `stop()` + `clearMediaItems()` 了,
     * 再 prepare 一条流 + 发 `Ready` 就是凭空复活一个没人在听的播放会话
     * (进度协调器会照着它给服务端发 `Sessions/Playing`)。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `reset 之后,正在飞的那次 resolve 落地时不得复活播放`() = runTest {
        val control = RecordingPlayerControl()
        val provider = PlaybackSourceProvider { _, _, pos ->
            delay(1_000L)
            source(pos)
        }
        val engine = AudioPlaybackEngineImpl(provider, control)

        launch { engine.play(ITEM_ID, USER_ID, startPositionMs = 0L) }
        advanceTimeBy(100L)
        engine.reset()
        advanceUntilIdle()

        assertEquals(PlaybackEngineState.Idle, engine.state.value)
        assertTrue(control.preparedUrls.isEmpty(), "reset 之后不该再有任何流被 prepare")
    }

    @Test fun `release 委托给 PlayerControl`() {
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { _, _, pos -> source(pos) }, control)

        engine.release()

        assertTrue(control.released)
    }
}
