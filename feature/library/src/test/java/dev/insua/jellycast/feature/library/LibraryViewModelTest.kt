package dev.insua.jellycast.feature.library

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.repository.CacheBuckets
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.session.JellyfinSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

    // 取数改走 MediaRepository(缓存优先)。假仓储默认缓存为空 —— 空缓存下的行为与改动前
    // 「直接调 API」完全一致,所以下面这些既有断言原样保留。
    private fun newViewModel(
        api: JellyfinApi,
        session: JellyfinSession = fakeSession(),
        repository: FakeMediaRepository = FakeMediaRepository(),
    ) = LibraryViewModel(api, session, repository)

    private fun page(vararg names: String) =
        ItemsResponseDto(names.map { seriesDto(it) }, total = 120)

    /**
     * 收藏/已看的乐观更新测试只关心"缓存里已有的这一条",不关心刷新——网络那一趟故意让它失败,
     * 这样第一次发射(缓存)之后不会被第二次发射(网络新数据)覆盖掉,断言时读到的就是种进去的
     * 那一份带着期望初始状态(isFavorite/isPlayed/resumePositionMs)的条目,而不是 [seriesDto]
     * 默认值(全部 false/0)覆盖后的样子。
     */
    private fun defaultApiWithFailingSeriesRefresh(): JellyfinApi {
        val api = defaultApi()
        coEvery {
            api.items(any(), "Series", any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws java.io.IOException("offline")
        return api
    }

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

        val viewModel = newViewModel(api)
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

        val viewModel = newViewModel(api)
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

        val viewModel = newViewModel(api)
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
    // 光靠 coVerify(exactly = 1) + 最终 items == ["银魂"] 不能区分"真的取消"和"没取消但
    // PageState.onPageLoaded 的 startIndex 幂等保护碰巧丢弃了迟到的响应"——两条查询都是
    // startIndex = 0,在 flatMapMerge(不取消)下"银魂"先落地把 items.size 变成 1,之后姗姗
    // 来迟的"银"响应因为 startIndex(0) != items.size(1) 一样会被幂等保护默默丢弃,现象雷同。
    // 所以这里改为直接观察协程本身是否被取消:fake 在 delay 处 catch CancellationException
    // 并置位标记后重新抛出(维持正常取消语义),再用 CompletableDeferred 证明请求确实已经真正
    // 发出(而不是被 debounce 拦在发出之前)。
    @Test fun `新查询到达时取消仍在进行的旧查询请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        var yinWasCancelled = false
        val yinDispatched = CompletableDeferred<Unit>()
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") } coAnswers {
            yinDispatched.complete(Unit)
            try {
                delay(1_000)
                page("银")
            } catch (e: CancellationException) {
                yinWasCancelled = true
                throw e
            }
        }
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)

        vm.onQueryChange("银")
        advanceTimeBy(400) // 越过 300ms 防抖,"银" 的请求已经真正发出、正挂在 delay(1000) 上
        assertTrue(yinDispatched.isCompleted, "旧查询的请求应已真正发出,而不是被 debounce 拦在发出之前")

        vm.onQueryChange("银魂")
        advanceUntilIdle()

        assertTrue(yinWasCancelled, "flatMapLatest 应该取消仍在途的旧查询协程,而不只是让它的结果被后续状态覆盖")
        coVerify(exactly = 1) { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") }
        assertEquals(listOf("银魂"), vm.uiState.value.searchResults.items.map { it.name })
    }

    // ---- 搜索分页的查询词身份:迟到的旧查询第二页不得粘到新查询结果后面 ----
    // 搜索**第一页**走 flatMapLatest,换词会被取消;搜索**第二页**走 loadNextPage →
    // requestPage,直接 launch 在 viewModelScope 上,不会被取消。PageState.onPageLoaded
    // 只按 startIndex 做幂等保护,它是泛型容器、压根不知道"查询词"这回事:旧词第二页
    // (startIndex = 50)迟到时,新词第一页刚好也是 50 条,startIndex 对得上,于是被当成
    // 合法的下一页**追加**进去,totalCount 也被旧词的总数覆盖。用户会看到 50 条毫不相干
    // 的结果凭空接在自己的搜索结果后面。

    @Test fun `迟到的旧查询第二页不会被追加到新查询结果`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") } returns
            ItemsResponseDto((1..50).map { seriesDto("银-$it") }, total = 300)
        // "银" 的第二页故意挂住,直到测试主动放行 —— 模拟慢响应迟到。
        val yinPage2 = CompletableDeferred<Unit>()
        coEvery { api.items(any(), "Series,Movie", any(), any(), 50, 50, any(), "银") } coAnswers {
            yinPage2.await()
            ItemsResponseDto((51..100).map { seriesDto("银-$it") }, total = 300)
        }
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns
            ItemsResponseDto((1..50).map { seriesDto("银魂-$it") }, total = 60)

        val vm = newViewModel(api)
        vm.onQueryChange("银"); advanceUntilIdle()
        assertEquals(50, vm.uiState.value.searchResults.items.size)

        vm.loadNextPage()          // "银" 的第二页发出,挂在慢响应上
        advanceUntilIdle()

        vm.onQueryChange("银魂"); advanceUntilIdle()   // 新查询落地:50 条 "银魂"
        assertEquals(50, vm.uiState.value.searchResults.items.size)

        yinPage2.complete(Unit)    // 旧查询第二页现在才回来
        advanceUntilIdle()

        val names = vm.uiState.value.searchResults.items.map { it.name }
        assertTrue(names.all { it.startsWith("银魂-") }, "新查询结果里混进了旧查询的条目:$names")
        assertEquals(50, names.size, "迟到的旧查询分页不得追加到新查询结果之后")
        assertEquals(60, vm.uiState.value.searchResults.totalCount, "totalCount 不得被旧查询的总数覆盖")
    }

    @Test fun `防抖窗口内翻页请求的是当前展示结果的查询词`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银") } returns
            ItemsResponseDto((1..50).map { seriesDto("银-$it") }, total = 300)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 50, 50, any(), "银") } returns
            ItemsResponseDto((51..100).map { seriesDto("银-$it") }, total = 300)

        val vm = newViewModel(api)
        vm.onQueryChange("银"); advanceUntilIdle()

        // 用户又敲了一个字,但 300ms 防抖还没过 —— 屏幕上显示的仍然是 "银" 的结果。
        vm.onQueryChange("银魂")
        advanceTimeBy(100)
        vm.loadNextPage()
        advanceTimeBy(1)

        coVerify(exactly = 0) { api.items(any(), any(), any(), any(), 50, any(), any(), "银魂") }
        coVerify(exactly = 1) { api.items(any(), "Series,Movie", any(), any(), 50, 50, any(), "银") }
    }

    // ================= 缓存优先(离线可用)=================
    // 用户的真实问题:没开 Tailscale 打开 App 就闪退。下面这些把"打开就有内容 / 断网不崩溃"
    // 钉在 ViewModel 这一层。浏览列表只有**第一页**走缓存,第二页起仍是纯网络(见 VM 的 KDoc)。

    private fun series(id: String) = MediaItem(id = id, kind = MediaKind.SERIES, name = id)
    private fun episode(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = id)

    @Test fun `有缓存时第一页先显示缓存,不等网络`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } coAnswers {
            delay(10_000)
            page("网络A", "网络B")
        }
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("缓存A")))

        val vm = newViewModel(api, repository = repository)
        runCurrent() // 只跑到"请求已发出、正挂在 delay 上"

        assertEquals(listOf("缓存A"), vm.uiState.value.series.items.map { it.name }, "缓存必须先于网络显示")
        assertTrue(vm.uiState.value.series.isLoading, "缓存已显示,后台仍在刷新")

        advanceUntilIdle()

        assertEquals(
            listOf("网络A", "网络B"),
            vm.uiState.value.series.items.map { it.name },
            "新数据必须**替换**第一页,不是追加在缓存后面 —— 追加会变成重复列表",
        )
        assertEquals(120, vm.uiState.value.series.totalCount, "总数只能来自网络那次发射")
        assertFalse(vm.uiState.value.series.isLoading)
    }

    @Test fun `断网但有缓存时显示缓存并标记离线`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Unable to resolve host")
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("缓存A")))

        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()

        assertEquals(listOf("缓存A"), vm.uiState.value.series.items.map { it.name }, "刷新失败不得清空缓存内容")
        assertNull(vm.uiState.value.series.error, "还有内容可看就不该显示错误")
        assertTrue(vm.uiState.value.isOffline)
        assertFalse(vm.uiState.value.series.isLoading, "刷新已失败,不能一直转圈")
        assertTrue(repository.writes.isEmpty(), "刷新失败不得写库")
    }

    // ---- 剧集刷新失败、电影刷新成功时,isOffline 不该被后落地的电影发射冲掉 ----
    // 剧集有缓存所以走"缓存 -> 刷新失败"两次发射;电影的成功响应故意挂在 CompletableDeferred
    // 上,直到剧集那条流已经把 isOffline 置为 true 之后才放行,确保电影的发射**确实**晚于
    // 剧集落地——如果 isOffline 是被最后一次发射直接覆盖(旧实现的问题),这里会看到 false。

    @Test fun `剧集刷新失败电影刷新成功时isOffline保持true_不受着陆顺序影响`() = runTest {
        val moviesGate = CompletableDeferred<Unit>()
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } throws
            java.io.IOException("offline")
        coEvery { api.items(any(), "Movie", any(), any(), 0, 50, any(), null) } coAnswers {
            moviesGate.await()
            page("电影A")
        }
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("缓存A")))

        val vm = newViewModel(api, repository = repository)
        runCurrent() // 剧集没有真正的挂起点(fetch 同步抛异常),这一步应该已经跑完整条剧集流

        assertTrue(vm.uiState.value.isOffline, "剧集的失败发射应该已经落地")

        moviesGate.complete(Unit)
        advanceUntilIdle() // 电影的成功发射现在才落地,且严格晚于剧集的失败发射

        assertTrue(vm.uiState.value.isOffline, "电影刷新成功不该覆盖剧集仍处于离线状态的事实")
        assertEquals(listOf("电影A"), vm.uiState.value.movies.items.map { it.name })
        assertEquals(listOf("缓存A"), vm.uiState.value.series.items.map { it.name }, "剧集显示的仍是缓存内容")
    }

    @Test fun `无缓存且断网时进入可重试的错误态而不是崩溃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Unable to resolve host")

        val vm = newViewModel(api)
        advanceUntilIdle() // 崩溃的话这一步就会把异常抛出来

        assertTrue(vm.uiState.value.series.items.isEmpty())
        assertNotNull(vm.uiState.value.series.error, "无缓存 + 连不上 = 可重试的错误页")
        assertFalse(vm.uiState.value.series.isLoading, "必须收尾,不能一直转圈")
        assertNotNull(vm.uiState.value.movies.error, "电影 Tab 同样收敛到错误态")
    }

    @Test fun `第一页失败后重试成功并写回缓存`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } throws
            java.io.IOException("offline") andThen page("A", "B")
        val repository = FakeMediaRepository()

        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.series.error)

        vm.retry(); advanceUntilIdle()

        assertEquals(listOf("A", "B"), vm.uiState.value.series.items.map { it.name })
        assertNull(vm.uiState.value.series.error)
        assertEquals(
            listOf("A", "B"),
            repository.writes.single { it.first == CacheBuckets.LIBRARY_SERIES }.second.map { it.name },
            "重试成功也要写回缓存,否则'重试成功了但下次断网还是白屏'",
        )
    }

    @Test fun `第一页成功后写回缓存`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        val repository = FakeMediaRepository()

        newViewModel(api, repository = repository); advanceUntilIdle()

        assertEquals(
            listOf("A", "B"),
            repository.writes.single { it.first == CacheBuckets.LIBRARY_SERIES }.second.map { it.name },
        )
    }

    @Test fun `搜索结果不进缓存`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val repository = FakeMediaRepository()

        val vm = newViewModel(api, repository = repository)
        vm.onQueryChange("银魂"); advanceUntilIdle()

        assertEquals(listOf("银魂"), vm.uiState.value.searchResults.items.map { it.name })
        assertTrue(
            repository.writes.none { it.second.any { item -> item.name == "银魂" } },
            "搜索结果是'此刻针对这个词的答案',缓存它没有意义,还会塞满缓存表",
        )
    }

    // ---- 剧集详情:季 / 集列表同样缓存优先,断网不崩溃 ----

    @Test fun `剧集详情有缓存时先显示缓存的季与集`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()
        coEvery { api.seasons(any(), any()) } throws java.io.IOException("offline")
        coEvery { api.episodes(any(), any(), any()) } throws java.io.IOException("offline")

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.seasonsOf("series-1"), listOf(MediaItem("season-1", MediaKind.SEASON, "第一季", seasonNumber = 1)))
        repository.seed(CacheBuckets.episodesOf("season-1"), listOf(episode("e1"), episode("e2"), episode("e3")))

        val vm = newViewModel(api, repository = repository)
        vm.openSeries("series-1"); advanceUntilIdle()

        val detail = vm.uiState.value.detail
        assertEquals(listOf("season-1"), detail?.seasons?.map { it.id })
        assertEquals("season-1", detail?.selectedSeasonId)
        // 离线时整季的集列表照样完整 —— 自动连播的播放队列就是它。
        assertEquals(3, detail?.episodes?.size)
        assertEquals(detail?.episodes, vm.queueFor(detail!!.episodes[1]))
        assertTrue(detail.isOffline)
        assertFalse(detail.isLoading)
    }

    @Test fun `剧集详情无缓存且断网时进错误态而不崩溃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()
        coEvery { api.seasons(any(), any()) } throws java.io.IOException("offline")

        val vm = newViewModel(api)
        vm.openSeries("series-1"); advanceUntilIdle()

        val detail = vm.uiState.value.detail
        assertNotNull(detail)
        assertTrue(detail!!.seasons.isEmpty())
        assertNotNull(detail.error, "无缓存 + 连不上 = 可重试的错误态")
        assertFalse(detail.isLoading, "必须收尾,不能一直转圈")
    }

    // ================= 排序与筛选(设计文档 §3.7)=================
    // Jellyfin 对错误大小写的查询参数静默忽略,以下断言的字面量(SortName/DateCreated/
    // DatePlayed/Descending/IsUnplayed/IsFavorite)均已用 jq 核对 docs/jellyfin-openapi.json
    // 的 ItemSortBy / SortOrder / ItemFilter 三个枚举精确拼写——详见任务报告。

    @Test fun `默认不改排序时请求不显式携带 sortOrder`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null) } returns page("默认")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        assertEquals(listOf("默认"), vm.uiState.value.series.items.map { it.name })
        // 显式验证:sortOrder 位置(第 9 参)确实是 null——默认选择不该在查询串里额外声明升序。
        coVerify { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null, null, null, null, null) }
    }

    @Test fun `选择按添加日期降序排序时请求携带对应参数`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), "DateCreated", 0, 50, any(), null, "Descending", any(), any(), any()) } returns
            page("按添加时间降序")
        val vm = newViewModel(api)
        advanceUntilIdle()

        vm.setSortBy(LibrarySortBy.DATE_ADDED)
        vm.setSortOrder(LibrarySortOrder.DESCENDING)
        advanceUntilIdle()

        assertEquals(listOf("按添加时间降序"), vm.uiState.value.series.items.map { it.name })
    }

    @Test fun `选择按观看日期排序时 sortBy 为 DatePlayed`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), "DatePlayed", 0, 50, any(), null, any(), any(), any(), any()) } returns
            page("按观看时间")
        val vm = newViewModel(api)
        advanceUntilIdle()

        vm.setSortBy(LibrarySortBy.DATE_PLAYED)
        advanceUntilIdle()

        assertEquals(listOf("按观看时间"), vm.uiState.value.series.items.map { it.name })
    }

    @Test fun `选择仅未看筛选时请求携带 filters=IsUnplayed`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null, any(), "IsUnplayed", any(), any()) } returns
            page("未看")
        val vm = newViewModel(api)
        advanceUntilIdle()

        vm.setFilters(LibraryFilters(unplayedOnly = true))
        advanceUntilIdle()

        assertEquals(listOf("未看"), vm.uiState.value.series.items.map { it.name })
    }

    @Test fun `同时选择未看与收藏筛选时filters逗号分隔且顺序固定`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery {
            api.items(any(), "Series", any(), any(), 0, 50, any(), null, any(), "IsUnplayed,IsFavorite", any(), any())
        } returns page("未看且收藏")
        val vm = newViewModel(api)
        advanceUntilIdle()

        vm.setFilters(LibraryFilters(unplayedOnly = true, favoritesOnly = true))
        advanceUntilIdle()

        assertEquals(listOf("未看且收藏"), vm.uiState.value.series.items.map { it.name })
    }

    @Test fun `选择相同排序不重复触发请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null) } returns page("默认")
        val vm = newViewModel(api)
        advanceUntilIdle()
        coVerify(exactly = 1) { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null, null, null, null, null) }

        vm.setSortBy(LibrarySortBy.NAME) // 和当前选择相同,不该重新发请求
        advanceUntilIdle()

        coVerify(exactly = 1) { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null, null, null, null, null) }
    }

    @Test fun `搜索不受浏览排序筛选选择影响`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ItemsResponseDto()
        coEvery { api.items(any(), "Series,Movie", any(), "SortName", 0, 50, any(), "银魂", null, null, any(), any()) } returns
            page("银魂")
        val vm = newViewModel(api)
        advanceUntilIdle()

        vm.setFilters(LibraryFilters(unplayedOnly = true))
        vm.setSortBy(LibrarySortBy.DATE_ADDED)
        advanceUntilIdle()

        vm.onQueryChange("银魂"); advanceUntilIdle()

        assertEquals(listOf("银魂"), vm.uiState.value.searchResults.items.map { it.name })
    }

    // ---- 铁律:排序/筛选选择必须参与缓存 bucket key,否则不同选择会互相覆盖缓存 ----

    @Test fun `切换排序后再切回_读到的是各自排序对应的缓存_不会串桶`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val repository = FakeMediaRepository()
        val ascendingBucket =
            libraryBucketKey(CacheBuckets.LIBRARY_SERIES, LibrarySortBy.NAME, LibrarySortOrder.ASCENDING, LibraryFilters())
        val descendingBucket =
            libraryBucketKey(CacheBuckets.LIBRARY_SERIES, LibrarySortBy.NAME, LibrarySortOrder.DESCENDING, LibraryFilters())
        assertTrue(ascendingBucket != descendingBucket, "两种排序选择必须对应不同的 bucket key")
        repository.seed(ascendingBucket, listOf(series("升序缓存")))
        repository.seed(descendingBucket, listOf(series("降序缓存")))

        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()
        assertEquals(listOf("升序缓存"), vm.uiState.value.series.items.map { it.name }, "默认(升序)应该读到升序自己的缓存")

        vm.setSortOrder(LibrarySortOrder.DESCENDING)
        advanceUntilIdle()
        assertEquals(
            listOf("降序缓存"),
            vm.uiState.value.series.items.map { it.name },
            "切到降序应该读降序自己的缓存,不能继承升序的缓存内容",
        )

        vm.setSortOrder(LibrarySortOrder.ASCENDING)
        advanceUntilIdle()
        assertEquals(
            listOf("升序缓存"),
            vm.uiState.value.series.items.map { it.name },
            "切回升序应该读回升序自己的缓存——证明两个 bucket 全程互不覆盖,而不只是巧合地没冲突",
        )
    }

    @Test fun `筛选选择也参与缓存 bucket_未播放与全部互不覆盖`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val repository = FakeMediaRepository()
        val allBucket =
            libraryBucketKey(CacheBuckets.LIBRARY_SERIES, LibrarySortBy.NAME, LibrarySortOrder.ASCENDING, LibraryFilters())
        val unplayedBucket = libraryBucketKey(
            CacheBuckets.LIBRARY_SERIES, LibrarySortBy.NAME, LibrarySortOrder.ASCENDING,
            LibraryFilters(unplayedOnly = true),
        )
        repository.seed(allBucket, listOf(series("全部缓存")))
        repository.seed(unplayedBucket, listOf(series("未播放缓存")))

        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()
        assertEquals(listOf("全部缓存"), vm.uiState.value.series.items.map { it.name })

        vm.setFilters(LibraryFilters(unplayedOnly = true))
        advanceUntilIdle()
        assertEquals(
            listOf("未播放缓存"),
            vm.uiState.value.series.items.map { it.name },
            "筛选选择必须参与 bucket key,否则会读到未筛选那份缓存,用户会看到明明选了'仅未看'却混进已看内容",
        )
    }

    @Test fun `切换排序会写入新 bucket 而不覆盖旧 bucket 的既有缓存`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), "SortName", 0, 50, any(), null) } returns page("升序网络结果")
        coEvery {
            api.items(any(), "Series", any(), "SortName", 0, 50, any(), null, "Descending", any(), any(), any())
        } returns page("降序网络结果")

        val repository = FakeMediaRepository()
        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()

        vm.setSortOrder(LibrarySortOrder.DESCENDING)
        advanceUntilIdle()

        val ascendingWrite = repository.writes.first { it.second.any { i -> i.name == "升序网络结果" } }
        val descendingWrite = repository.writes.first { it.second.any { i -> i.name == "降序网络结果" } }
        assertTrue(
            ascendingWrite.first != descendingWrite.first,
            "升序和降序的写回必须落在不同的 bucket,实际都写进了 ${ascendingWrite.first}",
        )
    }

    @Test fun `季与集刷新成功后写回各自的 bucket`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()
        coEvery { api.seasons("series-1", "user-1") } returns ItemsResponseDto(items = listOf(seasonDto("season-1", 1)))
        coEvery { api.episodes("series-1", "season-1", "user-1") } returns
            ItemsResponseDto(items = listOf(episodeDto("e1", 1, 1), episodeDto("e2", 1, 2)))
        val repository = FakeMediaRepository()

        val vm = newViewModel(api, repository = repository)
        vm.openSeries("series-1"); advanceUntilIdle()

        assertEquals(
            listOf("season-1"),
            repository.writes.single { it.first == CacheBuckets.seasonsOf("series-1") }.second.map { it.id },
        )
        assertEquals(
            listOf("e1", "e2"),
            repository.writes.single { it.first == CacheBuckets.episodesOf("season-1") }.second.map { it.id },
        )
        assertEquals(2, vm.uiState.value.detail?.episodes?.size)
    }

    // ================= 合集(BoxSet,设计文档 §3.7)=================

    private fun boxSetDto(id: String, name: String = id) = BaseItemDto(id = id, name = name, type = "BoxSet")

    @Test fun `合集 Tab 使用 includeItemTypes=BoxSet 加载列表`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ItemsResponseDto()
        coEvery { api.items(any(), "BoxSet", any(), any(), 0, 50, any(), null, any(), any(), any(), any()) } returns
            ItemsResponseDto(listOf(boxSetDto("box1", "神作合集")), total = 1)

        val vm = newViewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("神作合集"), vm.uiState.value.collections.items.map { it.name })
    }

    @Test fun `进入合集加载其中的条目_不使用 queueFor 概念`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ItemsResponseDto()
        coEvery { api.items(any(), "Movie,Series", false, any(), any(), any(), "box1", any(), any(), any(), any(), any()) } returns
            ItemsResponseDto(listOf(movieDto("m1"), seriesDto("s1")), total = 2)

        val vm = newViewModel(api)
        advanceUntilIdle()
        vm.openCollection("box1")
        advanceUntilIdle()

        assertEquals(listOf("m1", "s1"), vm.uiState.value.collectionDetail?.items?.map { it.id })
        assertFalse(vm.uiState.value.collectionDetail!!.isLoading)
        assertNull(vm.uiState.value.collectionDetail!!.error)
    }

    @Test fun `合集详情有缓存时先显示缓存_断网标记离线`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.collectionItemsOf("box1"), listOf(series("缓存的合集条目")))

        val vm = newViewModel(api, repository = repository)
        vm.openCollection("box1"); advanceUntilIdle()

        val detail = vm.uiState.value.collectionDetail
        assertEquals(listOf("缓存的合集条目"), detail?.items?.map { it.name })
        assertTrue(detail!!.isOffline)
        assertFalse(detail.isLoading)
    }

    @Test fun `合集详情无缓存且断网时进错误态而不崩溃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val vm = newViewModel(api)
        vm.openCollection("box1"); advanceUntilIdle()

        val detail = vm.uiState.value.collectionDetail
        assertNotNull(detail)
        assertTrue(detail!!.items.isEmpty())
        assertNotNull(detail.error, "无缓存 + 连不上 = 可重试的错误态")
        assertFalse(detail.isLoading, "必须收尾,不能一直转圈")
    }

    @Test fun `合集条目刷新成功后写回 bucket`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ItemsResponseDto()
        coEvery { api.items(any(), any(), false, any(), any(), any(), "box1", any(), any(), any(), any(), any()) } returns
            ItemsResponseDto(listOf(movieDto("m1")))
        val repository = FakeMediaRepository()

        val vm = newViewModel(api, repository = repository)
        vm.openCollection("box1"); advanceUntilIdle()

        assertEquals(
            listOf("m1"),
            repository.writes.single { it.first == CacheBuckets.collectionItemsOf("box1") }.second.map { it.id },
        )
    }

    // ---- 合集 Tab 的刷新失败同样计入 isOffline(OR 逻辑覆盖第三个 target,不只是 series/movies) ----
    // 合集 Tab 必须**有缓存**才能走到"缓存已显示、这次刷新失败"这条分支——这与既有的
    // "无缓存 + 断网 = 错误态"是两码事(错误态不经过 collect,不会记入 listRefreshFailed,
    // 也就不会影响 isOffline,这一点在"无缓存且断网时进入可重试的错误态而不是崩溃"里已经
    // 覆盖过 series/movies 两个 target 的同等行为)。

    @Test fun `合集 Tab 有缓存但刷新失败时也会让 isOffline 变为 true`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null, any(), any(), any(), any()) } returns
            page("剧集A")
        coEvery { api.items(any(), "Movie", any(), any(), 0, 50, any(), null, any(), any(), any(), any()) } returns
            ItemsResponseDto(listOf(movieDto("m1")), total = 1)
        coEvery { api.items(any(), "BoxSet", any(), any(), 0, 50, any(), null, any(), any(), any(), any()) } throws
            java.io.IOException("offline")

        val repository = FakeMediaRepository()
        val collectionsBucket =
            libraryBucketKey(CacheBuckets.LIBRARY_COLLECTIONS, LibrarySortBy.NAME, LibrarySortOrder.ASCENDING, LibraryFilters())
        repository.seed(collectionsBucket, listOf(series("缓存合集")))

        val vm = newViewModel(api, repository = repository)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isOffline, "合集 Tab 有缓存但刷新失败,也应该体现在整体离线横幅上")
        assertEquals(listOf("剧集A"), vm.uiState.value.series.items.map { it.name }, "剧集 Tab 本身仍正常显示")
    }

    // ================= 收藏 / 已看:乐观更新 + 失败回滚(设计文档 §3.7)=================

    @Test
    fun `收藏成功时立即乐观更新_网络返回后写透缓存`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.addFavorite("series-1", "user-1") } returns Unit
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1")))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }
        assertFalse(target.isFavorite)

        viewModel.toggleFavorite(target)
        // 乐观更新是同步的 state 变更,不需要等协程跑完就该立刻看见。
        assertTrue(
            viewModel.uiState.value.series.items.single { it.id == "series-1" }.isFavorite,
            "点击后必须立刻反映在 UI 上,不等网络返回",
        )

        advanceUntilIdle()
        coVerify { api.addFavorite("series-1", "user-1") }
        assertTrue(viewModel.uiState.value.series.items.single { it.id == "series-1" }.isFavorite)
        assertEquals(listOf("series-1"), repository.patchedItemIds, "成功要写透缓存,离线时才不会看到收藏状态弹回去")
        assertNull(viewModel.uiState.value.actionError)
    }

    @Test
    fun `收藏失败时回滚乐观更新并提示错误_不写缓存`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.addFavorite("series-1", "user-1") } throws java.io.IOException("offline")
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1")))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }

        viewModel.toggleFavorite(target)
        advanceUntilIdle()

        assertFalse(
            viewModel.uiState.value.series.items.single { it.id == "series-1" }.isFavorite,
            "失败必须回滚——停在乐观更新的状态,下次刷新又变回去,比现在直接报错更让人困惑",
        )
        assertNotNull(viewModel.uiState.value.actionError, "失败要可见地告诉用户,不能静默复原")
        assertTrue(repository.patchedItemIds.isEmpty(), "失败不该写透缓存")
    }

    @Test
    fun `取消收藏调用DELETE接口`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.removeFavorite("series-1", "user-1") } returns Unit
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1").copy(isFavorite = true)))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }

        viewModel.toggleFavorite(target)
        advanceUntilIdle()

        coVerify { api.removeFavorite("series-1", "user-1") }
        assertFalse(viewModel.uiState.value.series.items.single { it.id == "series-1" }.isFavorite)
    }

    @Test
    fun `标记已看成功时清零听过进度并写透缓存`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.markPlayed("series-1", "user-1") } returns Unit
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1").copy(resumePositionMs = 60_000)))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }

        viewModel.togglePlayed(target)
        val optimistic = viewModel.uiState.value.series.items.single { it.id == "series-1" }
        assertTrue(optimistic.isPlayed)
        assertEquals(0L, optimistic.resumePositionMs, "标为已看后不该继续显示一条听了一半的进度条,UI 不能自相矛盾")

        advanceUntilIdle()
        coVerify { api.markPlayed("series-1", "user-1") }
        assertEquals(listOf("series-1"), repository.patchedItemIds)
    }

    @Test
    fun `标记已看失败时把已看状态与听过进度一起回滚`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.markPlayed("series-1", "user-1") } throws java.io.IOException("offline")
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1").copy(resumePositionMs = 60_000)))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }

        viewModel.togglePlayed(target)
        advanceUntilIdle()

        val rolledBack = viewModel.uiState.value.series.items.single { it.id == "series-1" }
        assertFalse(rolledBack.isPlayed)
        assertEquals(60_000L, rolledBack.resumePositionMs, "回滚要把进度也还原,不能只回滚 isPlayed 留下自相矛盾的状态")
        assertNotNull(viewModel.uiState.value.actionError)
    }

    @Test
    fun `取消已看调用DELETE接口`() = runTest(testDispatcher) {
        val api = defaultApiWithFailingSeriesRefresh()
        coEvery { api.markUnplayed("series-1", "user-1") } returns Unit
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.LIBRARY_SERIES, listOf(series("series-1").copy(isPlayed = true)))

        val viewModel = newViewModel(api, repository = repository)
        advanceUntilIdle()
        val target = viewModel.uiState.value.series.items.single { it.id == "series-1" }

        viewModel.togglePlayed(target)
        advanceUntilIdle()

        coVerify { api.markUnplayed("series-1", "user-1") }
        assertFalse(viewModel.uiState.value.series.items.single { it.id == "series-1" }.isPlayed)
    }

    // ================= 单个库浏览(首页「我的媒体」卡片,修正 §3.1)=================
    // jq -r '.paths["/Items"].get.parameters[]?.name' docs/jellyfin-openapi.json | grep -ix parentId
    // 命中且大小写完全一致(parentId)——见任务报告的 jq 证据。

    @Test fun `打开某个库时用 parentId 限定该库并只拉第一页`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 0, 50, "lib-1", any(), any(), any(), any(), any())
        } returns page("剧A", "电影B")
        val vm = newViewModel(api)

        vm.openLibrary("lib-1")
        advanceUntilIdle()

        assertEquals("lib-1", vm.uiState.value.libraryView?.libraryId)
        assertEquals(listOf("剧A", "电影B"), vm.uiState.value.libraryView?.page?.items?.map { it.name })
        assertEquals(120, vm.uiState.value.libraryView?.page?.totalCount)
        coVerify { api.items(any(), "Series,Movie", any(), any(), 0, 50, "lib-1", any(), any(), any(), any(), any()) }
    }

    @Test fun `不同库各自独立缓存_不会互相覆盖`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.libraryViewOf("lib-1"), listOf(series("库1缓存")))
        repository.seed(CacheBuckets.libraryViewOf("lib-2"), listOf(series("库2缓存")))

        val vm = newViewModel(api, repository = repository)

        vm.openLibrary("lib-1")
        advanceUntilIdle()
        assertEquals(listOf("库1缓存"), vm.uiState.value.libraryView?.page?.items?.map { it.name })

        vm.openLibrary("lib-2")
        advanceUntilIdle()
        assertEquals(
            listOf("库2缓存"),
            vm.uiState.value.libraryView?.page?.items?.map { it.name },
            "换一个库不该看到上一个库缓存下来的内容——bucket key 必须按 libraryId 区分",
        )
    }

    @Test fun `库浏览页 loadLibraryViewNextPage 追加下一页且不重复触发`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 0, 50, "lib-1", any(), any(), any(), any(), any())
        } returns page("A", "B")
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 2, 50, "lib-1", any(), any(), any(), any(), any())
        } returns page("C", "D")
        val vm = newViewModel(api)

        vm.openLibrary("lib-1"); advanceUntilIdle()
        vm.loadLibraryViewNextPage(); vm.loadLibraryViewNextPage() // 连点两次
        advanceUntilIdle()

        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.libraryView?.page?.items?.map { it.name })
        coVerify(exactly = 1) {
            api.items(any(), "Series,Movie", any(), any(), 2, 50, "lib-1", any(), any(), any(), any(), any())
        }
    }

    @Test fun `库浏览页到底之后不再请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 0, 50, "lib-1", any(), any(), any(), any(), any())
        } returns ItemsResponseDto(listOf(seriesDto("A")), total = 1)
        val vm = newViewModel(api)

        vm.openLibrary("lib-1"); advanceUntilIdle()
        vm.loadLibraryViewNextPage(); advanceUntilIdle()

        coVerify(exactly = 0) {
            api.items(any(), any(), any(), any(), 1, any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `库浏览页分页失败保留已加载内容并可重试`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 0, 50, "lib-1", any(), any(), any(), any(), any())
        } returns page("A", "B")
        coEvery {
            api.items(any(), "Series,Movie", any(), any(), 2, 50, "lib-1", any(), any(), any(), any(), any())
        } throws java.io.IOException("offline") andThen page("C", "D")
        val vm = newViewModel(api)

        vm.openLibrary("lib-1"); advanceUntilIdle()
        vm.loadLibraryViewNextPage(); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.libraryView?.page?.items?.map { it.name })
        assertNotNull(vm.uiState.value.libraryView?.page?.error)

        vm.retryLibraryView(); advanceUntilIdle()
        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.libraryView?.page?.items?.map { it.name })
        assertNull(vm.uiState.value.libraryView?.page?.error)
    }

    @Test fun `库浏览页无缓存且断网时进入可重试错误态而不崩溃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")
        val vm = newViewModel(api)

        vm.openLibrary("lib-1"); advanceUntilIdle()

        assertTrue(vm.uiState.value.libraryView?.page?.items.isNullOrEmpty())
        assertNotNull(vm.uiState.value.libraryView?.page?.error, "无缓存 + 连不上 = 可重试的错误态")
        assertFalse(vm.uiState.value.libraryView?.page?.isLoading ?: true, "必须收尾,不能一直转圈")
    }

    @Test fun `库浏览页断网但有缓存时显示缓存并标记该屏离线`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("offline")
        val repository = FakeMediaRepository()
        repository.seed(CacheBuckets.libraryViewOf("lib-1"), listOf(series("缓存A")))

        val vm = newViewModel(api, repository = repository)
        vm.openLibrary("lib-1"); advanceUntilIdle()

        assertEquals(listOf("缓存A"), vm.uiState.value.libraryView?.page?.items?.map { it.name })
        assertTrue(vm.uiState.value.libraryView?.isOffline == true)
        assertNull(vm.uiState.value.libraryView?.page?.error, "还有内容可看就不该显示错误")
    }

    @Test
    fun `收藏切换同步更新剧集详情里的同一集`() = runTest(testDispatcher) {
        val api = defaultApi()
        coEvery { api.seasons("series-1", "user-1") } returns
            ItemsResponseDto(items = listOf(seasonDto("season-1", 1)))
        coEvery { api.episodes("series-1", "season-1", "user-1") } returns
            ItemsResponseDto(items = listOf(episodeDto("e1", 1, 1)))
        coEvery { api.addFavorite("e1", "user-1") } returns Unit

        val viewModel = newViewModel(api)
        viewModel.openSeries("series-1")
        advanceUntilIdle()

        val episode = viewModel.uiState.value.detail!!.episodes.single { it.id == "e1" }
        viewModel.toggleFavorite(episode)

        assertTrue(
            viewModel.uiState.value.detail!!.episodes.single { it.id == "e1" }.isFavorite,
            "剧集详情页里的这一集也要立刻反映乐观更新,不能只改浏览列表",
        )
    }
}
