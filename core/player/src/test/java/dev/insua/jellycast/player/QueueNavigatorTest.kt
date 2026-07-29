package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

/**
 * 设计文档 §3.3:上一集/下一集不伪造 Media3 播放列表,而是让 [SeekInterceptingPlayer] 按
 * [PlayQueue] 的真实状态声明/执行这两个命令,委派到 [EngineQueueNavigator]——和
 * `AppSessionViewModel.play()` / [PlaybackEndedAdvancer] 走同一条路(经
 * [AudioPlaybackEngine.play] 重新准备,不绕开 engine 的内部状态)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueNavigatorTest {

    private fun source(itemId: String) = PlaybackSource(
        itemId = itemId,
        mediaSourceId = "ms",
        streamUrl = "https://nas.example.com/Audio/$itemId/universal",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = null,
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    private class RecordingPlayerControl : PlayerControl {
        val preparedUrls = mutableListOf<String>()
        override var currentPositionMs: Long = 0L
        override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) { preparedUrls += url }
        override fun release() {}
    }

    @Test fun `hasNext hasPrevious 反映 PlayQueue 的真实状态`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2"), ep("3")), 1) }
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { id, _, _ -> source(id) }, RecordingPlayerControl())
        val navigator = EngineQueueNavigator(queue, engine, { "u1" }, this)

        assertTrue(navigator.hasNext())
        assertTrue(navigator.hasPrevious())
    }

    @Test fun `next 推进队列游标并经 engine play 重新播放下一条`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val resolvedIds = mutableListOf<String>()
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { id, _, _ -> resolvedIds += id; source(id) },
            RecordingPlayerControl(),
        )
        val navigator = EngineQueueNavigator(queue, engine, { "u1" }, this)

        navigator.next()
        advanceUntilIdle()

        assertEquals("2", queue.current.value?.id)
        assertEquals(listOf("2"), resolvedIds)
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    @Test fun `previous 回退队列游标并经 engine play 重新播放上一条`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 1) }
        val resolvedIds = mutableListOf<String>()
        val engine = AudioPlaybackEngineImpl(
            PlaybackSourceProvider { id, _, _ -> resolvedIds += id; source(id) },
            RecordingPlayerControl(),
        )
        val navigator = EngineQueueNavigator(queue, engine, { "u1" }, this)

        navigator.previous()
        advanceUntilIdle()

        assertEquals("1", queue.current.value?.id)
        assertEquals(listOf("1"), resolvedIds)
    }

    @Test fun `拿不到登录用户时安全地什么都不做,队列游标不移动`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { id, _, _ -> source(id) }, RecordingPlayerControl())
        val navigator = EngineQueueNavigator(queue, engine, { null }, this)

        navigator.next()
        advanceUntilIdle()

        assertEquals(
            "1",
            queue.current.value?.id,
            "拿不到 userId 时不该把队列游标推进到一个实际上没有播放的位置——队列和引擎状态必须保持同步",
        )
    }

    @Test fun `队列到底时 next 安全地什么都不做`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val engine = AudioPlaybackEngineImpl(PlaybackSourceProvider { id, _, _ -> source(id) }, RecordingPlayerControl())
        val navigator = EngineQueueNavigator(queue, engine, { "u1" }, this)

        navigator.next()
        advanceUntilIdle()

        assertFalse(navigator.hasNext())
        assertEquals("1", queue.current.value?.id)
    }

    @Test fun `QueueNavigator None 恒无上一条下一条,调用安全地什么都不做`() {
        assertFalse(QueueNavigator.None.hasNext())
        assertFalse(QueueNavigator.None.hasPrevious())
        QueueNavigator.None.next()
        QueueNavigator.None.previous()
    }
}
