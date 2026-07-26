package dev.insua.jellycast.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.PageState
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.session.JellyfinSession
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 每页条目数——库里有 8744 集,首屏绝不能一次性拉全量。 */
private const val PAGE_SIZE = 50

/** 搜索防抖窗口:用户连续输入期间不发请求,停顿这么久之后才真正发出去。 */
private const val SEARCH_DEBOUNCE_MS = 300L

enum class LibraryTab { SERIES, MOVIES }

/** 当前可见的分页列表是哪一个——浏览态下按 Tab 分流,搜索态下统一走 searchResults。 */
private enum class ListTarget { SERIES, MOVIES, SEARCH }

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
    val series: PageState<MediaItem> = PageState(),
    val movies: PageState<MediaItem> = PageState(),
    val query: String = "",
    val searchResults: PageState<MediaItem> = PageState(),
    val detail: SeriesDetailUiState? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    /** 当前该显示哪一页数据:搜索态下永远是 searchResults,否则跟随当前 Tab。 */
    val visible: PageState<MediaItem>
        get() = if (isSearching) searchResults else if (tab == LibraryTab.SERIES) series else movies
}

/**
 * 媒体库浏览:剧集 / 电影两个 Tab,各自独立分页请求(`includeItemTypes=Series` / `Movie`),
 * 互不混入同一个列表——这样电影搜索不会把剧集也翻出来,反之亦然。
 *
 * **分页**:每个列表(剧集 / 电影 / 搜索结果)各自维护一个 [PageState],首屏只拉一页
 * (`PAGE_SIZE` 条),[loadNextPage] 在用户滚动到底部时追加下一页。[PageState.onPageLoaded]
 * 自带幂等保护(响应的 `startIndex` 对不上当前已加载条目数就丢弃),但"连点两次只发一次请求"
 * 这条必须在 [loadNextPage] 里再挡一层——它检查 `isLoading`/`endReached` 后**同步**标记
 * `isLoading = true`,而不是在挂起协程内部才检查,否则两次连续调用会在协程真正跑起来之前
 * 都读到"未在加载"的旧状态,双双发出请求。
 *
 * **搜索**:[onQueryChange] 把新查询词写进 [queryFlow];真正发请求的是 `init` 里那条
 * `debounce + flatMapLatest` 管线——防抖负责按下请求风暴(用户连续敲字符时只有停顿超过
 * [SEARCH_DEBOUNCE_MS] 才发一次请求),`flatMapLatest` 负责取消在途请求(新查询到达时,
 * 上一个还没返回的请求所在协程会被直接取消,慢响应不会在快响应之后覆盖它,乱序问题不存在)。
 *
 * 关键交互(自动连播依赖):[queueFor] 返回的永远是当前选中季**完整**的集列表,而不是从被点的
 * 那一集开始的子列表——[LibraryScreen]/[SeriesDetailScreen] 在 onPlay 回调里必须把它原样传出去
 * (`onPlay(item, allEpisodesInSeason)`),交给 :core:player 的播放队列做自动连播。
 *
 * [api] 是 :app 提供的会话代理(见 `dev.insua.jellycast.network.session.SessionJellyfinApi`),
 * 内部按需解析"当前应该用哪个 endpoint";`userId` 同样是运行时可变的会话状态(修正 §8d)——
 * 不再作为裸 `String` 构造参数注入,改成注入 [JellyfinSession] 并在每次真正发请求前取当前值。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val session: JellyfinSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        loadLibrary()

        // debounce + flatMapLatest:新输入自动取消在途请求,慢响应不会覆盖快响应。
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        flowOf<Result<ItemsResponseDto>?>(null)
                    } else {
                        flow {
                            updatePageState(ListTarget.SEARCH) { it.reset().startLoading() }
                            emit(runCatching { fetchPage(types = "Series,Movie", startIndex = 0, searchTerm = query) })
                        }
                    }
                }
                .collect { result -> applySearchResult(result) }
        }
    }

    /** 首屏加载:剧集 / 电影各自独立并发拉第一页。 */
    fun loadLibrary() {
        listOf(ListTarget.SERIES, ListTarget.MOVIES).forEach { target ->
            updatePageState(target) { PageState<MediaItem>().startLoading() }
            requestPage(target, startIndex = 0, searchTerm = null)
        }
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank()) _uiState.update { it.copy(searchResults = PageState()) }
        queryFlow.value = query
    }

    /**
     * 加载当前可见列表的下一页。`isLoading == true` 或 `endReached == true` 时立即返回,
     * 且状态标记发生在启动协程**之前**(同步)——这是"连点两次只发一次请求"的保证:
     * 第二次调用发生时,第一次调用已经同步把 isLoading 置为 true,能立刻看到并短路。
     */
    fun loadNextPage() {
        val target = currentTarget()
        val current = getPageState(target)
        if (current.isLoading || current.endReached) return
        val startIndex = current.loadedCount
        updatePageState(target) { it.startLoading() }
        requestPage(target, startIndex, searchTermFor(target))
    }

    /** 重试当前可见列表的失败页——本质就是"再要一次下一页",因为失败没有推进 loadedCount。 */
    fun retry() = loadNextPage()

    /** 进入某部剧的详情:加载季列表(按季号排序)并自动选中第一季。 */
    fun openSeries(seriesId: String) {
        _uiState.update { it.copy(detail = SeriesDetailUiState(seriesId = seriesId, isLoading = true)) }
        viewModelScope.launch {
            val userId = session.userId()
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
            val userId = session.userId()
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

    /** 浏览列表(剧集/电影)的分页请求:成功走 onPageLoaded(自带幂等丢弃),失败走 onError,绝不抛错。 */
    private fun requestPage(target: ListTarget, startIndex: Int, searchTerm: String?) {
        viewModelScope.launch {
            val result = runCatching { fetchPage(types = typesFor(target), startIndex = startIndex, searchTerm = searchTerm) }
            result.fold(
                onSuccess = { response ->
                    updatePageState(target) {
                        it.onPageLoaded(response.items.mapNotNull { dto -> dto.toMediaItem() }, startIndex, response.total)
                    }
                },
                onFailure = { error ->
                    updatePageState(target) { it.onError(error.message ?: "加载失败") }
                },
            )
        }
    }

    private fun applySearchResult(result: Result<ItemsResponseDto>?) {
        if (result == null) return // 空查询词:onQueryChange 已经同步清空过 searchResults,这里无需再做什么
        result.fold(
            onSuccess = { response ->
                updatePageState(ListTarget.SEARCH) {
                    it.onPageLoaded(response.items.mapNotNull { dto -> dto.toMediaItem() }, startIndex = 0, total = response.total)
                }
            },
            onFailure = { error ->
                updatePageState(ListTarget.SEARCH) { it.onError(error.message ?: "搜索失败") }
            },
        )
    }

    private suspend fun fetchPage(types: String, startIndex: Int, searchTerm: String? = null): ItemsResponseDto =
        api.items(
            userId = session.userId(),
            types = types,
            sortBy = "SortName",
            startIndex = startIndex,
            limit = PAGE_SIZE,
            searchTerm = searchTerm,
        )

    private fun currentTarget(): ListTarget {
        val state = _uiState.value
        return when {
            state.isSearching -> ListTarget.SEARCH
            state.tab == LibraryTab.SERIES -> ListTarget.SERIES
            else -> ListTarget.MOVIES
        }
    }

    private fun typesFor(target: ListTarget): String = when (target) {
        ListTarget.SERIES -> "Series"
        ListTarget.MOVIES -> "Movie"
        ListTarget.SEARCH -> "Series,Movie"
    }

    private fun searchTermFor(target: ListTarget): String? =
        if (target == ListTarget.SEARCH) _uiState.value.query else null

    private fun getPageState(target: ListTarget): PageState<MediaItem> = when (target) {
        ListTarget.SERIES -> _uiState.value.series
        ListTarget.MOVIES -> _uiState.value.movies
        ListTarget.SEARCH -> _uiState.value.searchResults
    }

    private fun updatePageState(target: ListTarget, transform: (PageState<MediaItem>) -> PageState<MediaItem>) {
        _uiState.update { state ->
            when (target) {
                ListTarget.SERIES -> state.copy(series = transform(state.series))
                ListTarget.MOVIES -> state.copy(movies = transform(state.movies))
                ListTarget.SEARCH -> state.copy(searchResults = transform(state.searchResults))
            }
        }
    }
}
