package dev.insua.jellycast.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.PageState
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.repository.CacheBuckets
import dev.insua.jellycast.network.repository.ItemPage
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.session.JellyfinSession
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

/** 无缓存又连不上服务器时给用户看的话。不带技术细节——用户能做的只有"重试"。 */
private const val OFFLINE_MESSAGE = "无法连接服务器"

enum class LibraryTab { SERIES, MOVIES, COLLECTIONS }

/** 当前可见的分页列表是哪一个——浏览态下按 Tab 分流,搜索态下统一走 searchResults。 */
private enum class ListTarget { SERIES, MOVIES, COLLECTIONS, SEARCH }

/**
 * 浏览排序字段。[apiValue] 对应 `/Items` 的 `sortBy`,取值来自 Jellyfin `ItemSortBy` 枚举,
 * 2026-07-28 用 `jq '.components.schemas.ItemSortBy.enum' docs/jellyfin-openapi.json` 核对过
 * 三个取值的精确大小写(SortName / DateCreated / DatePlayed)——写错大小写会被服务端静默忽略。
 */
enum class LibrarySortBy(internal val apiValue: String) {
    /** 名称(拼音/字母序),对应"按名称"。 */
    NAME("SortName"),
    /** 入库时间,对应"按添加日期"。 */
    DATE_ADDED("DateCreated"),
    /** 最近观看时间,对应"按观看日期"。 */
    DATE_PLAYED("DatePlayed"),
}

/**
 * 排序方向。[wireValue] 对应 `/Items` 的 `sortOrder`,取值来自 Jellyfin `SortOrder` 枚举
 * (jq 核对:Ascending / Descending)。
 *
 * [ASCENDING] 故意映射到 `null`(不发送这个查询参数)而不是字面量 `"Ascending"`——
 * Jellyfin 在未指定 `sortOrder` 时的默认行为就是升序,这里不发送只是省一个查询参数,
 * **不影响服务端实际返回的顺序**。这与既有代码"不发送即代表默认值"的写法一致(见
 * [LibraryFilters.toApiFilters] 对空筛选的处理)。缓存 bucket key 的区分逻辑
 * ([libraryBucketKey])不依赖这个 wire 层面的省略,而是直接比较枚举本身,所以这个优化
 * 不会影响"不同排序选择互不覆盖缓存"这条铁律。
 */
enum class LibrarySortOrder {
    ASCENDING,
    DESCENDING,
    ;

    internal val wireValue: String?
        get() = when (this) {
            ASCENDING -> null
            DESCENDING -> "Descending"
        }
}

/**
 * 浏览筛选:仅未看 / 仅收藏,两者可同时生效。对应 `/Items` 的 `filters` 参数,取值来自
 * Jellyfin `ItemFilter` 枚举,2026-07-28 用
 * `jq '.components.schemas.ItemFilter.enum' docs/jellyfin-openapi.json` 核对过精确拼写:
 * `IsUnplayed` / `IsFavorite`(枚举里还有 IsPlayed/IsResumable 等本次不用)。
 */
