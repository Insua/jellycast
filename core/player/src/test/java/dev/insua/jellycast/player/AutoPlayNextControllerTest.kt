package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
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
 * [JellyfinApi](MockK 造假)、`autoPlayNext: Flow<Boolean>`(生产传 `PreferencesStore.autoPlayNext`,
 * 单测传 `flowOf(...)`)——完全不需要真实播放器 / Android Context,离线可单测。
 *
 * ⚠️ 稳定性根因 #4:本类**不再**解析播放源,只回答"下一条是哪个条目"。解析只发生在
 * [AudioPlaybackEngine.play] 里一次 —— 见 [AutoPlayNextController] 与 [PlaybackEndedAdvancer] 的类注释。
 */
class AutoPlayNextControllerTest {

    @Test fun `autoPlayNext 关闭时不连播,队列不推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(false))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
    }

    @Test fun `队列还有下一项时直接推进并返回该条目`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
        assertEquals("2", queue.current.value?.id)
    }

    @Test fun `队列耗尽时调用 NextUp 补充队列并播放第一项`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(
            items = listOf(BaseItemDto(id = "9", name = "第 9 集", type = "Episode"))
        )
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("9", result?.id)
        assertEquals("9", queue.current.value?.id)
    }

    @Test fun `队列耗尽且 NextUp 也空时不连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(items = emptyList())
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
    }

    @Test fun `NextUp 调用抛异常时静默不连播,不向上抛出`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } throws java.io.IOException("offline")
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")   // 不应抛出

        assertNull(result)
    }

    // ---- 全支线复审 Important 4:NextUp 补充队列必须走 :core:network 的共享 mapper ----

    /**
     * 本类原来有一份私有的 `BaseItemDto.toMediaItem()`,漏掉了 `imageTag`(和 Season/Episode 的
     * IndexNumber 分流)。后果:自动连播换集之后,迷你条和全屏播放页的封面**变成空白**——因为
     * `MediaItem.posterUrl()` 拿不到 tag 就返回 null。这也是设计文档 §5 的模块边界问题:
     * `:core:player` 本不该自己解析 Jellyfin DTO。
     */
    @Test fun `NextUp 补充的条目保留封面 tag 与季集号,连播后封面不会变空白`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(
            items = listOf(
                BaseItemDto(
                    id = "9",
                    name = "第 9 集",
                    type = "Episode",
                    seriesName = "银魂",
                    seasonNumber = 1,          // ParentIndexNumber
                    episodeNumber = 9,         // IndexNumber
                    runTimeTicks = 15_000_000_000L,
                    imageTags = mapOf("Primary" to "cover-tag-9"),
                )
            )
        )
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.onPlaybackEnded("u1")

        val refilled = queue.current.value
        assertEquals("cover-tag-9", refilled?.imageTag)
        assertEquals("银魂", refilled?.seriesName)
        assertEquals(1, refilled?.seasonNumber)
        assertEquals(9, refilled?.episodeNumber)
        assertEquals(1_500_000L, refilled?.runTimeMs)     // ticks / 10_000,只在 mapper 里换算一次
    }

    /** 共享 mapper 对不认识的类型返回 null;这类条目必须被跳过,不能让一条陌生数据毒死整个队列。 */
    @Test fun `NextUp 返回无法识别的类型时跳过该条,不连播到一个空条目`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.nextUp("u1") } returns ItemsResponseDto(
            items = listOf(BaseItemDto(id = "x", name = "某个音乐视频", type = "MusicVideo"))
        )
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
    }

    // ---- 「播完本集」睡眠定时模式(Finding 1):必须在真正决定"要不要连播"的这个类里拦截,
    // 而不是靠上层 UI 自己 pause——否则 Task 22 接上真实 STATE_ENDED 回调时,队列已经在这里被
    // 推进过、下一集已经开始解析播放,UI 层再暂停也晚了。----

    @Test fun `武装播完本集后即使 autoPlayNext 开启也不连播,队列不推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
    }

    @Test fun `播完本集武装状态是一次性的,消费后下次结束恢复正常连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.onPlaybackEnded("u1")               // 消费掉这次武装
        val secondResult = controller.onPlaybackEnded("u1")

        assertEquals("2", secondResult?.id)
    }

    @Test fun `disarm 后不再拦截连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.disarmStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
    }
}
