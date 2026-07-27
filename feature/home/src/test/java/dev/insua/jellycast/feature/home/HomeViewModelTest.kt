package dev.insua.jellycast.feature.home

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
    private fun fakeSession(userId: String = "user-1"): JellyfinSession {
        val session = mockk<JellyfinSession>()
        coEvery { session.userId() } returns userId
        return session
    }

    // 取数改走 MediaRepository(缓存优先)。假仓储默认缓存为空 —— 空缓存下的行为与改动前
    // 「直接调 API」完全一致,所以下面这些既有断言原样保留。
    private fun newViewModel(api: JellyfinApi, repository: FakeMediaRepository = FakeMediaRepository()) =
        HomeViewModel(api, fakeSession(), repository)

    // ---- 修正 §1 场景 1:三个分区并发加载互不阻塞 ----
    // 三个接口各自耗时 100ms(虚拟时间)。如果 HomeViewModel 串行 await 它们,总耗时会是 300ms;
    // 如果并发(各自 async 后再 await),总耗时约等于单个请求耗时 100ms。用 currentTime 直接证明。

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
        coEvery { api.items(any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(100)
            ItemsResponseDto(items = listOf(movieDto("recent-1")))
        }

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        assertEquals(100L, currentTime, "三个分区应并发发起,总耗时应约等于单个请求耗时而非三者相加")
        assertEquals(3, viewModel.uiState.value.sections.size)
    }

    // ---- 修正 §1 场景 2:某个分区失败时其余仍正常展示,一个 500 不能让整页空白 ----

    @Test
    fun `某个分区失败时其余仍正常展示`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } throws RuntimeException("500")
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        coEvery { api.items(any(), any(), any(), any(), any(), any()) } returns
            ItemsResponseDto(items = listOf(movieDto("recent-1")))

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertTrue(sections.none { it.kind == HomeSectionKind.RESUME }, "失败的分区不应出现")
        assertTrue(sections.any { it.kind == HomeSectionKind.NEXT_UP }, "下一集分区应正常展示")
        assertTrue(sections.any { it.kind == HomeSectionKind.RECENTLY_ADDED }, "最近添加分区应正常展示")
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ---- 修正 §1 场景 3:分区为空时不显示该分区标题 ----

    @Test
    fun `分区为空时不显示该分区标题`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = emptyList())
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = listOf(episodeDto("next-1")))
        coEvery { api.items(any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto(items = emptyList())

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertEquals(1, sections.size)
        assertEquals(HomeSectionKind.NEXT_UP, sections.single().kind)
    }

    // ---- 库里有 8744 集,「最近添加」不带 limit 会一次性拉全量并渲染 ----

    @Test
    fun `最近添加带加载上限,不拉全量`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } returns ItemsResponseDto(items = emptyList())
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(items = emptyList())
        coEvery {
            api.items(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ItemsResponseDto(items = emptyList())

        val viewModel = newViewModel(api)
        advanceUntilIdle()

        coVerify {
            api.items(
                userId = any(), types = "Episode,Movie", recursive = any(),
                sortBy = "DateCreated", startIndex = any(), limit = 20,
                parentId = any(), searchTerm = any(),
            )
        }
    }

    // ================= 缓存优先(离线可用)=================
    // 用户的真实问题:没开 Tailscale 打开 App 就闪退。下面四条把"打开就有内容 / 断网不崩溃"
    // 钉在 ViewModel 这一层。

    private fun episode(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = id)

    /** 三个接口全部当场抛错,模拟"VPN 没开,一个都连不上"。 */
    private fun offlineApi(): JellyfinApi {
        val api = mockk<JellyfinApi>()
        coEvery { api.resume(any()) } throws IOException("Unable to resolve host")
        coEvery { api.nextUp(any(), any()) } throws IOException("Unable to resolve host")
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IOException("Unable to resolve host")
        return api
    }

    @Test
    fun `有缓存时第一次发射就是缓存,不等网络返回`() = runTest(testDispatcher) {
        val api = mockk<JellyfinApi>()
        // 三个接口都很慢。缓存必须在它们返回之前就已经显示出来。
        coEvery { api.resume(any()) } coAnswers { delay(10_000); ItemsResponseDto(items = listOf(episodeDto("net-1"))) }
        coEvery { api.nextUp(any(), any()) } coAnswers { delay(10_000); ItemsResponseDto() }
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(10_000); ItemsResponseDto()
        }

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
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()

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
        coEvery { api.items(any(), any(), any(), any(), any(), any(), any(), any()) } returns ItemsResponseDto()

        val repository = FakeMediaRepository()
        newViewModel(api, repository)
        advanceUntilIdle()

        assertEquals(
            listOf("resume-1"),
            repository.writes.single { it.first == CacheBuckets.HOME_RESUME }.second.map { it.id },
            "这次的成功结果就是下次断网时用户能看到的内容",
        )
    }
}
