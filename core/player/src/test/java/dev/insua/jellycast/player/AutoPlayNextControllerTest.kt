package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

/**
 * 自动连播:队列驱动在播放器外部完成,只依赖 [PlayQueue](纯 Kotlin,已单测)、
 * [PlaybackSourceProvider](fun interface,单测可造假)、[JellyfinApi](MockK 造假)、
 * `autoPlayNext: Flow<Boolean>`(生产传 `PreferencesStore.autoPlayNext`,单测传 `flowOf(...)`)——
 * 完全不需要真实播放器 / Android Context,离线可单测。
 */
class AutoPlayNextControllerTest {

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

    @Test fun `autoPlayNext 关闭时不连播,队列不推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(false))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
    }

    @Test fun `队列还有下一项时直接推进并解析新源`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val resolvedIds = mutableListOf<String>()
        val provider = PlaybackSourceProvider { id, _, _ -> resolvedIds += id; source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.itemId)
        assertEquals(listOf("2"), resolvedIds)
        assertEquals("2", queue.current.value?.id)
    }

    @Test fun `队列耗尽时调用 NextUp 补充队列并播放第一项`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(
            items = listOf(BaseItemDto(id = "9", name = "第 9 集", type = "Episode"))
        )
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("9", result?.itemId)
        assertEquals("9", queue.current.value?.id)
    }

    @Test fun `队列耗尽且 NextUp 也空时不连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(items = emptyList())
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
    }

    @Test fun `NextUp 调用抛异常时静默不连播,不向上抛出`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } throws java.io.IOException("offline")
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")   // 不应抛出

        assertNull(result)
    }

    // ---- 「播完本集」睡眠定时模式(Finding 1):必须在真正决定"要不要连播"的这个类里拦截,
    // 而不是靠上层 UI 自己 pause——否则 Task 22 接上真实 STATE_ENDED 回调时,队列已经在这里被
    // 推进过、下一集已经开始解析播放,UI 层再暂停也晚了。----

    @Test fun `武装播完本集后即使 autoPlayNext 开启也不连播,队列不推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val resolvedIds = mutableListOf<String>()
        val provider = PlaybackSourceProvider { id, _, _ -> resolvedIds += id; source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
        assertEquals(emptyList<String>(), resolvedIds)
    }

    @Test fun `播完本集武装状态是一次性的,消费后下次结束恢复正常连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.onPlaybackEnded("u1")               // 消费掉这次武装
        val secondResult = controller.onPlaybackEnded("u1")

        assertEquals("2", secondResult?.itemId)
    }

    @Test fun `disarm 后不再拦截连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val provider = PlaybackSourceProvider { id, _, _ -> source(id) }
        val controller = AutoPlayNextController(queue, provider, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.disarmStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.itemId)
    }
}
