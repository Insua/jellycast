package dev.insua.jellycast.feature.library

import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.session.JellyfinSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seriesDto(id: String) = BaseItemDto(id = id, name = id, type = "Series")
    private fun movieDto(id: String) = BaseItemDto(id = id, name = id, type = "Movie")
    private fun seasonDto(id: String, seasonNumber: Int) =
        BaseItemDto(id = id, name = "Season $seasonNumber", type = "Season", episodeNumber = seasonNumber)

    private fun episodeDto(id: String, seasonNumber: Int, episodeNumber: Int) = BaseItemDto(
        id = id, name = "E$episodeNumber", type = "Episode",
        seasonNumber = seasonNumber, episodeNumber = episodeNumber, seriesName = "剧集",
    )

    private fun defaultApi(): JellyfinApi {
        val api = mockk<JellyfinApi>()
        coEvery { api.items(any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()
        return api
    }

    private fun fakeSession(userId: String = "user-1"): JellyfinSession {
        val session = mockk<JellyfinSession>()
        coEvery { session.userId() } returns userId
        return session
    }

    private fun newViewModel(api: JellyfinApi, session: JellyfinSession = fakeSession()) =
        LibraryViewModel(api, session)

    private fun page(vararg names: String) =
        ItemsResponseDto(names.map { seriesDto(it) }, total = 120)

    // ---- 修正 §1 场景 1:点击某集时把整季作为队列传出,自动连播依赖它 ----

    @Test
    fun `点击某集时把整季作为队列传出`() = runTest(testDispatcher) {
        val api = defaultApi()
        coEvery { api.seasons("series-1", "user-1") } returns
            ItemsResponseDto(items = listOf(seasonDto("season-1", 1)))
        val episodes = listOf(
            episodeDto("e1", 1, 1),
            episodeDto("e2", 1, 2),
            episodeDto("e3", 1, 3),
        )
        coEvery { api.episodes("series-1", "season-1", "user-1") } returns ItemsResponseDto(items = episodes)

        val viewModel = LibraryViewModel(api, fakeSession())
        viewModel.openSeries("series-1")
        advanceUntilIdle()

        val loadedEpisodes = viewModel.uiState.value.detail?.episodes
        assertEquals(3, loadedEpisodes?.size)

        // 点击中间那一集:传出的队列必须是整季(3 集),而不是从被点的那集开始的子列表,
        // 也不是只有被点的那一集。
        val tapped = loadedEpisodes!![1]
        val queue = viewModel.queueFor(tapped)
        assertEquals(loadedEpisodes, queue)
        assertEquals(3, queue.size)
    }

    // ---- 修正 §1 场景 2:季列表按季号排序,不依赖接口返回顺序 ----

    @Test
    fun `季列表按季号排序`() = runTest(testDispatcher) {
        val api = defaultApi()
        coEvery { api.seasons("series-1", "user-1") } returns ItemsResponseDto(
            items = listOf(seasonDto("s2", 2), seasonDto("s1", 1), seasonDto("s3", 3)),
        )
        coEvery { api.episodes(any(), any(), any()) } returns ItemsResponseDto()

        val viewModel = LibraryViewModel(api, fakeSession())
        viewModel.openSeries("series-1")
        advanceUntilIdle()

        val seasons = viewModel.uiState.value.detail?.seasons
        assertEquals(listOf(1, 2, 3), seasons?.map { it.seasonNumber })
    }

    // ---- 修正 §1 场景 3:剧集和电影分为两个 Tab,互不混入 ----

    @Test
    fun `剧集和电影分为两个 Tab`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.items("user-1", "Series", any(), any(), any(), any()) } returns
            ItemsResponseDto(items = listOf(seriesDto("series-1")))
        coEvery { api.items("user-1", "Movie", any(), any(), any(), any()) } returns
            ItemsResponseDto(items = listOf(movieDto("movie-1")))

        val viewModel = LibraryViewModel(api, fakeSession())
        advanceUntilIdle()

        assertEquals(listOf("series-1"), viewModel.uiState.value.series.items.map { it.id })
        assertEquals(listOf("movie-1"), viewModel.uiState.value.movies.items.map { it.id })
        assertEquals(LibraryTab.SERIES, viewModel.uiState.value.tab)

        viewModel.selectTab(LibraryTab.MOVIES)
        assertEquals(LibraryTab.MOVIES, viewModel.uiState.value.tab)
    }

    // ---- 分页与搜索(设计文档 §3):首屏只拉一页 ----

    @Test fun `剧集列表首屏只拉一页而不是全量`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.series.items.map { it.name })
        assertEquals(120, vm.uiState.value.series.totalCount)
    }

    @Test fun `loadNextPage 追加下一页且不重复触发`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        coEvery { api.items(any(), "Series", any(), any(), 2, 50, any(), null) } returns page("C", "D")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); vm.loadNextPage()   // 连点两次
        advanceUntilIdle()
        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.series.items.map { it.name })
        coVerify(exactly = 1) { api.items(any(), "Series", any(), any(), 2, 50, any(), null) }
    }

    @Test fun `到底之后不再请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns
            ItemsResponseDto(listOf(seriesDto("A")), total = 1)
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        coVerify(exactly = 0) { api.items(any(), any(), any(), any(), 1, any(), any(), any()) }
    }

    @Test fun `分页失败保留已加载内容并可重试`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        coEvery { api.items(any(), "Series", any(), any(), 2, 50, any(), null) } throws
            java.io.IOException("offline") andThen page("C", "D")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.series.items.map { it.name })
        assertNotNull(vm.uiState.value.series.error)
        vm.retry(); advanceUntilIdle()
        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.series.items.map { it.name })
        assertNull(vm.uiState.value.series.error)
    }

    @Test fun `搜索防抖 快速连续输入只发一次请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)
        vm.onQueryChange("银"); advanceTimeBy(100)
        vm.onQueryChange("银魂"); advanceUntilIdle()
        coVerify(exactly = 0) { api.items(any(), any(), any(), any(), any(), any(), any(), "银") }
        coVerify(exactly = 1) { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") }
    }

    @Test fun `搜索结果同时包含剧集与电影`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)
        vm.onQueryChange("银魂"); advanceUntilIdle()
        assertTrue(vm.uiState.value.isSearching)
        assertEquals(listOf("银魂"), vm.uiState.value.visible.items.map { it.name })
    }

    @Test fun `清空搜索词回到浏览态并保留原 Tab`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Movie", any(), any(), 0, 50, any(), null) } returns page("电影A")
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("剧A")
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "x") } returns page("X")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.selectTab(LibraryTab.MOVIES)
        vm.onQueryChange("x"); advanceUntilIdle()
        vm.onQueryChange(""); advanceUntilIdle()
        assertFalse(vm.uiState.value.isSearching)
        assertEquals(LibraryTab.MOVIES, vm.uiState.value.tab)
        assertEquals(listOf("电影A"), vm.uiState.value.visible.items.map { it.name })
    }

    @Test fun `搜索失败置 error 且不影响浏览列表`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A")
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "q") } throws
            java.io.IOException("offline")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.onQueryChange("q"); advanceUntilIdle()
        assertNotNull(vm.uiState.value.searchResults.error)
        assertEquals(listOf("A"), vm.uiState.value.series.items.map { it.name })
    }

    // ---- 证明 flatMapLatest 真正取消了在途请求,而不仅仅是"最终结果凑巧正确" ----
    // "银" 的请求故意做成慢响应(delay 1000ms 才返回);在它完成前切换到 "银魂"。
    // 断言 1:"银" 的请求确实被发出过(证明不是被 debounce 拦下,而是真正在途被取消)。
    // 断言 2:最终结果只有 "银魂",没有被姗姗来迟的 "银" 响应污染。
    @Test fun `新查询到达时取消仍在进行的旧查询请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") } coAnswers {
            delay(1_000)
            page("银")
        }
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)

        vm.onQueryChange("银")
        advanceTimeBy(400) // 越过 300ms 防抖,"银" 的请求已经真正发出、正挂在 delay(1000) 上
        vm.onQueryChange("银魂")
        advanceUntilIdle()

        coVerify(exactly = 1) { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") }
        assertEquals(listOf("银魂"), vm.uiState.value.searchResults.items.map { it.name })
    }
}
