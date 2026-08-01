package dev.insua.jellycast.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.Cached
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.repository.CacheBuckets
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.session.JellyfinSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 无缓存又连不上服务器时给用户看的话。不带技术细节——用户能做的只有"重试"。 */
internal const val OFFLINE_MESSAGE = "无法连接服务器"

/** 每个库的"最近添加"分组各自最多拉这么多条,呼应设计文档 §3.6(库有 8744 集不能拉全量)。 */
private const val RECENTLY_ADDED_LIMIT = 20

/** 「我的最爱」标签页最多拉这么多条——收藏列表通常远小于整个库,但仍要有一个上限,
 *  不能对着 8744 集的库无限拉全量(同 [RECENTLY_ADDED_LIMIT] 的取舍)。 */
private const val FAVORITES_LIMIT = 200

/**
 * 参与静默刷新的分区(设计文档 §2)。刻意**不含** [HomeSectionKind.LIBRARIES] ——
 * 库列表几乎不变,而它还会带出"最近添加"的按库 N 个请求。
 */
private val LIVE_REFRESH_SECTIONS = listOf(HomeSectionKind.RESUME, HomeSectionKind.NEXT_UP)

enum class HomeSectionKind { RESUME, NEXT_UP, LIBRARIES }

/**
 * 首页顶部的两个标签(设计文档 §3.6:"顶部栏 + 首页/我的最爱标签",对齐 Jellyfin Web mobile)。
 * [FEED] 是既有的"继续收听/下一集/我的媒体/最近添加"信息流,[FAVORITES] 是收藏列表——
 * 两者的数据都在 [load] 里并发取好,切标签只是本地状态切换,不触发新请求。
 */
enum class HomeTab { FEED, FAVORITES }

private val HomeSectionKind.bucket: String
    get() = when (this) {
        HomeSectionKind.RESUME -> CacheBuckets.HOME_RESUME
        HomeSectionKind.NEXT_UP -> CacheBuckets.HOME_NEXT_UP
        HomeSectionKind.LIBRARIES -> CacheBuckets.HOME_LIBRARIES
    }

private val HomeSectionKind.title: String
    get() = when (this) {
        HomeSectionKind.RESUME -> "继续收听"
        HomeSectionKind.NEXT_UP -> "下一集"
        HomeSectionKind.LIBRARIES -> "我的媒体"
    }

data class HomeSection(
    val kind: HomeSectionKind,
    val title: String,
    val items: List<MediaItem>,
)

/**
 * "最近添加"里的一个库分组(参照 Jellyfin Web mobile:"最近添加的 电视剧" / "最近添加的 电影"……)。
 * [libraryId] 用于点击 ">" 或标题跳进该库([HomeSectionKind.LIBRARIES] 里同一条目的 id)。
 */
data class RecentlyAddedGroup(
    val libraryId: String,
    val libraryName: String,
    val items: List<MediaItem>,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
    val recentlyAddedGroups: List<RecentlyAddedGroup> = emptyList(),
    /** 至少有一个分区/分组这次没刷新成功,屏幕上显示的是上次的内容。用于顶部那条"离线"提示。 */
    val isOffline: Boolean = false,
    /** 「我的媒体」以外的分区**一个都没拿到数据**(没缓存 + 全部请求失败)时的可重试错误态。 */
    val error: String? = null,
    /** 当前选中的顶部标签(设计文档 §3.6)。 */
    val tab: HomeTab = HomeTab.FEED,
    /** 「我的最爱」标签页的内容——已经按 [MediaItem.isFavorite] 过滤过,取消收藏的乐观更新
     *  会让条目立刻从这里消失,不需要等下一次刷新(见 [HomeViewModel.toggleFavorite])。 */
    val favorites: List<MediaItem> = emptyList(),
    /** 收藏乐观更新失败时的一次性提示,语义与 [dev.insua.jellycast.feature.library.LibraryUiState.actionError] 一致。 */
    val actionError: String? = null,
    /**
     * 下拉刷新手势(设计文档 §2.3)是否正在进行,驱动 `PullToRefreshBox` 的指示器。与 [isLoading]
     * (冷启动/错误态重试)是两回事——只由用户主动下拉触发,刷新期间屏幕上已有的内容原样保留。
     */
    val isRefreshing: Boolean = false,
)

