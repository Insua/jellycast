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
}
