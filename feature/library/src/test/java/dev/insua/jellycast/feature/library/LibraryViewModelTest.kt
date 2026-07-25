package dev.insua.jellycast.feature.library

import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

        val viewModel = LibraryViewModel(api, "user-1")
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

        val viewModel = LibraryViewModel(api, "user-1")
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

        val viewModel = LibraryViewModel(api, "user-1")
        advanceUntilIdle()

        assertEquals(listOf("series-1"), viewModel.uiState.value.series.map { it.id })
        assertEquals(listOf("movie-1"), viewModel.uiState.value.movies.map { it.id })
        assertEquals(LibraryTab.SERIES, viewModel.uiState.value.tab)

        viewModel.selectTab(LibraryTab.MOVIES)
        assertEquals(LibraryTab.MOVIES, viewModel.uiState.value.tab)
    }
}
