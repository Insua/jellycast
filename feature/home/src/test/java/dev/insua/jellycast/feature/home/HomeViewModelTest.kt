package dev.insua.jellycast.feature.home

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.repository.CacheBuckets
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.dto.UserDataDto
import dev.insua.jellycast.network.session.JellyfinSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    // 三个分区的加载必须共享同一个虚拟时钟调度器,才能用 currentTime 断言"并发"而不是"串行"。
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun episodeDto(id: String) = BaseItemDto(id = id, name = id, type = "Episode")
    private fun movieDto(id: String) = BaseItemDto(id = id, name = id, type = "Movie")
    private fun libraryDto(id: String, name: String) = BaseItemDto(id = id, name = name, type = "CollectionFolder")
    private fun episodeDtoWithUnplayed(id: String, unplayed: Int) = BaseItemDto(
        id = id, name = id, type = "Episode",
        userData = UserDataDto(unplayedItemCount = unplayed),
    )

    private fun fakeSession(userId: String = "user-1"): JellyfinSession {
        val session = mockk<JellyfinSession>()
        coEvery { session.userId() } returns userId
        return session
    }

    // 取数改走 MediaRepository(缓存优先)。假仓储默认缓存为空 —— 空缓存下的行为与改动前
    // 「直接调 API」完全一致,所以下面这些既有断言原样保留。
    private fun newViewModel(api: JellyfinApi, repository: FakeMediaRepository = FakeMediaRepository()) =
        HomeViewModel(api, fakeSession(), repository)

    /** 「我的媒体」不参与某个测试的断言时,给个空库列表,免得第二阶段(按库拉最近添加)节外生枝。 */
    private fun stubNoLibraries(api: JellyfinApi) {
        coEvery { api.userViews(any()) } returns ItemsResponseDto()
    }

    // ---- 修正 §1 场景 1:三个 flat 分区(继续收听/下一集/我的媒体)并发加载互不阻塞 ----
    // 三个接口各自耗时 100ms(虚拟时间)。如果 HomeViewModel 串行 await 它们,总耗时会是 300ms;
    // 如果并发(各自 async 后再 await),总耗时约等于单个请求耗时 100ms。用 currentTime 直接证明。
    // 「我的媒体」这里刻意回空列表——把测量范围限定在第一阶段,不让第二阶段(按库拉最近添加)
    // 混进耗时。第二阶段自己的并发单独有一条测试(见下方"最近添加按库并发加载互不阻塞")。

    @Test
    fun `三个分区并发加载互不阻塞`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } coAnswers {
            delay(100)
            ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        }
        coEvery { api.nextUp(any(), any()) } coAnswers {
            delay(100)
            ItemsResponseDto(items = listOf(episodeDto("next-1")))
        }
        coEvery { api.userViews(any()) } coAnswers {
            delay(100)
            ItemsResponseDto()
        }

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertEquals(100L, currentTime, "三个分区应并发发起,总耗时应约等于单个请求耗时而非三者相加")
        assertEquals(2, viewModel.uiState.value.sections.size, "继续收听 + 下一集,「我的媒体」这次是空的不出现")
    }

    // ---- 修正 §1 场景 2:某个分区失败时其余仍正常展示,一个 500 不能让整页空白 ----

    @Test
    fun `某个分区失败时其余仍正常展示`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } throws RuntimeException("500")
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        coEvery { api.userViews(any()) } returns ItemsResponseDto(items = listOf(libraryDto("lib-1", "电视剧")))
        coEvery {
            api.items(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ItemsResponseDto(items = listOf(movieDto("recent-1")))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertTrue(sections.none { it.kind == HomeSectionKind.RESUME }, "失败的分区不应出现")
        assertTrue(sections.any { it.kind == HomeSectionKind.NEXT_UP }, "下一集分区应正常展示")
        assertTrue(sections.any { it.kind == HomeSectionKind.LIBRARIES }, "我的媒体分区应正常展示")
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ---- 修正 §1 场景 3:分区为空时不显示该分区标题 ----

    @Test
    fun `分区为空时不显示该分区标题`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = emptyList())
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        stubNoLibraries(api)

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertEquals(1, sections.size)
        assertEquals(HomeSectionKind.NEXT_UP, sections.single().kind)
    }

    // ================= Change 1:「我的媒体」库入口(GET /UserViews)=================

    @Test
    fun `我的媒体分区从 UserViews 加载库列表`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val libraries = viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.LIBRARIES }
        assertEquals("我的媒体", libraries.title)
        assertEquals(listOf("lib-1", "lib-2"), libraries.items.map { it.id })
        assertEquals(listOf("电视剧", "电影"), libraries.items.map { it.name })
        assertTrue(libraries.items.all { it.kind == MediaKind.LIBRARY }, "库入口条目应映射为 MediaKind.LIBRARY")
    }

    @Test
    fun `我的媒体请求失败时其余分区不受影响`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } throws RuntimeException("500")

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertTrue(sections.none { it.kind == HomeSectionKind.LIBRARIES })
        assertTrue(sections.any { it.kind == HomeSectionKind.RESUME }, "「我的媒体」挂了不该拖累继续收听")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该进错误态")
    }

    // ================= Change 2:最近添加按库分组 + 未看数角标 =================

    @Test
    fun `最近添加按库分组并携带未看数`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-1", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = listOf(episodeDtoWithUnplayed("e-1", 3)))
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-2", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = listOf(movieDto("m-1")))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val groups = viewModel.uiState.value.recentlyAddedGroups
        assertEquals(listOf("lib-1", "lib-2"), groups.map { it.libraryId }, "分组顺序应与「我的媒体」库顺序一致")
        assertEquals(listOf("电视剧", "电影"), groups.map { it.libraryName })
        assertEquals(3, groups.single { it.libraryId == "lib-1" }.items.single().unplayedItemCount)
        assertNull(
            groups.single { it.libraryId == "lib-2" }.items.single().unplayedItemCount,
            "服务端没给这个字段时不该编造一个数字",
        )
    }

    @Test
    fun `最近添加按库并发加载互不阻塞`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-1", searchTerm = any(),
            )
        } coAnswers { delay(100); ItemsResponseDto(items = listOf(episodeDto("e-1"))) }
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-2", searchTerm = any(),
            )
        } coAnswers { delay(100); ItemsResponseDto(items = listOf(movieDto("m-1"))) }

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertEquals(100L, currentTime, "两个库的「最近添加」应并发发起,不应叠加耗时")
        assertEquals(2, viewModel.uiState.value.recentlyAddedGroups.size)
    }

    @Test
    fun `某个库的最近添加请求失败不影响其他库(无缓存时该库不出现)`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-1", searchTerm = any(),
            )
        } throws IOException("offline")
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-2", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = listOf(movieDto("m-1")))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val groups = viewModel.uiState.value.recentlyAddedGroups
        assertEquals(listOf("lib-2"), groups.map { it.libraryId }, "失败的库不该出现,但不能连累另一个库")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该拿错误页盖住它")
    }

    @Test
    fun `某个库最近添加断网但有缓存时显示缓存并标记整体离线`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-1", searchTerm = any(),
            )
        } throws IOException("offline")
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-2", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = listOf(movieDto("m-1")))

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.recentlyAddedOf("lib-1"), listOf(episode("cached-recent-1")))
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()

        val groups = viewModel.uiState.value.recentlyAddedGroups
        assertEquals(
            listOf("cached-recent-1"),
            groups.single { it.libraryId == "lib-1" }.items.map { it.id },
            "刷新失败绝不能清掉已经显示出来的缓存内容",
        )
        assertTrue(viewModel.uiState.value.isOffline, "有分组没刷新成功,整体就算离线")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该拿错误页盖住它")
    }

    @Test
    fun `某个库最近添加为空时不出现该分组`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(
            items = listOf(libraryDto("lib-1", "电视剧"), libraryDto("lib-2", "电影")),
        )
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-1", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = emptyList())
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = "lib-2", searchTerm = any(),
            )
        } returns ItemsResponseDto(items = listOf(movieDto("m-1")))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val groups = viewModel.uiState.value.recentlyAddedGroups
        assertEquals(listOf("lib-2"), groups.map { it.libraryId }, "确实没内容的库不该显示一个空分组标题")
    }

    // ---- 库里有 8744 集,「最近添加」不带 limit 会一次性拉全量并渲染;现在按库分组,
    //      每个库各自的请求仍必须带上限,并且用 parentId 限定在这一个库内 ----

    @Test
    fun `最近添加按库分组时仍带加载上限并限定库范围`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        coEvery { api.userViews(any()) } returns ItemsResponseDto(items = listOf(libraryDto("lib-1", "电视剧")))
        coEvery {
            api.items(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ItemsResponseDto(items = emptyList())

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        coVerify {
            api.items(
                userId = any(), types = "Episode,Movie", recursive = any(),
                sortBy = "DateCreated", startIndex = any(), limit = 20,
                parentId = "lib-1", searchTerm = any(),
            )
        }
    }

    // ================= 缓存优先(离线可用)=================
    // 用户的真实问题:没开 Tailscale 打开 App 就闪退。下面四条把"打开就有内容 / 断网不崩溃"
    // 钉在 ViewModel 这一层。

    private fun episode(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = id)

    /** 四个接口全部当场抛错,模拟"VPN 没开,一个都连不上"。 */
    private fun offlineApi(): JellyfinApi {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } throws IOException("Unable to resolve host")
        coEvery { api.nextUp(any(), any()) } throws IOException("Unable to resolve host")
        coEvery { api.userViews(any()) } throws IOException("Unable to resolve host")
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IOException("Unable to resolve host")
        return api
    }

    @Test
    fun `有缓存时第一次发射就是缓存,不等网络返回`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        // 接口都很慢。缓存必须在它们返回之前就已经显示出来。
        coEvery { api.resume(any()) } coAnswers { delay(10_000); ItemsResponseDto(items = listOf(episodeDto("net-1"))) }
        coEvery { api.nextUp(any(), any()) } coAnswers { delay(10_000); ItemsResponseDto() }
        coEvery { api.userViews(any()) } coAnswers { delay(10_000); ItemsResponseDto() }

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.HOME_RESUME, listOf(episode("cached-1")))
        val viewModel = newViewModel(api, repository)

        runCurrent() // 只跑到"请求已发出、正挂在 delay 上"

        val resume = viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }
        assertEquals(listOf("cached-1"), resume.items.map { it.id }, "缓存必须在网络返回之前就显示出来")
        assertTrue(viewModel.uiState.value.isLoading, "缓存显示出来了,后台刷新仍在进行")

        advanceUntilIdle()

        val refreshed = viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }
        assertEquals(listOf("net-1"), refreshed.items.map { it.id }, "新数据到达后必须替换缓存,而不是追加")
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isOffline)
    }

    @Test
    fun `断网但有缓存时显示缓存并标记离线,不进错误态`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.HOME_RESUME, listOf(episode("cached-1")))
        repository.seed(CacheBuckets.HOME_NEXT_UP, listOf(episode("cached-2")))

        val viewModel = newViewModel(offlineApi(), repository)
        advanceUntilIdle()

        assertEquals(
            listOf(HomeSectionKind.RESUME, HomeSectionKind.NEXT_UP),
            viewModel.uiState.value.sections.map { it.kind },
            "刷新失败绝不能清掉已经显示出来的缓存内容",
        )
        assertTrue(viewModel.uiState.value.isOffline, "要能提示用户'显示的是上次内容'")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该拿错误页盖住它")
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(repository.writes.isEmpty(), "刷新失败不得写库")
    }

    @Test
    fun `无缓存且断网时进入可重试的错误态而不是崩溃`() = runTest(testDispatcher) {
        val viewModel = newViewModel(offlineApi())

        advanceUntilIdle() // 崩溃的话这一步就会把异常抛出来

        assertTrue(viewModel.uiState.value.sections.isEmpty())
        assertNotNull(viewModel.uiState.value.error, "无缓存 + 连不上 = 可重试的错误页,不是永远转圈的白屏")
        assertFalse(viewModel.uiState.value.isLoading, "必须收尾,不能一直转圈")
    }

    @Test
    fun `一个分区连不上不会让其余分区的缓存进错误态`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } throws IOException("offline")
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        stubNoLibraries(api)

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.HOME_RESUME, listOf(episode("cached-1")))
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertEquals(listOf("cached-1"), sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id })
        assertEquals(listOf("next-1"), sections.single { it.kind == HomeSectionKind.NEXT_UP }.items.map { it.id })
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.isOffline, "有分区没刷新成功,整体就算离线")
    }

    @Test
    fun `刷新成功后新数据被写回缓存`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)

        val repository = FakeMediaRepository()
        newViewModel(api, repository)
        advanceUntilIdle()

        assertEquals(
            listOf("resume-1"),
            repository.writes.single { it.first == CacheBuckets.HOME_RESUME }.second.map { it.id },
            "这次的成功结果就是下次断网时用户能看到的内容",
        )
    }

    // ================= 我的最爱标签(设计文档 §3.6/§3.7)=================

    private fun favoriteDto(id: String) = BaseItemDto(
        id = id, name = id, type = "Series",
        userData = UserDataDto(isFavorite = true),
    )

    /** 只匹配"这一次请求确实带了 isFavorite=true"的调用——普通的 items() 桩(不传 isFavorite,
     *  默认 null)不会命中,不用担心互相覆盖。 */
    private fun stubFavorites(api: JellyfinApi, response: ItemsResponseDto) {
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = any(), searchTerm = any(),
                sortOrder = any(), filters = any(), isFavorite = true, isPlayed = any(),
            )
        } returns response
    }

    @Test
    fun `我的最爱标签展示收藏条目`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto(items = listOf(favoriteDto("fav-1"), favoriteDto("fav-2"))))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("fav-1", "fav-2"), viewModel.uiState.value.favorites.map { it.id })
        assertTrue(viewModel.uiState.value.favorites.all { it.isFavorite })
    }

    @Test
    fun `我的最爱无缓存时刷新失败不影响其余分区也不出现在离线横幅里`() = runTest(testDispatcher) {
        // 无缓存 + 失败:staleWhileRevalidate 一次都不发射(CachePolicy 的既有契约),
        // 「我的最爱」这次干脆没有任何信号,不该被当成"有内容但是旧的"计入离线横幅——
        // 与既有的"某个库的最近添加请求失败不影响其他库(无缓存时该库不出现)"是同一条规则。
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = any(), searchTerm = any(),
                sortOrder = any(), filters = any(), isFavorite = true, isPlayed = any(),
            )
        } throws IOException("offline")

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favorites.isEmpty())
        assertTrue(viewModel.uiState.value.sections.any { it.kind == HomeSectionKind.RESUME }, "我的最爱挂了不该拖累继续收听")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该拿错误页盖住它")
    }

    @Test
    fun `我的最爱有缓存时刷新失败显示缓存并计入整体离线`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        coEvery {
            api.items(
                userId = any(), types = any(), recursive = any(), sortBy = any(),
                startIndex = any(), limit = any(), parentId = any(), searchTerm = any(),
                sortOrder = any(), filters = any(), isFavorite = true, isPlayed = any(),
            )
        } throws IOException("offline")

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.HOME_FAVORITES, listOf(episode("cached-fav-1").copy(isFavorite = true)))
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()

        assertEquals(listOf("cached-fav-1"), viewModel.uiState.value.favorites.map { it.id }, "刷新失败绝不能清掉已经显示出来的缓存内容")
        assertTrue(viewModel.uiState.value.isOffline, "我的最爱有缓存但没刷新成功,整体也算离线")
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `切换标签更新当前选中的tab`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto())

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertEquals(HomeTab.FEED, viewModel.uiState.value.tab)
        viewModel.selectTab(HomeTab.FAVORITES)
        assertEquals(HomeTab.FAVORITES, viewModel.uiState.value.tab)
    }

    @Test
    fun `取消收藏时乐观更新使条目立刻从我的最爱消失_成功后写透缓存`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto(items = listOf(favoriteDto("fav-1"))))
        coEvery { api.removeFavorite("fav-1", "user-1") } returns Unit

        val repository = FakeMediaRepository()
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.favorites.single { it.id == "fav-1" }

        viewModel.toggleFavorite(target)
        assertTrue(viewModel.uiState.value.favorites.none { it.id == "fav-1" }, "取消收藏后要立刻从我的最爱消失,不等网络")

        advanceUntilIdle()
        coVerify { api.removeFavorite("fav-1", "user-1") }
        assertEquals(listOf("fav-1"), repository.patchedItemIds)
        assertNull(viewModel.uiState.value.actionError)
    }

    @Test
    fun `取消收藏失败时条目回到我的最爱并提示错误`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto(items = listOf(favoriteDto("fav-1"))))
        coEvery { api.removeFavorite("fav-1", "user-1") } throws IOException("offline")

        val viewModel = newViewModel(api)
        advanceUntilIdle()
        val target = viewModel.uiState.value.favorites.single { it.id == "fav-1" }

        viewModel.toggleFavorite(target)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.favorites.any { it.id == "fav-1" },
            "失败要回滚——条目重新出现在我的最爱,而不是停在'已消失'的乐观状态",
        )
        assertNotNull(viewModel.uiState.value.actionError)
    }

    // ================= 下拉刷新(设计文档 §2.3)=================
    // 下拉刷新必须走与 load() 完全相同的仓储路径(不是另起一套取数逻辑),但绝不能像
    // load()/retry() 那样先清空内存态再重新填——那种"清空再补"的路径只在错误态(屏幕上本来
    // 就没有内容)才被调用得到,真的用来刷新已经显示的内容会先闪成空白,是这个手势明确禁止的。

    @Test
    fun `下拉刷新走既有仓储路径_调用瞬间不清空已显示内容_成功后替换并关闭指示器`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)

        val repository = FakeMediaRepository()
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()
        assertEquals(
            listOf("resume-1"),
            viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id },
        )
        assertFalse(viewModel.uiState.value.isRefreshing, "还没下拉之前不该显示指示器")

        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-2")))

        viewModel.refresh()
        // refresh() 同步置位指示器、且不触碰 sections——这一断言发生在挂起协程真正跑起来之前,
        // 证明"清空"这一步根本不存在,而不只是"清空后又很快补回来,恰好来不及被观察到"。
        assertTrue(viewModel.uiState.value.isRefreshing, "下拉手势应立即显示刷新指示器")
        assertEquals(
            listOf("resume-1"),
            viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id },
            "调用 refresh() 的一瞬间绝不能清空已经显示的内容",
        )

        advanceUntilIdle()

        assertEquals(
            listOf("resume-2"),
            viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id },
            "后台网络到达后应该替换成新数据",
        )
        assertFalse(viewModel.uiState.value.isRefreshing, "刷新完成后指示器应该消失")
    }

    @Test
    fun `下拉刷新失败时保留已加载内容并标记离线_不进错误态`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)

        val repository = FakeMediaRepository()
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()

        coEvery { api.resume(any()) } throws IOException("offline")

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(
            listOf("resume-1"),
            viewModel.uiState.value.sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id },
            "刷新失败绝不能清空已加载内容",
        )
        assertTrue(viewModel.uiState.value.isOffline, "刷新失败复用既有的离线横幅")
        assertNull(viewModel.uiState.value.error, "还有内容可看就不该进错误态,更不该弹窗")
        assertFalse(viewModel.uiState.value.isRefreshing, "失败也要收起指示器")
    }

    @Test
    fun `下拉刷新时三个分区仍并发刷新`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto()
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto()
        stubNoLibraries(api)
        val viewModel = newViewModel(api)
        advanceUntilIdle()
        val before = currentTime

        coEvery { api.resume(any()) } coAnswers { delay(100); ItemsResponseDto(items = listOf(episodeDto("resume-1"))) }
        coEvery { api.nextUp(any(), any()) } coAnswers { delay(100); ItemsResponseDto(items = listOf(episodeDto("next-1"))) }
        coEvery { api.userViews(any()) } coAnswers { delay(100); ItemsResponseDto() }

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(100L, currentTime - before, "下拉刷新的三个分区也应并发发起,不是首次加载独有的行为")
    }

    @Test
    fun `下拉刷新时某个分区失败不影响其余分区`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("resume-1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        stubNoLibraries(api)
        val viewModel = newViewModel(api)
        advanceUntilIdle()

        coEvery { api.resume(any()) } throws IOException("500")

        viewModel.refresh()
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertTrue(sections.any { it.kind == HomeSectionKind.NEXT_UP }, "一个分区刷新失败不该拖累另一个")
        assertEquals(
            listOf("resume-1"),
            sections.single { it.kind == HomeSectionKind.RESUME }.items.map { it.id },
            "失败的分区保留刷新前的内容,而不是消失",
        )
    }

    // ---- 设计文档 §3/§4/§5:静默定时刷新 ----

    /** 「一切都会成功」的默认 api 桩,静默刷新这组用例不关心具体接口行为,只要有内容可刷。 */
    private fun happyApi(): JellyfinApi {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("ep1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto())
        return api
    }

    private fun createViewModel(repository: FakeMediaRepository) = newViewModel(happyApi(), repository)

    /**
     * 只刷「继续收听」和「下一集」。库列表和「最近添加」变化极慢,而后者是**按库各发一个请求**,
     * 每分钟重拉一遍是纯浪费流量 —— 这条用例把范围钉死。
     */
    @Test fun `静默刷新只请求继续收听和下一集两个 bucket`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.requestedBuckets.clear()

        viewModel.refreshLive()
        advanceUntilIdle()

        assertEquals(
            setOf(CacheBuckets.HOME_RESUME, CacheBuckets.HOME_NEXT_UP),
            repository.requestedBuckets.toSet(),
        )
    }

    /** 静默 = 不转下拉刷新的圈。 */
    @Test fun `静默刷新不置位 isRefreshing`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.refreshLive()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshing, "静默刷新不该驱动下拉刷新指示器")
    }

    /**
     * 静默刷新发生在屏幕上已经有内容的时候。先清空再补回来会让首页闪一下白 ——
     * 这正是 v5 给下拉刷新定下的禁令,静默刷新更不能违反。
     *
     * 「继续收听」和「下一集」这次故意制造出真实的时序差(前者本地有缓存、瞬间可读;后者缓存被
     * evict、只能等一次真正跨越协程调度点的网络请求):如果实现在两个分区各自的取数协程真正跑
     * 起来之前就先把 [HomeViewModel] 内部的整份状态清空,「继续收听」先跑完、状态被重新拼出来的
     * 那一刻,「下一集」还没来得及把自己的旧值放回去——这一刻的 `sections` 就会比 `before` 少一块,
     * 断言才有机会抓到。如果两个 bucket 都读同一个立刻返回的内存缓存,清空和补回会在同一次
     * `runCurrent()` 批次里被谁先谁后的巧合悄悄抹平,断言永远看不到"曾经缺过一块"的中间态。
     */
    @Test fun `静默刷新期间已有内容不被清空`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = listOf(episodeDto("ep1")))
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        stubNoLibraries(api)
        stubFavorites(api, ItemsResponseDto())
        val repository = FakeMediaRepository()
        val viewModel = newViewModel(api, repository)
        advanceUntilIdle()
        val before = viewModel.uiState.value.sections
        assertEquals(
            setOf(HomeSectionKind.RESUME, HomeSectionKind.NEXT_UP),
            before.map { it.kind }.toSet(),
            "前提条件:两个分区这次都得先加载成功,才谈得上'已有内容'",
        )

        // 「下一集」这次没有本地缓存可读,只能等网络,而网络这次刻意调得比「继续收听」慢——
        // 制造出两者真正交错到达的时序。
        repository.evictCache(CacheBuckets.HOME_NEXT_UP)
        coEvery { api.nextUp(any(), any()) } coAnswers { delay(100); ItemsResponseDto(items = listOf(episodeDto("next-1"))) }

        viewModel.refreshLive()
        runCurrent() // 「继续收听」这次没有延迟,会先跑完并重新拼一次 UI 状态;
        // 「下一集」这次卡在网络请求上,还没来得及把自己的值放回去。
        assertEquals(before, viewModel.uiState.value.sections, "静默刷新不得清空已有内容")

        advanceUntilIdle()
    }

    /**
     * 失败不进错误态 —— 屏幕上还有旧内容可看,不该被一整页错误盖住。
     *
     * ## 这条用例实际钉住的是什么
     *
     * 能抓住"确实会跑到"的错误写入:比如不管三七二十一就 `_uiState.update { it.copy(error = ...) }`,
     * 或者照着 [sectionRefreshFailed] 判断"这次静默刷新自己的 bucket 失败了就算错误"——这两种都是
     * 有人会真的这么写的疏忽,实测过(见 task-1-report.md「Finding 2」)确实会让这条用例变红。
     *
     * ## 这条用例钉不住什么,以及为什么
     *
     * 钉不住"把 [load] 收尾那句 `error = if (sectionItems.isEmpty()) OFFLINE_MESSAGE else null`
     * 原样复制进 [HomeViewModel.refreshLive]"这一种具体写法——**不是用例本身设计得不够狠**,是这行
     * 条件在这里天生判不成真:[refreshLive] 只可能在 [HomeViewModel.load] 已经成功过一次之后才有
     * 意义地被调用(这正是它存在的理由——已经有内容在屏幕上,才谈得上"静默"刷新),
     * 而只要 [load] 成功过一次,`sectionItems` 就至少留着「我的媒体」那个 key(哪怕值是空列表),
     * `sectionItems.isEmpty()` 永远评不出 `true`。想让这一行代码真的写错东西,得先有另一处 bug
     * 把 `sectionItems` 清空——那正是"静默刷新期间已有内容不被清空"这条用例的职责,不该也不需要
     * 在这里重复设防。所以这条用例对这一种具体的错误写法没有判别力,是已知的、有意接受的盲区,
     * 不是没测出来。
     */
    @Test fun `静默刷新失败不产生错误态`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.failBucket(CacheBuckets.HOME_RESUME)

        viewModel.refreshLive()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error, "静默刷新失败不该把首页打成错误态")
    }

    /**
     * 设计文档 §5:用户主动发起的刷新优先级更高。同一个 bucket 被两条流程同时写,
     * 只会产生无意义的重复请求。
     */
    @Test fun `有下拉刷新在飞时静默刷新跳过且不取消它`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.requestedBuckets.clear()

        viewModel.refresh()          // 用户下拉,请求在飞
        viewModel.refreshLive()      // 同一时刻的定时器
        advanceUntilIdle()

        // 下拉刷新自己要刷的 bucket 一个都不能少 —— 说明它没被取消。
        assertTrue(
            repository.requestedBuckets.containsAll(
                listOf(CacheBuckets.HOME_RESUME, CacheBuckets.HOME_NEXT_UP, CacheBuckets.HOME_LIBRARIES)
            ),
            "静默刷新不得取消正在进行的下拉刷新",
        )
        // 两个 live bucket 各自只被请求一次 —— 说明静默刷新确实跳过了,没有叠加。
        assertEquals(
            1,
            repository.requestedBuckets.count { it == CacheBuckets.HOME_RESUME },
            "静默刷新应当在下拉刷新在飞时跳过,不重复请求",
        )
    }

    /** 这条是需求本身:在别处听过之后回到首页,位置要变成新的。 */
    @Test fun `静默刷新把服务端的新位置反映到界面`() = runTest(testDispatcher) {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        repository.setResumePositionMs(itemId = "ep1", positionMs = 1_800_000L)
        viewModel.refreshLive()
        advanceUntilIdle()

        val resume = viewModel.uiState.value.sections.first { it.kind == HomeSectionKind.RESUME }
        assertEquals(1_800_000L, resume.items.first { it.id == "ep1" }.resumePositionMs)
    }
}
