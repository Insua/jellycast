package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SERIES = "series-A"
private const val OTHER_SERIES = "series-B"
private const val S1 = "series-A-s1"
private const val S2 = "series-A-s2"
private const val S3 = "series-A-s3"

private fun ep(
    id: String,
    seriesId: String? = SERIES,
    seasonId: String? = S1,
    seasonNumber: Int? = 1,
) = MediaItem(
    id = id,
    kind = MediaKind.EPISODE,
    name = "E$id",
    seasonNumber = seasonNumber,
    seriesId = seriesId,
    seasonId = seasonId,
)

private fun movie(id: String) = MediaItem(id = id, kind = MediaKind.MOVIE, name = "M$id")

private fun epDto(
    id: String,
    seriesId: String = SERIES,
    seasonId: String = S1,
    seasonNumber: Int = 1,
) = BaseItemDto(
    id = id,
    name = "E$id",
    type = "Episode",
    seriesId = seriesId,
    seasonId = seasonId,
    seasonNumber = seasonNumber,
)

private fun seasonDto(id: String, number: Int) =
    BaseItemDto(id = id, name = "S$number", type = "Season", seriesId = SERIES, episodeNumber = number)

private fun items(vararg dto: BaseItemDto) = ItemsResponseDto(items = dto.toList())

/**
 * 自动连播:**跟着剧走,不跟着队列走**(2026-07-29 用户需求)。
 *
 * 决策只依赖三样东西——偏好开关、「播完本集」武装状态,以及"刚播完的那一条是什么"
 * ([PlayQueue.current])。真正的"下一条是哪个"来自 [JellyfinApi] 的剧集顺序,
 * 不是队列里碰巧排在后面的那一项(首页分区把整行当队列,那一行里全是**别的剧**)。
 *
 * 全部离线可测:[JellyfinApi] 用 MockK 造假,不需要真实播放器 / Android Context。
 */
class AutoPlayNextControllerTest {

    // ---- 表格第 1 行:剧集,本季还有下一集 → 播本剧本季的下一集 ----