data class LibraryFilters(
    val unplayedOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
) {
    /** 都未选中时返回 null——不发送这个参数,而不是空字符串(空字符串的服务端行为未经核实)。 */
    fun toApiFilters(): String? {
        val values = buildList {
            if (unplayedOnly) add("IsUnplayed")
            if (favoritesOnly) add("IsFavorite")
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}

/**
 * 缓存 bucket key 的唯一组装处(feature:library 内部)——排序/筛选选择必须参与这个 key,
 * 否则"剧集按名称升序"和"剧集按添加时间降序"会共用同一个缓存位置,后加载的那个会覆盖前一个,
 * 用户切换排序再切回来时会看到刚才那次选择残留的内容,而不是真正属于当前选择的数据。
 *
 * 默认选择(名称 / 升序 / 无筛选)刻意映射回 [base] 原样,不追加任何后缀——这样"从未特意选过
 * 排序筛选"的既有用户拿到的缓存 key 和改动前完全一致,不会因为这次改动让老缓存全部失效。
 * 只有真正偏离默认值的那些维度才会被编码进 key,且顺序固定(排序字段、排序方向、筛选项),
 * 不因为用户操作顺序不同而产生不同的 key(否则"先选未看再选收藏"和"先选收藏再选未看"会被
 * 误判成两个不同的桶)。
 *
 * 之所以放在 `internal`(而不是 private)——[LibraryViewModelTest] 需要用同一份公式算出
 * "某个排序筛选组合对应的 bucket key"去 seed [FakeMediaRepository],以此证明"切换排序/筛选
 * 读到的是各自独立的缓存,不会互相串桶"。两处如果各写一份拼接逻辑,拼法一旦不一致,
 * 测试就会失去意义(测的是"我以为的公式"而不是"真正在用的公式")。
 */
internal fun libraryBucketKey(
    base: String,
    sortBy: LibrarySortBy,
    sortOrder: LibrarySortOrder,
    filters: LibraryFilters,
): String = buildString {
    append(base)
    if (sortBy != LibrarySortBy.NAME) append(".sort=").append(sortBy.name.lowercase())
    if (sortOrder != LibrarySortOrder.ASCENDING) append(".order=").append(sortOrder.name.lowercase())
    if (filters.unplayedOnly) append(".unplayed")
    if (filters.favoritesOnly) append(".favorite")
}

/** 剧集详情:季列表(按季号排序)+ 当前选中季的完整集列表。[episodes] 就是自动连播的播放队列。 */
data class SeriesDetailUiState(
    val seriesId: String,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    /** 显示的是缓存、且这次刷新没成功。用于"离线,显示的是上次内容"的提示。 */
    val isOffline: Boolean = false,
    /** 既没有缓存又连不上服务器时的可重试错误态。 */
    val error: String? = null,
)

/**
 * 合集(BoxSet)详情:直接是一份条目列表(电影/剧集混排),不像剧集详情那样有"季"这层
 * 结构。合集不是自动连播的队列语境——里面的条目彼此独立,点开电影直接播放,点开剧集导航
 * 到 [SeriesDetailUiState] 那条既有路径,所以这里不需要 `queueFor` 那样的概念。
 */
data class CollectionDetailUiState(
    val collectionId: String,
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val error: String? = null,
)

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.SERIES,
    val series: PageState<MediaItem> = PageState(),
    val movies: PageState<MediaItem> = PageState(),
    /** 合集(BoxSet)列表——第三个浏览 Tab(设计文档 §3.7)。 */
    val collections: PageState<MediaItem> = PageState(),
    /** 当前排序/筛选选择——三个 Tab 共用同一套选择(顶部只有一处排序筛选入口)。 */
    val sortBy: LibrarySortBy = LibrarySortBy.NAME,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.ASCENDING,
    val filters: LibraryFilters = LibraryFilters(),
    val query: String = "",
    val searchResults: PageState<MediaItem> = PageState(),
    val detail: SeriesDetailUiState? = null,
    val collectionDetail: CollectionDetailUiState? = null,
    /** 当前可见的浏览列表显示的是缓存、且这次刷新没成功。 */
    val isOffline: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    /** 当前该显示哪一页数据:搜索态下永远是 searchResults,否则跟随当前 Tab。 */
    val visible: PageState<MediaItem>
        get() = if (isSearching) {
            searchResults
        } else {
            when (tab) {
                LibraryTab.SERIES -> series
                LibraryTab.MOVIES -> movies
                LibraryTab.COLLECTIONS -> collections
            }
        }
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
 * **缓存优先(本次改动)**:浏览列表的**第一页**、季列表、集列表都改走 [MediaRepository]
 * ——先发缓存再后台刷新,断网打开看到的是上次的内容而不是白屏(设计文档 §3.1/§3.2)。
 * 第二页起**不走缓存**:分页的下一页是"接着刚才那批往后要",离线时既没有可用的缓存页、
 * 也没有意义(见 [ItemPage.total] 的 KDoc——总数是服务端此刻的口径,不进缓存)。
 * 第一页的发射是**整体替换**而不是 `onPageLoaded` 追加:同一条流会先后发缓存和新数据两次,
 * 追加语义会把两批内容拼起来变成重复列表。
 *
 * **搜索不缓存**:搜索结果是"这一刻针对这个词的服务端答案",缓存它没有意义(换个词就失效),
 * 而且会把大量一次性结果塞满缓存表。所以 [applySearchResult] 这条路径与改动前完全一致。
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
    private val repository: MediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    /** 每个浏览列表当前那条"第一页"流的协程。重新加载时先取消上一条,免得两条流交替覆盖状态。 */
    private val firstPageJobs = mutableMapOf<ListTarget, Job>()

    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var collectionDetailJob: Job? = null

    /**
     * 浏览列表(剧集/电影)**各自**最近一次的刷新是否失败,用来 OR 出 [LibraryUiState.isOffline]。
     *
     * SERIES 和 MOVIES 两个 Tab 的第一页刷新是各自独立并发的流([loadFirstPage] 对每个
     * target 单独 launch),谁先落地谁先发射。如果 [LibraryUiState.isOffline] 直接被最新一次
     * `cached.refreshFailed` 覆盖(不分是哪个 target 发的),后落地的那个流会把先落地的结论
     * 冲掉——剧集刷新失败、电影刷新成功时,电影的发射（`refreshFailed = false`）如果恰好后到,
     * 离线横幅会消失,即使屏幕上显示的仍是剧集的旧数据。跟 [HomeViewModel] 的
     * `sectionRefreshFailed` 是同一个模式:按 target 分别记录,再 `.any { it }`,
     * 顺序不影响结论。
     */
    private val listRefreshFailed = mutableMapOf<ListTarget, Boolean>()

    /**
     * **当前 searchResults 里那批结果是哪个查询词产生的** —— 搜索分页请求的"身份证"。
     *
     * 搜索第一页走 `flatMapLatest`,换词自动取消;但搜索**第二页**走 [loadNextPage] →
     * [requestPage],直接 launch 在 [viewModelScope] 上,换词不会取消它。而
     * [PageState.onPageLoaded] 是泛型容器,只按 startIndex 做幂等保护,不知道也不该知道
     * "查询词"这回事:旧词第二页(startIndex = 50)迟到时,新词第一页恰好也是 50 条,
     * startIndex 对得上,于是被当成合法下一页追加进去。所以身份校验必须放在这一层。
     *
     * 注意它跟 `_uiState.value.query` 不是一回事:用户敲下新字符到防抖窗口结束之间,
     * query 已经变了、屏幕上显示的却仍是旧词的结果。翻页要的是**结果的**词,不是输入框的词。
     */
    private var displayedSearchTerm: String? = null

    init {
        loadLibrary()

        // debounce + flatMapLatest:新输入自动取消在途请求,慢响应不会覆盖快响应。
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        displayedSearchTerm = null
                        flowOf<Result<ItemsResponseDto>?>(null)
                    } else {
                        flow {
                            // 结果换人的同一时刻改身份:此后迟到的旧词分页都对不上号,会被丢弃。
                            displayedSearchTerm = query
                            updatePageState(ListTarget.SEARCH) { it.reset().startLoading() }
                            emit(runCatching { fetchSearchPage(startIndex = 0, searchTerm = query) })
                        }
                    }
                }
                .collect { result -> applySearchResult(result) }
        }
    }

    /** 首屏加载:剧集 / 电影 / 合集各自独立并发拉第一页(缓存优先)。 */
    fun loadLibrary() {
        listOf(ListTarget.SERIES, ListTarget.MOVIES, ListTarget.COLLECTIONS).forEach { loadFirstPage(it) }
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    /**
     * 排序/筛选选择变更:更新状态并重新拉取两个浏览 Tab 的第一页(缓存优先,同
     * [loadLibrary])。选择不变时不重复触发([reloadBrowseLists] 会取消并重开两条第一页流,
     * 没有实际变化就重开一次没有意义,还会打断正在展示的转圈状态)。
     *
     * 搜索不受影响——[fetchSearchPage] 固定使用默认排序,不读这里的状态(见其 KDoc)。
     */
    fun setSortBy(sortBy: LibrarySortBy) {
        if (_uiState.value.sortBy == sortBy) return
        _uiState.update { it.copy(sortBy = sortBy) }
        reloadBrowseLists()
    }

    fun setSortOrder(sortOrder: LibrarySortOrder) {
        if (_uiState.value.sortOrder == sortOrder) return
        _uiState.update { it.copy(sortOrder = sortOrder) }
        reloadBrowseLists()
    }

    fun setFilters(filters: LibraryFilters) {
        if (_uiState.value.filters == filters) return
        _uiState.update { it.copy(filters = filters) }
        reloadBrowseLists()
    }

    private fun reloadBrowseLists() {
        listOf(ListTarget.SERIES, ListTarget.MOVIES, ListTarget.COLLECTIONS).forEach { loadFirstPage(it) }
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
        val searchTerm = searchTermFor(target)
        // 搜索态但还没有任何一批结果落地(防抖窗口内、首次搜索尚未返回):没有可翻的"下一页",
        // 更不能拿 searchTerm = null 去发请求 —— 那会退化成拉取整个未过滤的库。
        if (target == ListTarget.SEARCH && searchTerm == null) return
        val startIndex = current.loadedCount
        // 浏览列表一条都没加载成功时,"下一页"其实就是第一页 —— 走缓存优先那条路(重试路径),
        // 这样重试成功也会写回缓存,不会出现"重试成功了,下次断网还是白屏"。
        if (target != ListTarget.SEARCH && startIndex == 0) {
            loadFirstPage(target)
            return
        }
        updatePageState(target) { it.startLoading() }
        requestPage(target, startIndex, searchTerm)
    }

    /** 重试当前可见列表的失败页——本质就是"再要一次下一页",因为失败没有推进 loadedCount。 */
    fun retry() = loadNextPage()

    /** 进入某部剧的详情:加载季列表(按季号排序)并自动选中第一季。 */
    fun openSeries(seriesId: String) {
        seasonsJob?.cancel()
        episodesJob?.cancel()
        _uiState.update { it.copy(detail = SeriesDetailUiState(seriesId = seriesId, isLoading = true)) }
        seasonsJob = viewModelScope.launch {
            var delivered = false
            repository.bucket(CacheBuckets.seasonsOf(seriesId)) {
                api.seasons(seriesId, session.userId()).items
                    .mapNotNull { it.toMediaItem() }
                    // 排序放在写回缓存**之前**:缓存表按 position 保存顺序,存的就是排好序的样子,
                    // 离线读回来无需再排一次,也就不会出现"在线是 1、2、3,离线变成 2、1、3"。
                    .sortedBy { it.seasonNumber ?: Int.MAX_VALUE }
            }.collect { cached ->
                delivered = true
                val seasons = cached.data
                val previous = _uiState.value.detail?.selectedSeasonId
                // 刷新后原来选中的季可能已经不在了(服务端删了),这时才重新落到第一季;
                // 否则保持用户的选择不变,不能让一次后台刷新把用户正在看的季跳走。
                val selected = previous?.takeIf { id -> seasons.any { it.id == id } }
                    ?: seasons.firstOrNull()?.id
                _uiState.update { state ->
                    state.copy(
                        detail = state.detail?.copy(
                            seasons = seasons,
                            selectedSeasonId = selected,
                            isOffline = cached.refreshFailed,
                            error = null,
                        ),
                    )
                }
                when {
                    selected == null ->
                        _uiState.update { s -> s.copy(detail = s.detail?.copy(isLoading = false)) }
                    selected != previous -> selectSeason(seriesId, selected)
                }
            }
            if (!delivered) {
                // 既没缓存又连不上:进可重试的错误态,绝不抛异常、绝不崩溃。
                _uiState.update { state ->
                    state.copy(detail = state.detail?.copy(isLoading = false, error = OFFLINE_MESSAGE))
                }
            }
        }
    }

    /** 切换季:重新加载该季的完整集列表(即新的播放队列)。 */
    fun selectSeason(seriesId: String, seasonId: String) {
        episodesJob?.cancel()
        _uiState.update { state ->
            state.copy(detail = state.detail?.copy(selectedSeasonId = seasonId, isLoading = true))
        }
        episodesJob = viewModelScope.launch {
            var delivered = false
            repository.bucket(CacheBuckets.episodesOf(seasonId)) {
                api.episodes(seriesId, seasonId, session.userId()).items.mapNotNull { it.toMediaItem() }
            }.collect { cached ->
                delivered = true
                _uiState.update { state ->
                    val detail = state.detail ?: return@update state
                    state.copy(
                        detail = detail.copy(
                            episodes = cached.data,
                            // 缓存已到、新数据还在路上时仍算"加载中";刷新失败就别再转圈了。
                            isLoading = cached.isStale && !cached.refreshFailed,
                            isOffline = detail.isOffline || cached.refreshFailed,
                            error = null,
                        ),
                    )
                }
            }
            if (!delivered) {
                _uiState.update { state ->
                    state.copy(detail = state.detail?.copy(isLoading = false, error = OFFLINE_MESSAGE))
                }
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

    /** 进入某个合集:缓存优先加载它的直接子条目(电影/剧集混排,不递归)。 */
    fun openCollection(collectionId: String) {
        collectionDetailJob?.cancel()
        _uiState.update { it.copy(collectionDetail = CollectionDetailUiState(collectionId = collectionId, isLoading = true)) }
        collectionDetailJob = viewModelScope.launch {
            var delivered = false
            repository.bucket(CacheBuckets.collectionItemsOf(collectionId)) {
                fetchCollectionItems(collectionId)
            }.collect { cached ->
                delivered = true
                _uiState.update { state ->
                    state.copy(
                        collectionDetail = state.collectionDetail?.copy(
                            items = cached.data,
                            isLoading = cached.isStale && !cached.refreshFailed,
                            isOffline = cached.refreshFailed,
                            error = null,
                        ),
                    )
                }
            }
            if (!delivered) {
                _uiState.update { state ->
                    state.copy(collectionDetail = state.collectionDetail?.copy(isLoading = false, error = OFFLINE_MESSAGE))
                }
            }
        }
    }

    /** 重试当前合集详情的加载——本质就是重新 openCollection 同一个 id。 */
    fun retryCollection() {
        _uiState.value.collectionDetail?.collectionId?.let { openCollection(it) }
    }

    /**
     * 合集内的直接子条目:`parentId` 定位到这个合集,`recursive=false` 只要直接子项
     * (合集本身可能混装电影和整部剧集,不需要也不应该展开到季/集那一层)。
     */
    private suspend fun fetchCollectionItems(collectionId: String): List<MediaItem> =
        api.items(
            userId = session.userId(),
            types = "Movie,Series",
            recursive = false,
            parentId = collectionId,
        ).items.mapNotNull { it.toMediaItem() }

    /**
     * 浏览列表的**第一页**:缓存优先。一条流会先后发"缓存"和"新数据"两次,所以这里是
     * **整体替换**而不是 [PageState.onPageLoaded] 的追加语义(追加会把两批拼成重复列表)。
     *
     * `totalCount` 只在网络那次发射里有值——缓存不存总数(见 [ItemPage]),用 `?:` 保留旧值,
     * 免得一次缓存发射把已知的总数抹成 null 让 `endReached` 退回 false。
     */
    private fun loadFirstPage(target: ListTarget) {
        val bucket = bucketFor(target) ?: return
        firstPageJobs[target]?.cancel()
        updatePageState(target) { PageState<MediaItem>().startLoading() }
        firstPageJobs[target] = viewModelScope.launch {
            var delivered = false
            repository.pagedBucket(bucket) {
                val response = fetchBrowsePage(target, startIndex = 0)
                ItemPage(response.items.mapNotNull { it.toMediaItem() }, response.total)
            }.collect { cached ->
                delivered = true
                updatePageState(target) { state ->
                    state.copy(
                        items = cached.data.items,
                        totalCount = cached.data.total ?: state.totalCount,
                        isLoading = cached.isStale && !cached.refreshFailed,
                        error = null,
                    )
                }
                listRefreshFailed[target] = cached.refreshFailed
                _uiState.update { it.copy(isOffline = listRefreshFailed.values.any { it }) }
            }
            if (!delivered) {
                // 一次都没发射 = 没缓存 + 网络失败。转成可重试的错误态,不崩溃、不空白。
                updatePageState(target) { it.onError(OFFLINE_MESSAGE) }
            }
        }
    }

    /** 浏览列表(剧集/电影)**第二页起**的分页请求:成功走 onPageLoaded(自带幂等丢弃),失败走 onError,绝不抛错。 */
    private fun requestPage(target: ListTarget, startIndex: Int, searchTerm: String?) {
        viewModelScope.launch {
            val result = runCatching {
                if (target == ListTarget.SEARCH) {
                    fetchSearchPage(startIndex, requireNotNull(searchTerm))
                } else {
                    fetchBrowsePage(target, startIndex)
                }
            }
            // 身份校验:响应回来时,屏幕上显示的结果如果已经换成别的查询词(或已退出搜索),
            // 这一页就是"上一场比赛的成绩",直接丢弃 —— 既不追加条目也不覆盖 totalCount。
            // 此时 searchResults 的 isLoading 由新查询那条管线自己负责收尾,不会卡住。
            if (target == ListTarget.SEARCH && searchTerm != displayedSearchTerm) return@launch
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

    /**
     * 浏览列表(剧集/电影)取数——带上用户当前选择的排序字段/方向/筛选(设计文档 §3.7)。
     * `sortOrder`/`filters` 的取值构造见 [LibrarySortOrder.wireValue] / [LibraryFilters.toApiFilters]
     * 的 KDoc:默认选择时不发送这两个参数,行为与改动前完全一致。
     */
    private suspend fun fetchBrowsePage(target: ListTarget, startIndex: Int): ItemsResponseDto {
        val state = _uiState.value
        return api.items(
            userId = session.userId(),
            types = typesFor(target),
            sortBy = state.sortBy.apiValue,
            startIndex = startIndex,
            limit = PAGE_SIZE,
            sortOrder = state.sortOrder.wireValue,
            filters = state.filters.toApiFilters(),
        )
    }

    /**
     * 搜索固定用名称排序、不带任何筛选——搜索关心的是"这个词命中了什么",用户在浏览页选的
     * 排序/筛选是浏览语境下的偏好,套到搜索结果上没有意义,也会让"清空搜索词回到浏览态"和
     * "搜索结果与浏览列表互不影响"这两条既有保证变得含糊。
     */
    private suspend fun fetchSearchPage(startIndex: Int, searchTerm: String): ItemsResponseDto =
        api.items(
            userId = session.userId(),
            types = "Series,Movie",
            sortBy = "SortName",
            startIndex = startIndex,
            limit = PAGE_SIZE,
            searchTerm = searchTerm,
        )

    private fun currentTarget(): ListTarget {
        val state = _uiState.value
        return when {
            state.isSearching -> ListTarget.SEARCH
            else -> when (state.tab) {
                LibraryTab.SERIES -> ListTarget.SERIES
                LibraryTab.MOVIES -> ListTarget.MOVIES
                LibraryTab.COLLECTIONS -> ListTarget.COLLECTIONS
            }
        }
    }

    private fun typesFor(target: ListTarget): String = when (target) {
        ListTarget.SERIES -> "Series"
        ListTarget.MOVIES -> "Movie"
        // BaseItemKind 精确拼写 "BoxSet" 已用 jq 核对(见 MediaItemMapper 里的同一处核对)。
        ListTarget.COLLECTIONS -> "BoxSet"
        ListTarget.SEARCH -> "Series,Movie"
    }

    /**
     * 搜索结果不缓存(见类 KDoc),所以 SEARCH 没有 bucket。
     *
     * 剧集/电影的 bucket key 由 [libraryBucketKey] 拼出当前排序/筛选选择——这是本次改动的
     * 关键点:不同选择必须落到不同的缓存位置,否则会互相覆盖(见 [libraryBucketKey] 的 KDoc)。
     */
    private fun bucketFor(target: ListTarget): String? {
        val base = when (target) {
            ListTarget.SERIES -> CacheBuckets.LIBRARY_SERIES
            ListTarget.MOVIES -> CacheBuckets.LIBRARY_MOVIES
            ListTarget.COLLECTIONS -> CacheBuckets.LIBRARY_COLLECTIONS
            ListTarget.SEARCH -> return null
        }
        val state = _uiState.value
        return libraryBucketKey(base, state.sortBy, state.sortOrder, state.filters)
    }

    /**
     * 翻页要用的查询词。搜索态下取 [displayedSearchTerm](**产生当前这批结果**的词),
     * 而不是 `_uiState.value.query`(输入框里的实时值)—— 防抖窗口内两者不一致,
     * 用后者会把新词的第 2 页接到旧词的列表后面。
     */
    private fun searchTermFor(target: ListTarget): String? =
        if (target == ListTarget.SEARCH) displayedSearchTerm else null

    private fun getPageState(target: ListTarget): PageState<MediaItem> = when (target) {
        ListTarget.SERIES -> _uiState.value.series
        ListTarget.MOVIES -> _uiState.value.movies
        ListTarget.COLLECTIONS -> _uiState.value.collections
        ListTarget.SEARCH -> _uiState.value.searchResults
    }

    private fun updatePageState(target: ListTarget, transform: (PageState<MediaItem>) -> PageState<MediaItem>) {
        _uiState.update { state ->
            when (target) {
                ListTarget.SERIES -> state.copy(series = transform(state.series))
                ListTarget.MOVIES -> state.copy(movies = transform(state.movies))
                ListTarget.COLLECTIONS -> state.copy(collections = transform(state.collections))
                ListTarget.SEARCH -> state.copy(searchResults = transform(state.searchResults))
            }
        }
    }
}