/**
 * "在听"首页。自上而下:继续收听 / 下一集 / 我的媒体 / 最近添加(按库分组)。设计文档的成功
 * 标准之一是"打开 App 到开始播放'下一集'不超过 3 次点击"——下一集分区是追剧主入口,必须显眼、
 * 可直接点播(由 [HomeScreen] 负责布局上的强调,这里只负责把数据摆出来)。
 *
 * ## 并发与独立失败(既有契约,扩到四个入口点不变)
 *
 * [HomeSectionKind] 三个分区(继续收听/下一集/我的媒体)各自独立取数、各自独立失败,并发发起
 * 而非顺序 await:一个接口的 500 绝不能把整页拖空白。空分区不出现在 [HomeUiState.sections] 里。
 *
 * ## 最近添加:按库分组(设计文档 §3.6)
 *
 * 不再是"首页一条拉 20 条混排"的扁平列表——参照 Jellyfin Web mobile,按库分组、每组标题
 * "最近添加的 {库名}"。库列表本身来自 [HomeSectionKind.LIBRARIES] 那次请求(缓存或网络任一
 * 就绪即可),库与库之间的"最近添加"取数**互相并发**、互相独立失败,与三个 flat 分区的规则
 * 完全一致——只是必须先知道有哪些库(第一阶段),才能决定要对哪些库发第二阶段的请求。
 * 这不是"退化成串行加载":同一阶段内的多个请求仍然是并发的,只是分了两个阶段。
 *
 * ## 缓存优先
 *
 * 取数不再是"调 API → 显示",而是走 [MediaRepository]:**先发缓存,再后台刷新**。
 * 用户没开 VPN 打开 App 时,看到的是上次的内容而不是白屏或崩溃(设计文档 §3.2)。
 *
 * [error] 只在**三个 flat 分区一个都没拿到数据**时置位(既没有缓存、网络也全挂了)。只要有任何
 * 一个分区有内容可显示,就不该拿一整页错误盖住它。
 *
 * [api] 是 :app 提供的会话代理(见 `dev.insua.jellycast.network.session.SessionJellyfinApi`),
 * 内部按需解析"当前应该用哪个 endpoint";`userId` 同样是运行时可变的会话状态,注入
 * [JellyfinSession] 并在每次真正发请求前用 [JellyfinSession.userId] 取当前值。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val session: JellyfinSession,
    private val repository: MediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * 各分区最近一次拿到的数据。**"键不存在"和"值是空列表"含义不同**:前者是"这个分区至今
     * 一个字节都没拿到"(没缓存且请求失败),后者是"服务端确实说没有"。[load] 收尾时正是靠
     * 这个区分来决定要不要进错误态——把两者混为一谈,一次服务端抖动就会显示成"连不上服务器"。
     */
    private val sectionItems = mutableMapOf<HomeSectionKind, List<MediaItem>>()
    private val sectionRefreshFailed = mutableMapOf<HomeSectionKind, Boolean>()

    /** 按库 id 索引的"最近添加"分组数据/失败标记,语义与 [sectionItems]/[sectionRefreshFailed] 一致。 */
    private val recentlyAddedItems = mutableMapOf<String, List<MediaItem>>()
    private val recentlyAddedRefreshFailed = mutableMapOf<String, Boolean>()

    /** 「我的最爱」标签页的数据/失败标记,只有一个 bucket,不需要按 key 分组。 */
    private var favoriteItems: List<MediaItem> = emptyList()
    private var favoritesRefreshFailed: Boolean = false

    private var loadJob: Job? = null

    /**
     * 静默刷新用自己的 job,和 [loadJob](冷启动 / 重试 / 下拉刷新共用)分开(设计文档 §5)。
     *
     * 合用一个 job 会让定时器把用户主动发起的刷新取消掉 —— 那是优先级完全反了。
     */
    private var liveRefreshJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        sectionItems.clear()
        sectionRefreshFailed.clear()
        recentlyAddedItems.clear()
        recentlyAddedRefreshFailed.clear()
        favoriteItems = emptyList()
        favoritesRefreshFailed = false
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isOffline = false, error = null) }

            // 第一阶段:三个 flat 分区 + 「我的最爱」同时发起,再一起 join——并发而非串行。
            // 「我的最爱」不参与下面 error 的判定(它是独立标签页,不是 feed 的一部分),但刷新
            // 失败要计入整体 isOffline(见 recomputeState),失败/成功都不影响另外三个分区。
            (
                HomeSectionKind.entries.map { kind ->
                    launch {
                        repository.bucket(kind.bucket) { fetchSection(kind) }
                            .collect { cached -> applySection(kind, cached) }
                    }
                } + launch {
                    repository.bucket(CacheBuckets.HOME_FAVORITES) { fetchFavorites() }
                        .collect { cached -> applyFavorites(cached) }
                }
            ).joinAll()

            // 第二阶段:库列表已就绪(缓存或网络任一成功即可),按库并发拉"最近添加"——
            // 库与库之间互不阻塞、互不影响,与第一阶段内部的并发规则一致。
            sectionItems[HomeSectionKind.LIBRARIES].orEmpty()
                .map { library ->
                    launch {
                        repository.bucket(CacheBuckets.recentlyAddedOf(library.id)) {
                            fetchRecentlyAdded(library.id)
                        }.collect { cached -> applyRecentlyAddedGroup(library, cached) }
                    }
                }
                .joinAll()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    // 下拉刷新在中途被 load()/retry() 抢占(loadJob?.cancel())的边缘情形下,
                    // 新的这条流程结束时顺带把指示器收掉,不会卡在"一直转圈"。
                    isRefreshing = false,
                    // 三个 flat 分区一个都没拿到数据 = 没缓存 + 全部失败,这才是"连不上服务器"。
                    error = if (sectionItems.isEmpty()) OFFLINE_MESSAGE else null,
                )
            }
        }
    }

    /** 重试:与首次加载完全同路,失败后的重试成功同样会把结果写回缓存。 */
    fun retry() = load()

    /**
     * 下拉刷新(设计文档 §2.3)。走的是与 [load] 完全相同的仓储路径——[repository.bucket] 的
     * "先缓存后网络"编排,三个分区 + 收藏并发发起、第二阶段"最近添加"按库并发,**不是另起一套
     * 取数逻辑**。
     *
     * 与 [load] 唯一的区别:[load] 一上来先清空 [sectionItems] 等内存态(服务于"错误态点重试,
     * 从空白开始"这个场景),这里**不清空**——下拉刷新发生在屏幕上已经有内容的时候,先清空
     * 再一点点补回来,会先闪成空白再恢复,是这个手势明确要求禁止的行为。[applySection]/
     * [applyFavorites]/[applyRecentlyAddedGroup] 本来就是"整份覆盖对应 key"的写法,缓存那次
     * 发射(bucket 没变,几乎必定和屏幕上已经显示的一致)不会产生可见的变化,真正的新内容随后台
     * 网络到达时才会替换进去。
     *
     * 刷新失败不弹窗——[recomputeState] 的 `isOffline` OR 语义照旧生效,复用既有的离线横幅,
     * 不打断浏览(设计文档 §2.3)。
     */
    fun refresh() {
        loadJob?.cancel()
        _uiState.update { it.copy(isRefreshing = true) }
        loadJob = viewModelScope.launch {
            (
                HomeSectionKind.entries.map { kind ->
                    launch {
                        repository.bucket(kind.bucket) { fetchSection(kind) }
                            .collect { cached -> applySection(kind, cached) }
                    }
                } + launch {
                    repository.bucket(CacheBuckets.HOME_FAVORITES) { fetchFavorites() }
                        .collect { cached -> applyFavorites(cached) }
                }
            ).joinAll()

            sectionItems[HomeSectionKind.LIBRARIES].orEmpty()
                .map { library ->
                    launch {
                        repository.bucket(CacheBuckets.recentlyAddedOf(library.id)) {
                            fetchRecentlyAdded(library.id)
                        }.collect { cached -> applyRecentlyAddedGroup(library, cached) }
                    }
                }
                .joinAll()

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * 静默刷新「继续收听 / 下一集」(设计文档 §3)。
     *
     * ## 它解决的问题
     *
     * [HomeViewModel] 是 `@HiltViewModel`,**只要首页还在返回栈上就一直活着** —— [init] 里那次
     * [load] 之后,从播放页返回首页、从后台切回前台都不会再取一次数,屏幕上是几十分钟前的位置。
     * 用户在别处听过(或本机被系统杀掉)之后重开 App,点「继续收听」会从旧位置开始播。
     *
     * ## 为什么只有这两个分区
     *
     * 它们是唯一会因为"在别处听了一会儿"而改变的分区,也是点播主入口。库列表几乎不变;
     * 「最近添加」是**按库各发一个请求**,每分钟重拉一遍纯属浪费,出门走公网时尤其。
     *
     * ## 「静默」的边界
     *
     * 不置位 [HomeUiState.isRefreshing](那是下拉刷新手势的指示器)、不清空已有内容、失败不进
     * [HomeUiState.error]。失败只沿用 [recomputeState] 既有的 [HomeUiState.isOffline] 语义 ——
     * 屏幕上确实是旧数据,横幅是诚实的。
     *
     * ## 让路
     *
     * 冷启动 / 重试 / 下拉刷新在飞时直接跳过:同一个 bucket 被两条流程同时写只会产生无意义的
     * 重复请求,而用户主动发起的那条优先级更高。**绝不取消 [loadJob]。**
     * 冷启动时 [init] 的 [load] 通常仍在进行中,所以首页刚可见时的这一次会被这条规则跳过,
     * 不会重复请求。
     */
    fun refreshLive() {
        if (loadJob?.isActive == true || liveRefreshJob?.isActive == true) return
        liveRefreshJob = viewModelScope.launch {
            LIVE_REFRESH_SECTIONS
                .map { kind ->
                    launch {
                        repository.bucket(kind.bucket) { fetchSection(kind) }
                            .collect { cached -> applySection(kind, cached) }
                    }
                }
                .joinAll()
        }
    }

    fun selectTab(tab: HomeTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    /** [HomeUiState.actionError] 显示过一次后由 [HomeScreen] 调这个清空。 */
    fun consumeActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    /**
     * 收藏 / 取消收藏(设计文档 §3.7)。与 `LibraryViewModel.toggleFavorite` 同一套模式:
     * 乐观更新 → 成功写透缓存 / 失败原样回滚 + 提示。取消收藏时,乐观更新经由
     * [HomeUiState.favorites] 对 [MediaItem.isFavorite] 的过滤,条目立刻从「我的最爱」标签页
     * 消失,不需要额外的"从列表移除"逻辑。
     */
    fun toggleFavorite(item: MediaItem) {
        val target = !item.isFavorite
        mutateItem(item.id) { it.copy(isFavorite = target) }
        viewModelScope.launch {
            val result = runCatching {
                val userId = session.userId()
                if (target) api.addFavorite(item.id, userId) else api.removeFavorite(item.id, userId)
            }
            if (result.isSuccess) {
                repository.patchItem(item.id) { it.copy(isFavorite = target) }
            } else {
                mutateItem(item.id) { item }
                _uiState.update {
                    it.copy(actionError = if (target) "收藏失败,请重试" else "取消收藏失败,请重试")
                }
            }
        }
    }

    /** 把 [transform] 应用到 [itemId] 在首页各处出现的每一份缓存内存态,再重新拼一遍 UI 状态。 */
    private fun mutateItem(itemId: String, transform: (MediaItem) -> MediaItem) {
        sectionItems.keys.toList().forEach { k ->
            sectionItems[k] = sectionItems.getValue(k).replaceIfMatches(itemId, transform)
        }
        recentlyAddedItems.keys.toList().forEach { k ->
            recentlyAddedItems[k] = recentlyAddedItems.getValue(k).replaceIfMatches(itemId, transform)
        }
        favoriteItems = favoriteItems.replaceIfMatches(itemId, transform)
        recomputeState()
    }

    private suspend fun fetchSection(kind: HomeSectionKind): List<MediaItem> {
        val userId = session.userId()
        val response = when (kind) {
            HomeSectionKind.RESUME -> api.resume(userId)
            HomeSectionKind.NEXT_UP -> api.nextUp(userId)
            HomeSectionKind.LIBRARIES -> api.userViews(userId)
        }
        return response.items.mapNotNull { it.toMediaItem() }
    }

    private suspend fun fetchRecentlyAdded(libraryId: String): List<MediaItem> {
        val userId = session.userId()
        // 库里有 8744 集,不带 limit 会一次性拉全量并渲染;parentId 限定在这一个库内。
        val response = api.items(
            userId = userId,
            types = "Episode,Movie",
            sortBy = "DateCreated",
            limit = RECENTLY_ADDED_LIMIT,
            parentId = libraryId,
        )
        return response.items.mapNotNull { it.toMediaItem() }
    }

    /**
     * 「我的最爱」标签页取数(设计文档 §3.7):`isFavorite = true` 是 `/Items` 的专用布尔参数
     * (核对自 docs/jellyfin-openapi.json,与 `filters=IsFavorite` 等价但更直接,`JellyfinApi`
     * 已经单独暴露了这个参数)。混排 Series/Movie/Episode/BoxSet 四种类型——收藏可以是任意
     * 一种,不像浏览 Tab 那样按类型分开。
     */
    private suspend fun fetchFavorites(): List<MediaItem> {
        val userId = session.userId()
        val response = api.items(
            userId = userId,
            types = "Series,Movie,Episode,BoxSet",
            sortBy = "SortName",
            limit = FAVORITES_LIMIT,
            isFavorite = true,
        )
        return response.items.mapNotNull { it.toMediaItem() }
    }

    private fun applySection(kind: HomeSectionKind, cached: Cached<List<MediaItem>>) {
        sectionItems[kind] = cached.data
        sectionRefreshFailed[kind] = cached.refreshFailed
        recomputeState()
    }

    private fun applyFavorites(cached: Cached<List<MediaItem>>) {
        favoriteItems = cached.data
        favoritesRefreshFailed = cached.refreshFailed
        recomputeState()
    }

    private fun applyRecentlyAddedGroup(library: MediaItem, cached: Cached<List<MediaItem>>) {
        recentlyAddedItems[library.id] = cached.data
        recentlyAddedRefreshFailed[library.id] = cached.refreshFailed
        recomputeState()
    }

    /**
     * 每次发射都整体重拼一遍 state。分区顺序取自 [HomeSectionKind] 的声明顺序、分组顺序取自
     * 「我的媒体」分区里库的顺序,都与哪个请求先返回无关——否则"谁先到谁在上面"会让首页每次
     * 打开的排版都不一样。
     */
    private fun recomputeState() {
        _uiState.update { state ->
            state.copy(
                sections = HomeSectionKind.entries.mapNotNull { k ->
                    sectionItems[k]?.takeIf { it.isNotEmpty() }?.let { HomeSection(k, k.title, it) }
                },
                recentlyAddedGroups = sectionItems[HomeSectionKind.LIBRARIES].orEmpty()
                    .mapNotNull { library ->
                        recentlyAddedItems[library.id]
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { RecentlyAddedGroup(library.id, library.name, it) }
                    },
                // 只保留仍然收藏的条目——取消收藏的乐观更新(见 toggleFavorite)靠这个过滤
                // 立刻从标签页消失,不需要单独维护一份"移除了哪些 id"的状态。
                favorites = favoriteItems.filter { it.isFavorite },
                isOffline = sectionRefreshFailed.values.any { it } ||
                    recentlyAddedRefreshFailed.values.any { it } ||
                    favoritesRefreshFailed,
                error = null,
            )
        }
    }
}

/** [HomeViewModel.mutateItem] 用:命中 id 的那一条换成 [transform] 的结果,其余原样保留。 */
private fun List<MediaItem>.replaceIfMatches(itemId: String, transform: (MediaItem) -> MediaItem): List<MediaItem> =
    map { if (it.id == itemId) transform(it) else it }
