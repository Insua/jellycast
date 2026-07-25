package dev.insua.jellycast.feature.home

import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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

        val viewModel = HomeViewModel(api, "user-1")
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

        val viewModel = HomeViewModel(api, "user-1")
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

        val viewModel = HomeViewModel(api, "user-1")
        advanceUntilIdle()

        val sections = viewModel.uiState.value.sections
        assertEquals(1, sections.size)
        assertEquals(HomeSectionKind.NEXT_UP, sections.single().kind)
    }
}
