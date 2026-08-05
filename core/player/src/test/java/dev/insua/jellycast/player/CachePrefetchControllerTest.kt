package dev.insua.jellycast.player

import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.cache.NetworkTypeMonitor
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.database.CachedAudioEntity
import dev.insua.jellycast.datastore.DEFAULT_CACHE_MAX_BYTES
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SERVER_ID = "server-1"
private const val USER_ID = "u1"
private const val SERIES_ID = "series-A"
private const val SEASON_ID = "season-1"

private fun ep(id: String, episodeNumber: Int) = MediaItem(
    id = id,
    kind = MediaKind.EPISODE,
    name = "E$id",
    seasonNumber = 1,
    episodeNumber = episodeNumber,
    seriesId = SERIES_ID,
    seasonId = SEASON_ID,
)

private fun movie(id: String) = MediaItem(id = id, kind = MediaKind.MOVIE, name = "M$id")

private fun epDto(id: String, episodeNumber: Int) = BaseItemDto(
    id = id,
    name = "E$id",
    type = "Episode",
    seriesId = SERIES_ID,
    seasonId = SEASON_ID,
    seasonNumber = 1,
    episodeNumber = episodeNumber,
)

private fun seasonDto(id: String, number: Int) =
    BaseItemDto(id = id, name = "S$number", type = "Season", seriesId = SERIES_ID, episodeNumber = number)

private fun items(vararg dto: BaseItemDto) = ItemsResponseDto(items = dto.toList())