    @Test fun `本季还有下一集时接着播本季的下一集`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"), epDto("3"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
        assertNull(controller.sequenceEnd.value, "还有得播,不该发出播放结束信号")
    }

    /** 常见路径必须只有一次往返:本季就能找到下一集时不该再去问季列表。 */
    @Test fun `本季能找到下一集时不额外查询季列表`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.onPlaybackEnded("u1")

        coVerify(exactly = 0) { api.seasons(any(), any()) }
    }

    /**
     * 连播之后队列必须重定位到**本剧的集序**,否则:
     * - `MediaControllerPlayerConnection.nowPlaying` 按 `queueItem.id == source.itemId` 匹配,
     *   对不上就是迷你条/通知栏空白;
     * - `PlayerModule` 的 `metadataProvider` 也按 [PlayQueue.current] 查元数据,锁屏封面会掉。
     */
    @Test fun `连播后队列重定位到本剧集序,current 指向新播的那一集`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"), epDto("3"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.onPlaybackEnded("u1")

        assertEquals("2", queue.current.value?.id)
        assertEquals(true, queue.hasPrevious(), "队列现在是本季集序,第 2 集之前还有第 1 集")
        assertEquals(true, queue.hasNext())
    }

    // ---- 用户报告的问题本身:从首页分区点开的剧集 ----

    /**
     * 首页各分区(继续收听 / 下一集 / 最近添加)把**整行**当播放队列传进来,一行里全是不同的剧。
     * 播完之后必须接**本剧的下一集**,而不是那一行里排在后面的另一部剧。
     */
    @Test fun `从首页分区点开的剧集,播完接本剧下一集而不是分区列表的下一项`() = runTest {
        val homeRow = listOf(
            ep("1"),                                                    // 被点开的这一集
            ep("other-1", seriesId = OTHER_SERIES, seasonId = "series-B-s1"), // 同一行里的另一部剧
        )
        val queue = PlayQueue().apply { setQueue(homeRow, 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id, "应该接本剧的下一集,不是首页那一行里的另一部剧")
    }

    // ---- 表格第 2 行:剧集,本季已完 → 下一季的第一集 ----

    @Test fun `本季最后一集播完接下一季的第一集`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        coEvery { api.seasons(SERIES, "u1") } returns items(seasonDto(S1, 1), seasonDto(S2, 2))
        coEvery { api.episodes(SERIES, S2, "u1") } returns
            items(epDto("s2e1", seasonId = S2, seasonNumber = 2), epDto("s2e2", seasonId = S2, seasonNumber = 2))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("s2e1", result?.id)
        assertEquals("s2e1", queue.current.value?.id)
        assertNull(controller.sequenceEnd.value)
    }

    /** 中间夹着一个空季(比如没有内容的特别篇)时跳过去继续往后找,不能就此判定"全剧播完"。 */
    @Test fun `下一季是空的时候继续往后找,不误判为全剧播完`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        coEvery { api.seasons(SERIES, "u1") } returns
            items(seasonDto(S1, 1), seasonDto(S2, 2), seasonDto(S3, 3))
        coEvery { api.episodes(SERIES, S2, "u1") } returns items()
        coEvery { api.episodes(SERIES, S3, "u1") } returns
            items(epDto("s3e1", seasonId = S3, seasonNumber = 3))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("s3e1", result?.id)
    }

    // ---- 表格第 3 行:全剧播完 → 停,回首页,提示「已播放完」 ----

    @Test fun `全剧最后一集播完时不连播,并发出全剧播完的信号`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        coEvery { api.seasons(SERIES, "u1") } returns items(seasonDto(S1, 1))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals(PlaybackSequenceEnd.SERIES_COMPLETED, controller.sequenceEnd.value)
    }

    @Test fun `播放结束信号被消费后清空,不会重复触发回首页`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        coEvery { api.seasons(SERIES, "u1") } returns items(seasonDto(S1, 1))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.onPlaybackEnded("u1")
        controller.onSequenceEndHandled()

        assertNull(controller.sequenceEnd.value)
    }

    // ---- 表格第 4 行:电影 → 停,回首页,**不去 NextUp 找别的东西接着放** ----

    @Test fun `电影播完不连播,发出结束信号,且绝不去 NextUp 抓别的东西`() = runTest {
        // 队列里还有别的条目——电影播完照样不许接着放。
        val queue = PlayQueue().apply { setQueue(listOf(movie("m1"), ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals(PlaybackSequenceEnd.ITEM_COMPLETED, controller.sequenceEnd.value)
        assertEquals("m1", queue.current.value?.id, "队列不该被推进")
        coVerify(exactly = 0) { api.nextUp(any(), any()) }
        coVerify(exactly = 0) { api.seasons(any(), any()) }
        coVerify(exactly = 0) { api.episodes(any(), any(), any()) }
    }

    // ---- 网络不可用绝不允许崩溃:查不到就静默停止 ----

    @Test fun `本季集列表查询失败时静默停止,不抛异常也不发提示信号`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } throws java.io.IOException("offline")
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")   // 不应抛出

        assertNull(result)
        assertNull(controller.sequenceEnd.value, "网络失败不是「已播放完」,不该谎报给用户")
    }

    @Test fun `跨季查询失败时静默停止,不抛异常`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        coEvery { api.seasons(SERIES, "u1") } throws java.io.IOException("offline")
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertNull(controller.sequenceEnd.value)
    }

    /** CancellationException 必须原样冒泡,不能被"静默降级"吞掉(会让协程取消失效)。 */
    @Test fun `CancellationException 照常向上传播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } throws CancellationException("cancelled")
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val thrown = runCatching { controller.onPlaybackEnded("u1") }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "取消必须原样冒泡,被吞掉会让协程取消失效")
    }

    // ---- 归属 id 缺失时的兜底:条目详情补一次,再拿不到就静默停止 ----

    @Test fun `条目自身没带 SeriesId 时用条目详情补齐后照常连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1", seriesId = null, seasonId = null)), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.itemDetail("1", "u1") } returns epDto("1")
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
    }

    @Test fun `补不齐归属 id 时静默停止`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1", seriesId = null, seasonId = null)), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.itemDetail("1", "u1") } returns
            BaseItemDto(id = "1", name = "E1", type = "Episode")
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertNull(controller.sequenceEnd.value)
    }

    /** 季 id 未知但季号已知:退回季列表按季号定位,不能因此漏掉本季剩下的集。 */
    @Test fun `只有季号没有季 id 时按季号定位本季,照样接本季下一集`() = runTest {
        val queue = PlayQueue().apply {
            setQueue(listOf(ep("1", seasonId = null, seasonNumber = 1)), 0)
        }
        val api = mockk<JellyfinApi>()
        coEvery { api.itemDetail("1", "u1") } returns
            BaseItemDto(id = "1", name = "E1", type = "Episode", seriesId = SERIES)
        coEvery { api.seasons(SERIES, "u1") } returns items(seasonDto(S1, 1), seasonDto(S2, 2))
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
    }

    // ---- 元数据完整性:连播换集之后封面不能变空白(全支线复审 Important 4 的既有保证)----

    @Test fun `连播到的下一集保留封面 tag 与季集号,换集后封面不会变空白`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns ItemsResponseDto(
            items = listOf(
                epDto("1"),
                BaseItemDto(
                    id = "2",
                    name = "E2",
                    type = "Episode",
                    seriesId = SERIES,
                    seasonId = S1,
                    seasonNumber = 1,     // ParentIndexNumber
                    episodeNumber = 2,    // IndexNumber
                    runTimeTicks = 15_000_000_000L,
                    imageTags = mapOf("Primary" to "cover-tag-2"),
                ),
            ),
        )
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("cover-tag-2", result?.imageTag)
        assertEquals(1, result?.seasonNumber)
        assertEquals(2, result?.episodeNumber)
        assertEquals(1_500_000L, result?.runTimeMs, "ticks 只在 :core:network 的 mapper 里换算一次")
    }

    /** 共享 mapper 对不认识的类型返回 null;这类条目必须被跳过,不能毒死整个集列表。 */
    @Test fun `集列表里混进无法识别的类型时跳过该条`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(
            epDto("1"),
            BaseItemDto(id = "x", name = "X", type = "MusicVideo"),
            epDto("2"),
        )
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
    }

    // ---- 既有保证:偏好开关与「播完本集」睡眠定时 ----

    @Test fun `autoPlayNext 关闭时不连播,队列不推进,也不发结束信号`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(false))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
        assertNull(controller.sequenceEnd.value, "用户自己关掉了连播,不该把他弹回首页")
        coVerify(exactly = 0) { api.episodes(any(), any(), any()) }
    }

    @Test fun `武装播完本集后即使 autoPlayNext 开启也不连播,队列不推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertEquals("1", queue.current.value?.id)
        assertNull(controller.sequenceEnd.value)
        coVerify(exactly = 0) { api.episodes(any(), any(), any()) }
    }

    @Test fun `播完本集武装状态是一次性的,消费后下次结束恢复正常连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"), epDto("3"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.onPlaybackEnded("u1")               // 消费掉这次武装
        val secondResult = controller.onPlaybackEnded("u1")

        assertEquals("2", secondResult?.id)
    }

    @Test fun `disarm 后不再拦截连播`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1")), 0) }
        val api = mockk<JellyfinApi>()
        coEvery { api.episodes(SERIES, S1, "u1") } returns items(epDto("1"), epDto("2"))
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        controller.armStopAfterCurrentEpisode()
        controller.disarmStopAfterCurrentEpisode()
        val result = controller.onPlaybackEnded("u1")

        assertEquals("2", result?.id)
    }

    @Test fun `队列是空的时候什么都不做`() = runTest {
        val queue = PlayQueue()
        val api = mockk<JellyfinApi>()
        val controller = AutoPlayNextController(queue, api, flowOf(true))

        val result = controller.onPlaybackEnded("u1")

        assertNull(result)
        assertNull(controller.sequenceEnd.value)
    }
}
