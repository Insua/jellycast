package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

/**
 * v3 复审 Finding 1(Important):一个还悬而未决的防抖 seek,不能落到发起它时以外的条目上。
 *
 * 复现路径(和 finding 描述一致):锁屏拖动进度条 → [EngineSeekRouter] 排进 250ms 防抖窗口,还没
 * 落地 → 250ms 内又点了"下一集"([SeekInterceptingPlayer.seekToNextMediaItem] →
 * [EngineQueueNavigator.next] → `engine.play(nextId, ...)`,把 `currentItemId` 切到了下一集)→
 * 防抖窗口到期,悬而未决的那次 seek 执行 `engine.seekTo(oldPosition)`——[AudioPlaybackEngineImpl]
 * 内部按**此刻的** `currentItemId`(已经是新条目)隐式取值,于是拿着旧位置去给新条目重新 resolve。
 * 新条目会从旧集数的播放进度开始,而不是从头播。
 *
 * 之所以既有的 `requestSeq`/`isStale` 令牌机制挡不住这次:这次 seek 落地时**没有更晚的 seek 请求**
 * 在竞争——它自己就是"最新"的一次请求,令牌机制只防"迟到的旧结果覆盖新结果",不认识"这次请求
 * 从一开始就已经问错了条目"这件事。
 *
 * 全部用虚拟时间(`runTest`/`advanceTimeBy`/`runCurrent`)编排,不依赖真实延迟——铁律 6。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekItemChangeRaceTest {

    private fun source(itemId: String, startPositionMs: Long) = PlaybackSource(
        itemId = itemId,
        mediaSourceId = "ms-$itemId",
        streamUrl = "https://nas.example.com/Audio/$itemId/universal?startTimeTicks=${startPositionMs * 10_000}",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = null,
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    private class RecordingPlayerControl : PlayerControl {
        override var currentPositionMs: Long = 0L
        override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) {}
        override fun release() {}
    }

    @Test fun `拖动进度条后 250ms 防抖窗口内切到下一集,悬而未决的旧 seek 不得用旧位置重新 resolve 新条目`() = runTest {
        val resolved = mutableListOf<Pair<String, Long>>() // (itemId, startPositionMs)
        val provider = PlaybackSourceProvider { itemId, _, startPositionMs ->
            resolved += itemId to startPositionMs
            source(itemId, startPositionMs)
        }
        val engine = AudioPlaybackEngineImpl(provider, RecordingPlayerControl())
        val queue = PlayQueue().apply { setQueue(listOf(ep("ep1"), ep("ep2")), 0) }
        engine.play("ep1", "u1", startPositionMs = 0L)
        resolved.clear()

        val router = EngineSeekRouter(engine, this)
        val navigator = EngineQueueNavigator(queue, engine, { "u1" }, this)
        val sessionPlayer = SeekInterceptingPlayer(mockk(relaxed = true), router, queueNavigator = navigator)

        // 用户拖动锁屏进度条到 90s —— 排进 250ms 防抖窗口,还没落地。
        sessionPlayer.seekTo(90_000L)

        // 250ms 窗口内点了"下一集"。只推进到"当前可跑的都跑完"(不推进虚拟时钟),这样拖动进度条
        // 那次 seek(还在 delay(250) 里挂起)仍然悬而未决,和真机上的时序一致。
        sessionPlayer.seekToNextMediaItem()
        runCurrent()

        assertEquals("ep2", queue.current.value?.id, "下一集应该已经切过去了")
        assertEquals(listOf("ep2" to 0L), resolved, "下一集应该从头播放,不该带着上一次 seek 的旧位置")

        // 让防抖窗口过期——悬而未决的旧 seek 如果没被正确处理,会在这里触发。
        advanceTimeBy(300L)
        runCurrent()

        assertFalse(
            resolved.any { it.first == "ep2" && it.second == 90_000L },
            "悬而未决的旧 seek 不能用锁屏拖动的旧位置(90s)重新 resolve 已经切换到的新条目(ep2)," +
                "实际 resolved=$resolved",
        )
        assertEquals(
            listOf("ep2" to 0L),
            resolved,
            "防抖窗口到期后,针对已经离开的旧条目(ep1)的悬而未决 seek 不应该触发任何新的 resolve",
        )
    }
}