private fun remoteSource(itemId: String, level: AudioDeliveryLevel = AudioDeliveryLevel.SERVER_AUDIO_ONLY) =
    PlaybackSource(
        itemId = itemId,
        mediaSourceId = "ms-$itemId",
        streamUrl = "https://nas.example.com/Audio/$itemId/universal",
        level = level,
        isHls = false,
        playSessionId = "session-$itemId",
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

/**
 * 覆盖 Task 6 的编排行为(见任务简报,`.superpowers/sdd/2026-08-03-audio-cache/task-6-brief.md`):
 * 仅 WiFi、串行一次一集、驱逐先于预取、已缓存不重复下载、单集失败不影响后续、换集取消旧计划、
 * 孤儿清扫在控制器生命周期内只跑一次。
 *
 * [JellyfinApi] / [AudioCacheStore] / [CachedAudioDao] / [NetworkTypeMonitor] 全用 MockK 造假,
 * [PlaybackSourceProvider] 用 lambda 造假——完全离线可测,不碰真实网络/文件系统/Android。
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class CachePrefetchControllerTest {

    /** 一部剧 3 集,`order` 0..2——足够覆盖"按顺序下载"与"跨集边界"两类断言,不需要更大规模。 */
    private fun JellyfinApi.stubThreeEpisodeSeries() {
        coEvery { seasons(SERIES_ID, USER_ID) } returns items(seasonDto(SEASON_ID, 1))
        coEvery { episodes(SERIES_ID, SEASON_ID, USER_ID) } returns
            items(epDto("e1", 1), epDto("e2", 2), epDto("e3", 3))
    }

    private fun freshCacheStore(): AudioCacheStore = mockk<AudioCacheStore>().also { store ->
        coEvery { store.sweepOrphans(SERVER_ID) } just Runs
        coEvery { store.pathIfComplete(SERVER_ID, any()) } returns null
        coEvery { store.delete(SERVER_ID, any()) } just Runs
        coEvery { store.store(SERVER_ID, any(), any(), any()) } returns true
    }

    private fun freshDao(cached: List<CachedAudioEntity> = emptyList()): CachedAudioDao =
        mockk<CachedAudioDao>().also { dao -> coEvery { dao.findByServer(SERVER_ID) } returns cached }

    private fun cachedEntity(itemId: String) = CachedAudioEntity(
        serverId = SERVER_ID,
        itemId = itemId,
        seriesId = SERIES_ID,
        seasonNumber = 1,
        episodeNumber = 1,
        filePath = "/data/audio-cache/$SERVER_ID/$itemId",
        sizeBytes = 100L,
        completedAt = 0L,
        lastAccessAt = 0L,
    )

    private fun controller(
        api: JellyfinApi,
        cacheStore: AudioCacheStore,
        cacheDao: CachedAudioDao,
        onWifi: Boolean,
        downloadSourceProvider: PlaybackSourceProvider,
        scope: CoroutineScope,
    ): CachePrefetchController {
        val network = mockk<NetworkTypeMonitor>()
        every { network.isOnWifi() } returns onWifi
        return CachePrefetchController(
            cacheDao = cacheDao,
            cacheStore = cacheStore,
            networkTypeMonitor = network,
            downloadSourceProvider = downloadSourceProvider,
            api = api,
            serverId = SERVER_ID,
            userIdProvider = { USER_ID },
            scope = scope,
        )
    }

    // ---- 非 WiFi 时一个下载都不发起 ----

    @Test fun `非 WiFi 时一个下载都不发起`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = false, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        coVerify(exactly = 0) { cacheStore.store(SERVER_ID, any(), any(), any()) }
    }

    /**
     * 复审 Finding 2(Task 6):上一条用例的 DAO 是空的,`cacheStore.delete` 从未被断言过——一个
     * 把 WiFi 判定挪到驱逐循环之前的回归(先判 WiFi、不在 WiFi 就直接 return,跳过驱逐)会让全部
     * 8 条既有用例照常通过,却让"永远用蜂窝网络的用户"的缓存只进不出,一路涨到存储上限、然后
     * 涨过上限也没有任何东西回收——这条用例专门堵住这个洞:非 WiFi 时驱逐必须照常执行,只是不
     * 发起下载。
     */
    @Test fun `非 WiFi 时驱逐依然执行,只是不下载`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        // "stale-episode" 不在这次查到的 seriesOrder 里,任何网络状态下都应该被驱逐。
        val cacheDao = freshDao(listOf(cachedEntity("stale-episode")))
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, cacheDao, onWifi = false, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheStore.delete(SERVER_ID, "stale-episode") }
        coVerify(exactly = 0) { cacheStore.store(SERVER_ID, any(), any(), any()) }
    }

    // ---- WiFi 下按 planCache 给的顺序下载 ----

    @Test fun `WiFi 下按顺序下载还没缓存的条目`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val stored = mutableListOf<String>()
        coEvery { cacheStore.store(SERVER_ID, any(), any(), any()) } coAnswers {
            stored += secondArg<String>()
            true
        }
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        assertEquals(listOf("e1", "e2", "e3"), stored, "必须按 planCache 给出的 order 顺序下载")
    }

    // ---- 同一时刻只有一个下载在跑(保护 NAS 的核心断言) ----

    @Test fun `同一时刻只有一个下载在跑`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        coEvery { cacheStore.store(SERVER_ID, any(), any(), any()) } coAnswers {
            val itemId = secondArg<String>()
            started += itemId
            if (itemId == "e1") gate.await() // 第一集卡在这里,模拟"还没下载完"的窗口
            true
        }
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        assertEquals(
            listOf("e1"),
            started,
            "第一集还卡在下载中间(gate 未释放)时,第二集必须**还没开始**——这是保护 J4125 的关键断言",
        )

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("e1", "e2", "e3"), started, "第一集完成之后,后续几集应该依次跟上")
    }

    // ---- 已缓存的不重复下载 ----

    @Test fun `已缓存的条目不重复下载`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        coEvery { cacheStore.pathIfComplete(SERVER_ID, "e1") } returns "/data/audio-cache/$SERVER_ID/e1"
        val cacheDao = freshDao(listOf(cachedEntity("e1")))
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, cacheDao, onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        coVerify(exactly = 0) { cacheStore.store(SERVER_ID, "e1", any(), any()) }
        coVerify(exactly = 1) { cacheStore.store(SERVER_ID, "e2", any(), any()) }
        coVerify(exactly = 1) { cacheStore.store(SERVER_ID, "e3", any(), any()) }
    }

    // ---- 驱逐必须在预取之前执行 ----

    @Test fun `驱逐在预取之前执行`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val events = mutableListOf<String>()
        coEvery { cacheStore.delete(SERVER_ID, any()) } coAnswers { events += "evict:${secondArg<String>()}" }
        coEvery { cacheStore.store(SERVER_ID, any(), any(), any()) } coAnswers {
            events += "download:${secondArg<String>()}"
            true
        }
        // "stale-episode" 不在这次查到的 seriesOrder 里(锚点之前/别的剧遗留),必须被驱逐。
        val cacheDao = freshDao(listOf(cachedEntity("stale-episode")))
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, cacheDao, onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        assertTrue(events.contains("evict:stale-episode"), "过期条目必须被驱逐,实际事件:$events")
        val firstDownloadIndex = events.indexOfFirst { it.startsWith("download:") }
        val lastEvictIndex = events.indexOfLast { it.startsWith("evict:") }
        assertTrue(
            lastEvictIndex < firstDownloadIndex,
            "所有驱逐必须发生在第一次下载之前,实际顺序:$events",
        )
    }

    // ---- 下载失败不影响后续条目 ----

    @Test fun `单集下载失败不影响后续条目`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        coEvery { cacheStore.store(SERVER_ID, "e1", any(), any()) } throws RuntimeException("下载 e1 失败")
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheStore.store(SERVER_ID, "e2", any(), any()) }
        coVerify(exactly = 1) { cacheStore.store(SERVER_ID, "e3", any(), any()) }
    }

    // ---- 换集时重新计算计划,并取消上一次尚未完成的预取 ----

    @Test fun `换集时取消上一次尚未完成的预取并重新计算新计划`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val gate = CompletableDeferred<Unit>()
        val stored = mutableListOf<String>()
        coEvery { cacheStore.store(SERVER_ID, any(), any(), any()) } coAnswers {
            val itemId = secondArg<String>()
            stored += itemId
            if (itemId == "e1") gate.await()
            true
        }
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()
        assertEquals(listOf("e1"), stored, "e1 的下载应该卡在 gate 上")

        // 用户在 e1 还没缓存完的时候换到了一部电影——旧计划(e1 之后还有 e2/e3)必须被取消。
        sut.onItemChanged(movie("m1"))
        advanceUntilIdle()

        assertTrue(stored.contains("m1"), "新计划(电影)应该正常执行")

        // 放开旧的 gate:如果旧协程没有真的被取消,它会在这里继续走 e2/e3。
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(stored.contains("e2"), "旧计划必须在换集时被取消,不该在 gate 释放后又继续走 e2,实际:$stored")
        assertFalse(stored.contains("e3"), "同上,e3 也不该出现,实际:$stored")
    }

    // ---- 存储上限读取失败时的兜底值必须和 PreferencesStore 的默认值同一份(防悄悄漂移) ----

    /**
     * 复审发现:`DEFAULT_CACHE_MAX_BYTES` 曾经在 `PreferencesStore` / `CachePrefetchController` /
     * `SettingsViewModel` 三处各自声明一份 `1024L * 1024L * 1024L`,现在只在 `:core:datastore`
     * 定义一份、其余两处引用它。这条用例是行为级的防回归网:不直接比较两个常量字面量(那样只要
     * 有人手滑改了其中一处的*字面量*而不是常量引用就测不出来),而是真正驱动一次
     * `maxBytesProvider()` 读取失败的场景,断言 [CachePrefetchController.safeMaxBytes] 的兜底值
     * 落在 [DEFAULT_CACHE_MAX_BYTES] 这个边界上——`e2` 的体积恰好比这个边界多 1 字节,只有兜底值
     * 真的等于 [DEFAULT_CACHE_MAX_BYTES] 时它才会被驱逐;兜底值只要和这个常量不一致(不论改大还是
     * 改小),这条用例就会翻红。
     */
    @Test fun `安全读取上限失败时的兜底值与 PreferencesStore 默认值一致`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val cacheDao = freshDao(listOf(cachedEntity("e2").copy(sizeBytes = DEFAULT_CACHE_MAX_BYTES + 1)))
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val network = mockk<NetworkTypeMonitor>()
        every { network.isOnWifi() } returns true
        val sut = CachePrefetchController(
            cacheDao = cacheDao,
            cacheStore = cacheStore,
            networkTypeMonitor = network,
            downloadSourceProvider = provider,
            api = api,
            serverId = SERVER_ID,
            userIdProvider = { USER_ID },
            scope = this,
            // 模拟 DataStore 读取失败——safeMaxBytes() 必须退回 DEFAULT_CACHE_MAX_BYTES。
            maxBytesProvider = { throw RuntimeException("DataStore 读取失败") },
        )

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheStore.delete(SERVER_ID, "e2") }
    }

    // ---- 孤儿清扫的生产调用方(carry-forward 之一) ----

    @Test fun `孤儿清扫在控制器生命周期内只跑一次`() = runTest {
        val api = mockk<JellyfinApi>().apply { stubThreeEpisodeSeries() }
        val cacheStore = freshCacheStore()
        val provider = PlaybackSourceProvider { itemId, _, _ -> remoteSource(itemId) }
        val sut = controller(api, cacheStore, freshDao(), onWifi = true, downloadSourceProvider = provider, scope = this)

        sut.onItemChanged(ep("e1", 1))
        advanceUntilIdle()
        sut.onItemChanged(ep("e2", 2))
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheStore.sweepOrphans(SERVER_ID) }
    }
}
