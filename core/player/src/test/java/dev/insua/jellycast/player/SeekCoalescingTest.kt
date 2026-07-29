package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 复现并修复:连点快进大约 1/7 会弹出「该条目无法播放」,而耳机里其实还在正常出声。
 *
 * ⚠️ 根因**不是**"迟到的结果覆盖了更新的状态"——那条路已经被 [AudioPlaybackEngineImpl] 既有的
 * `requestSeq`/`isStale` 令牌机制挡住了(见 [AudioPlaybackEngineTest] 的
 * "连点 seek 时先发出的慢 resolve 不得覆盖后发出的快 resolve"/"过期 seek 失败时不得把已经成功的
 * 新一次 seek 打成 Error")。
 *
 * 真正的根因:**每一次按键都无条件起一个新的 resolve 请求**——
 * `SeekInterceptingPlayer.seekForward()`/`seekTo()` → [EngineSeekRouter] →
 * `scope.launch { engine.seekTo(...) }`,谁也不等谁、谁也不取消谁。而群晖 J4125 只能扛有限的
 * 并发转码探测(`PlaybackSourceResolver`「稳定性根因 #3」的 KDoc:连点快进就是几个孤儿转码任务和
 * 正在听的那条抢 CPU)。连点两下快进,**第二下**(真正最新、按 `requestSeq` 判定完全不 stale 的
 * 那一次)可能因为第一下还占着并发名额而竞争失败——它本身就是"最新",令牌机制救不了它,
 * `state` 被打成 Error,而播放器上还在放第一下发起前的那条旧流,声音完全没有断。
 *
 * 用一个"同时只允许 N 个并发 resolve、超过就直接失败"的假 resolver 确定性地模拟这种竞争,
 * 不依赖真实网络时序/真实线程调度(项目铁律 6:核心逻辑必须可离线单测,不能靠一个真的会偶发的
 * 竞态去验证修复——"flaky reproduction is worse than none")。
 *
 * 修复:[EngineSeekRouter] 在真正调用 [AudioPlaybackEngine.seekTo] 之前,先取消上一次还没落地的
 * 请求(seek 防抖 + 连点合并)。这是在 `requestSeq`/`isStale` 令牌机制之上叠加,不是另起一套并行
 * 方案——令牌机制继续兜底"取消信号还没来得及生效、resolve 已经跑完"那极小的窗口;这一层从根上
 * 减少冗余请求本身,不让陈旧请求有机会去抢并发名额。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekCoalescingTest {

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

    private class RecordingPlayerControl : PlayerControl {
        val preparedUrls = mutableListOf<String>()
        override var currentPositionMs: Long = 0L
        override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) { preparedUrls += url }
        override fun release() {}
    }

    /** 模拟群晖 J4125 只扛得住 [maxConcurrent] 个并发转码探测,超出的那个直接竞争失败。 */
    private fun contentionLimitedProvider(inFlight: AtomicInteger, maxConcurrent: Int = 1) =
        PlaybackSourceProvider { _, _, startPositionMs ->
            val concurrent = inFlight.incrementAndGet()
            try {
                if (concurrent > maxConcurrent) error("J4125 转码任务打架,这次探测竞争失败")
                delay(200L)
                source(startPositionMs)
            } finally {
                inFlight.decrementAndGet()
            }
        }

    @Test fun `连点快进合并成一次 resolve,不会因为陈旧请求抢并发名额而把最新一次打成 Error`() = runTest {
        val inFlight = AtomicInteger(0)
        val control = RecordingPlayerControl()
        val engine = AudioPlaybackEngineImpl(contentionLimitedProvider(inFlight), control)
        engine.play("ep1", "u1", startPositionMs = 0L)

        val router = EngineSeekRouter(engine, this)

        // 连点两下快进,第二下在第一下的 resolve 还没完成时就到达——真机上这个交错很常见。
        router.seekTo(60_000L)
        router.seekTo(120_000L)
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue(
            state is PlaybackEngineState.Ready && state.startPositionMs == 120_000L,
            "连点后应该只有最后一次目标真正生效,不该因为更早的陈旧请求抢并发名额而把最新一次打成 " +
                "Error(此刻耳机里其实还在正常出声),实际状态是 $state",
        )
    }

    @Test fun `连点三下快进只有最后一次真正发起 resolve,更早的请求在落地前就被取消`() = runTest {
        val control = RecordingPlayerControl()
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, pos -> requestedPositions += pos; delay(50L); source(pos) }
        val engine = AudioPlaybackEngineImpl(provider, control)
        engine.play("ep1", "u1", startPositionMs = 0L)
        requestedPositions.clear()
        control.preparedUrls.clear()

        val router = EngineSeekRouter(engine, this)
        router.seekTo(30_000L)
        router.seekTo(60_000L)
        router.seekTo(90_000L)
        advanceUntilIdle()

        assertEquals(
            listOf(90_000L),
            requestedPositions,
            "更早的两次连点应该在防抖窗口内被直接取消,从未发起 resolve —— 不只是结果被丢弃",
        )
        assertEquals(1, control.preparedUrls.size)
    }
}
