package dev.insua.jellycast.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.mapper.toMediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { SERIES, MOVIES }

/** 剧集详情:季列表(按季号排序)+ 当前选中季的完整集列表。[episodes] 就是自动连播的播放队列。 */
data class SeriesDetailUiState(
    val seriesId: String,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.SERIES,
    val series: List<MediaItem> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val detail: SeriesDetailUiState? = null,
)

/**
 * 媒体库浏览:剧集 / 电影两个 Tab,各自独立请求(`includeItemTypes=Series` / `Movie`),
 * 互不混入同一个列表——这样电影搜索不会把剧集也翻出来,反之亦然。
 *
 * 关键交互(自动连播依赖):[queueFor] 返回的永远是当前选中季**完整**的集列表,而不是从被点的
 * 那一集开始的子列表——[LibraryScreen]/[SeriesDetailScreen] 在 onPlay 回调里必须把它原样传出去
 * (`onPlay(item, allEpisodesInSeason)`),交给 :core:player 的播放队列做自动连播。
 *
 * [api] / [userId] 目前直接构造注入,尚未接入"当前激活服务器"的会话解析(Task 22 导航装配的
 * 职责);:feature:library 现在还没有被 :app 引用,不影响 Hilt 全图校验。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val userId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        loadLibrary()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val seriesDeferred = async {
                runCatching { api.items(userId, types = "Series").items.mapNotNull { it.toMediaItem() } }
            }
            val moviesDeferred = async {
                runCatching { api.items(userId, types = "Movie").items.mapNotNull { it.toMediaItem() } }
            }

            val series = seriesDeferred.await().getOrDefault(emptyList())
            val movies = moviesDeferred.await().getOrDefault(emptyList())

            _uiState.update { it.copy(isLoading = false, series = series, movies = movies) }
        }
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    /** 进入某部剧的详情:加载季列表(按季号排序)并自动选中第一季。 */
    fun openSeries(seriesId: String) {
        _uiState.update { it.copy(detail = SeriesDetailUiState(seriesId = seriesId, isLoading = true)) }
        viewModelScope.launch {
            val seasons = runCatching {
                api.seasons(seriesId, userId).items.mapNotNull { it.toMediaItem() }
            }.getOrDefault(emptyList()).sortedBy { it.seasonNumber ?: Int.MAX_VALUE }

            val firstSeasonId = seasons.firstOrNull()?.id
            _uiState.update { state ->
                state.copy(detail = state.detail?.copy(seasons = seasons, selectedSeasonId = firstSeasonId))
            }
            if (firstSeasonId != null) selectSeason(seriesId, firstSeasonId)
            else _uiState.update { state -> state.copy(detail = state.detail?.copy(isLoading = false)) }
        }
    }

    /** 切换季:重新加载该季的完整集列表(即新的播放队列)。 */
    fun selectSeason(seriesId: String, seasonId: String) {
        _uiState.update { state ->
            state.copy(detail = state.detail?.copy(selectedSeasonId = seasonId, isLoading = true))
        }
        viewModelScope.launch {
            val episodes = runCatching {
                api.episodes(seriesId, seasonId, userId).items.mapNotNull { it.toMediaItem() }
            }.getOrDefault(emptyList())

            _uiState.update { state ->
                state.copy(detail = state.detail?.copy(episodes = episodes, isLoading = false))
            }
        }
    }

    /**
     * 某一集被点击时应该传给 onPlay 的播放队列——当前选中季的完整集列表。
     * 找不到季数据(理论上不该发生,detail 为 null 或还没加载完)时兜底为只含被点那一集的队列,
     * 保证至少能播放,而不是崩溃或队列为空。
     */
    fun queueFor(episode: MediaItem): List<MediaItem> =
        _uiState.value.detail?.episodes?.takeIf { it.isNotEmpty() } ?: listOf(episode)
}
