package dev.insua.jellycast.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.insua.jellycast.designsystem.ActionMessageHost
import dev.insua.jellycast.designsystem.OfflineBanner
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.designsystem.PosterCardWide
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl
import kotlinx.coroutines.delay

/** 定位节点用的测试标签,不依赖文案(文案会改)。 */
object HomeScreenTestTags {
    const val ERROR_RETRY = "home_error_retry"
    const val TOP_BAR = "home_top_bar"
    const val SEARCH_BUTTON = "home_top_bar_search"
    const val ACCOUNT_BUTTON = "home_top_bar_account"
    const val TAB_ROW = "home_tab_row"
    const val FAVORITES_EMPTY = "home_favorites_empty"
    const val PULL_REFRESH_CONTAINER = "home_pull_refresh_container"
    const val PULL_REFRESH_INDICATOR = "home_pull_refresh_indicator"

    fun item(id: String) = "home_item_$id"
}

/** 见 [HomeScreen] 的 `liveRefreshIntervalMs`。 */
internal const val LIVE_REFRESH_INTERVAL_MS = 60_000L

/**
 * "在听"首页。自上而下:继续收听 / 下一集 / 我的媒体 / 最近添加(按库分组)——下一集是追剧
 * 主入口,紧跟在继续收听之后,点开就能直接播放,呼应设计文档"3 次点击内开始播放下一集"的成功
 * 标准。全程不渲染视频画面,封面用 [PosterCard](海报卡,内部走 Coil AsyncImage)。
 *
 * 结构参照 Jellyfin Web mobile 首页(浅色,§3.6):库入口用来直接跳进某个库浏览;
 * 最近添加不再是一条混排的扁平列表,而是"最近添加的 {库名}"逐库分组,标题带 ">" 可点进该库。
 *
 * [onLibraryClick] 点击库卡片/分组标题时回调库 id,交给调用方(导航层)决定跳去哪个库——
 * 这里不认识具体的路由。
 *
 * [onItemClick] 第二个参数是**播放队列**(修正 §3.2:"没有上一集")——继续收听/下一集/
 * 最近添加分组点开某一项时,传出去的是**整个分区/分组**(见 [HomeSection.playQueueFor]/
 * [RecentlyAddedGroup.playQueueFor]),被点的那一项作为起点,而不是只含它自己的单项队列。
 * 以前导航层固定传 `listOf(item)`,`PlayQueue` 长度恒为 1,系统媒体控制的"上一集"按钮因此
 * 恒不可用。「我的最爱」标签页不是"分区"(混排不同来源的收藏,彼此没有先后关系),继续沿用
 * 单项队列。
 *
 * [baseUrl] 是当前激活服务器的接入地址,用于拼封面 URL([dev.insua.jellycast.network.mapper.posterUrl]);
 * 默认空串——:feature:home 目前还没有接入"当前激活服务器"的会话解析(Task 22 导航装配的职责),
 * 空串时不拼 URL,PosterCard 走占位背景兜底,不会拼出一个必 404 的地址。
 *
 * [onSearchClick]/[onAccountClick] 是顶部栏(设计文档 §3.6)搜索/账户入口的回调,默认空实现——
 * 本模块不认识具体路由,导航层(v1 期间是"跳进媒体库页"/"跳进设置页",两处都已经有对应功能:
 * 媒体库页自带搜索框、设置页自带服务器管理入口)决定点了之后去哪。参照的是 Jellyfin Web mobile
 * 的结构与信息层级,不是配色——App 仍是浅色主题。
 */
@Composable
fun HomeScreen(
    onItemClick: (MediaItem, List<MediaItem>) -> Unit,
    onLibraryClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    baseUrl: String = "",
    viewModel: HomeViewModel = hiltViewModel(),
    /**
     * 静默刷新间隔(设计文档 §3.2)。60 秒的取舍:这两个接口各自最多 20 条,单次开销很小;
     * 60 秒意味着"在别处听完一集"最迟一分钟反映到首页。更短在公网场景下是白耗流量。
     * 做成参数只为了让 UI 测试能传一个很短的值,生产调用点一律用默认值。
     */
    liveRefreshIntervalMs: Long = LIVE_REFRESH_INTERVAL_MS,
) {
    val uiState by viewModel.uiState.collectAsState()

    // 设计文档 §3:首页可见期间才刷新——见 [HomeLiveRefreshEffect] 的 KDoc。
    HomeLiveRefreshEffect(intervalMs = liveRefreshIntervalMs, onRefresh = viewModel::refreshLive)

    HomeScreenContent(
        uiState = uiState,
        onItemClick = onItemClick,
        onLibraryClick = onLibraryClick,
        onSearchClick = onSearchClick,
        onAccountClick = onAccountClick,
        baseUrl = baseUrl,
        onSelectTab = viewModel::selectTab,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onToggleFavorite = viewModel::toggleFavorite,
        onActionErrorShown = viewModel::consumeActionError,
    )
}

/**
 * 设计文档 §3:首页可见期间才刷新。
 *
 * - `repeatOnLifecycle(STARTED)` 让这段协程**在首页变为可见时启动、不可见时取消** ——
 *   离开首页或退到后台连协程都不存在,而不是"存在但不发请求"。后台定时打接口是耗电耗流量
 *   的典型反模式。
 * - 进入循环先刷一次,再按间隔重复:第一次覆盖「切回前台 / 从播放页返回首页」,
 *   之后的覆盖「停在首页不动,而在别的设备上继续听」。
 *
 * 单独抽出来(而不是内联进 [HomeScreen])是为了让 [HomeLiveRefreshTest] 能用一个只计数的假
 * [onRefresh] 驱动来测试"什么时候该刷",不需要真的构造 [HomeViewModel] 及其 Hilt/网络依赖——
 * "刷什么"已经由 [HomeViewModel] 自己的单测([refreshLive])覆盖了。
 */
@Composable
internal fun HomeLiveRefreshEffect(intervalMs: Long, onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, intervalMs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                onRefresh()
                delay(intervalMs)
            }
        }
    }
}

/**
 * 真正的界面(纯函数、不依赖 ViewModel/Hilt/网络),方便 Compose UI 测试直接喂手工构造的
 * [HomeUiState]——与 `:feature:library` 的 `LibraryScreenContent`/`LibraryContentsScreenContent`
 * 同样的拆分理由,尤其是修正 §3.2("没有上一集")需要在 Compose 树上证明"点击分区第二项,
 * [onItemClick] 收到的队列确实是整个分区"这件事,单靠纯逻辑单测(见 [HomeScreenTest]的
 * `playQueueFor` 断言)测不出真正的接线是不是对的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onItemClick: (MediaItem, List<MediaItem>) -> Unit,
    onLibraryClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    baseUrl: String = "",
    onSelectTab: (HomeTab) -> Unit = {},
    onRetry: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onToggleFavorite: (MediaItem) -> Unit = {},
    onActionErrorShown: () -> Unit = {},
) {
    // 「这一次浏览会话里已经看过离线提示了」是页面级状态,不进 ViewModel:重新进入首页
    // (或点重试重新加载)时提示该重新出现,而不是被永久关掉。
    var offlineNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    Box {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        HomeTopBar(onSearchClick = onSearchClick, onAccountClick = onAccountClick)

        // 顶部标签(设计文档 §3.6):首页信息流 / 我的最爱。切标签不触发新请求——两份数据
        // 已经在 [HomeViewModel.load] 里并发取好,这里只是本地状态切换。
        TabRow(
            selectedTabIndex = uiState.tab.ordinal,
            modifier = Modifier.testTag(HomeScreenTestTags.TAB_ROW),
        ) {
            Tab(
                selected = uiState.tab == HomeTab.FEED,
                onClick = { onSelectTab(HomeTab.FEED) },
                text = { Text("首页") },
            )
            Tab(
                selected = uiState.tab == HomeTab.FAVORITES,
                onClick = { onSelectTab(HomeTab.FAVORITES) },
                text = { Text("我的最爱") },
            )
        }

        // 显示的是上次的内容:提示一句,但绝不挡住内容本身,也允许用户关掉。这条横幅覆盖两个
        // 标签共同的离线状态(HomeUiState.isOffline 已经 OR 了「我的最爱」的刷新结果),
        // 放在 TabRow 之下、两个标签内容之上,不随切标签而改变含义。
        //
        // ⚠️ **必须放在 LazyColumn 外面。** 放进去(`item(key = "home_offline")`)会踩到懒列表的
        // 锚定行为:分区是先到的,提示条是后到的(网络失败晚于读缓存),于是这一项是被**插入到
        // 已有项之前**的。LazyListState 带 key 时会保持原来那一项的位置不动,新插入的项因此
        // 落在视口**上方** —— 它确实被组合出来了,用户却要往上滑才看得见。真机验证时正是这个
        // 现象:日志里 isOffline=true、屏幕上什么都没有。
        val hasVisibleContent = if (uiState.tab == HomeTab.FEED) uiState.sections.isNotEmpty() else uiState.favorites.isNotEmpty()
        if (uiState.isOffline && hasVisibleContent && !offlineNoticeDismissed) {
            OfflineBanner(onDismiss = { offlineNoticeDismissed = true })
        }

        // 下拉刷新(设计文档 §2.3):只包住"当前 Tab 的内容"这一层,不含顶栏/TabRow/离线横幅——
        // 手势应该发生在列表本身上。[HomeViewModel.refresh] 覆盖两个 Tab 共同的数据源(三个分区
        // + 收藏 + 最近添加都在同一次并发刷新里),所以不管当前停在哪个 Tab,下拉都会让两边的
        // 数据一起更新;`isRefreshing` 只是这一次手势的指示器状态,不是"哪个 Tab 在转"。
        val pullRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(HomeScreenTestTags.PULL_REFRESH_CONTAINER),
            indicator = {
                // 只在"确实有事情正在发生"(手指正在拖 / 正在回弹动画 / 真的在刷新)时才把指示器
                // 组合进树——而不是让它常驻树上、只靠 M3 内部的 alpha/scale 动画变透明。默认
                // Indicator 用 graphicsLayer 做淡入淡出,不影响布局尺寸,`assertIsDisplayed()`
                // 判定的是布局边界与视口的交集,量不出"透明但还占着位置"这种不可见——与本项目
                // v3 离线横幅"存在但被挤出可视区"是同一类坑的另一种表现形式,所以这里干脆不让它
                // 在空闲态进入语义树,`assertDoesNotExist()`(真的不存在)+ `assertIsDisplayed()`
                // (真的在刷新时看得见)这一组断言才是站得住脚的。
                if (uiState.isRefreshing || pullRefreshState.distanceFraction > 0f || pullRefreshState.isAnimating) {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = uiState.isRefreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .testTag(HomeScreenTestTags.PULL_REFRESH_INDICATOR),
                    )
                }
            },
        ) {
            when (uiState.tab) {
                HomeTab.FEED -> HomeFeed(
                    uiState = uiState,
                    baseUrl = baseUrl,
                    onItemClick = onItemClick,
                    onLibraryClick = onLibraryClick,
                    onRetry = {
                        offlineNoticeDismissed = false
                        onRetry()
                    },
                )
                HomeTab.FAVORITES -> FavoritesGrid(
                    favorites = uiState.favorites,
                    baseUrl = baseUrl,
                    // 「我的最爱」不是"分区"——混排各种来源的收藏,彼此没有先后关系,不构造队列,
                    // 沿用点哪个就单独播哪个(与改动前行为一致)。
                    onItemClick = { item -> onItemClick(item, listOf(item)) },
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }

    ActionMessageHost(message = uiState.actionError, onMessageShown = onActionErrorShown)
    }
}

/**
 * 顶部栏(设计文档 §3.6):App 身份(名称)+ 搜索入口 + 账户入口。参照的是 Jellyfin Web mobile
 * 的结构——一行放"你在哪个 App"和两个跳转口子,不是它的深色配色,JellyCast 全程保持浅色。
 *
 * ⚠️ **[windowInsets] 显式清零,状态栏 inset 由外层 `Scaffold` 统一消费一次。**
 * Material3 的 `TopAppBar` 默认带 `TopAppBarDefaults.windowInsets`(含状态栏),而
 * `JellyCastNavHost` 的 `Scaffold` 也把系统栏 inset 计入内容内边距、通过 `padding(padding)`
 * 传给 NavHost —— 两者叠加,首页顶部就凭空多出一个状态栏的高度(真机实测 66px @440dpi,
 * 见 `HomeScreenInsetTest`)。
 *
 * 为什么让顶栏让路、而不是让 Scaffold 让路:Scaffold 是**全 App 唯一**的那层外壳,它同时
 * 负责底部——迷你播放条和底部 tab 栏就长在它的 `bottomBar` 里,导航栏 inset 靠它。把它改成
 * `contentWindowInsets = WindowInsets(0)` 就得让**每一个**页面各自处理系统栏,是更大的改动、
 * 更多的出错面。顶栏这一处清零只影响首页,底部完全不受牵连。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(onSearchClick: () -> Unit, onAccountClick: () -> Unit) {
    TopAppBar(
        title = { Text("JellyCast") },
        windowInsets = WindowInsets(0, 0, 0, 0),
        actions = {
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.testTag(HomeScreenTestTags.SEARCH_BUTTON),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            IconButton(
                onClick = onAccountClick,
                modifier = Modifier.testTag(HomeScreenTestTags.ACCOUNT_BUTTON),
            ) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "账户")
            }
        },
        modifier = Modifier.testTag(HomeScreenTestTags.TOP_BAR),
    )
}

@Composable
private fun HomeFeed(
    uiState: HomeUiState,
    baseUrl: String,
    onItemClick: (MediaItem, List<MediaItem>) -> Unit,
    onLibraryClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 96.dp), // 给底部常驻的 MiniPlayerBar 让位
    ) {
        // 没缓存又连不上服务器:给一个可点的重试行,而不是一片什么都没有的白屏。
        // 只要有任何一个分区有内容(哪怕是缓存),error 就是 null,这一行不会盖住它们。
        uiState.error?.let { message ->
            item(key = "home_error") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .testTag(HomeScreenTestTags.ERROR_RETRY),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "点击重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        for (section in uiState.sections) {
            item(key = section.kind) {
                HomeSectionRow(
                    section = section,
                    baseUrl = baseUrl,
                    // 「我的媒体」的卡片点的是库,不是可播放条目——回调换成 onLibraryClick。
                    // 其余分区(继续收听/下一集)把整个分区作为播放队列传出(修正 §3.2),
                    // 被点的那一项是起点——见 [HomeSection.playQueueFor] 的 KDoc。
                    onItemClick = { mediaItem ->
                        if (section.kind == HomeSectionKind.LIBRARIES) {
                            onLibraryClick(mediaItem.id)
                        } else {
                            onItemClick(mediaItem, section.playQueueFor(mediaItem))
                        }
                    },
                )
            }
        }
        // 「最近添加」按库分组(设计文档 §3.6),紧跟在三个 flat 分区之后。空分组已经被
        // ViewModel 过滤掉,这里不会画出一个没有内容的标题。
        for (group in uiState.recentlyAddedGroups) {
            item(key = "recent_${group.libraryId}") {
                RecentlyAddedGroupRow(
                    group = group,
                    baseUrl = baseUrl,
                    // 同一个库分组内的条目就是队列(修正 §3.2)——见 [RecentlyAddedGroup.playQueueFor]。
                    onItemClick = { mediaItem -> onItemClick(mediaItem, group.playQueueFor(mediaItem)) },
                    onLibraryClick = onLibraryClick,
                )
            }
        }
    }
}

/**
 * 「我的最爱」标签页(设计文档 §3.6/§3.7):混排的收藏条目网格,点击行为按类型分流——
 * 剧集是"库"这一层概念在这里不会出现,所以只需要区分"是不是可以直接播放"。已收藏的剧集条目
 * 直接点播(与浏览页/剧集详情不同,这里没有"进详情选季"这层导航,保持最短路径)。
 * 每张卡片右上角是收藏按钮,点了立刻从这个网格消失(乐观更新,见 [HomeViewModel.toggleFavorite])。
 */
@Composable
private fun FavoritesGrid(
    favorites: List<MediaItem>,
    baseUrl: String,
    onItemClick: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "还没有收藏的内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(HomeScreenTestTags.FAVORITES_EMPTY),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(favorites, key = { it.id }) { mediaItem ->
            PosterCard(
                title = mediaItem.name,
                subtitle = mediaItem.displaySubtitle.ifBlank { null },
                imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                onClick = { onItemClick(mediaItem) },
                isFavorite = mediaItem.isFavorite,
                onToggleFavorite = { onToggleFavorite(mediaItem) },
            )
        }
    }
}

@Composable
private fun HomeSectionRow(section: HomeSection, baseUrl: String, onItemClick: (MediaItem) -> Unit) {
    // 下一集是追剧主入口(设计文档"3 次点击内开始播放下一集"的成功标准),标题用主题色 + 加粗
    // 突出显示,和另外分区区分开——只是排版上的强调,不改变布局结构。
    val isPrimarySection = section.kind == HomeSectionKind.NEXT_UP
    Text(
        text = section.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (isPrimarySection) FontWeight.Bold else FontWeight.Normal,
        color = if (isPrimarySection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (section.kind == HomeSectionKind.RESUME) {
            // 继续收听改 16:9 宽幅卡 + 通栏进度条(设计文档 §3.6,参照 Jellyfin Web mobile)——
            // 其余分区(下一集/我的媒体)保持原来的 1:1 [PosterCard],不受影响。
            items(section.items, key = { it.id }) { mediaItem ->
                PosterCardWide(
                    title = mediaItem.name,
                    subtitle = mediaItem.displaySubtitle.ifBlank { null },
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = { onItemClick(mediaItem) },
                    modifier = Modifier.testTag(HomeScreenTestTags.item(mediaItem.id)),
                    progress = mediaItem.resumeProgressFraction(),
                )
            }
        } else {
            items(section.items, key = { it.id }) { mediaItem ->
                PosterCard(
                    title = mediaItem.name,
                    subtitle = mediaItem.displaySubtitle.ifBlank { null },
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = { onItemClick(mediaItem) },
                    modifier = Modifier.testTag(HomeScreenTestTags.item(mediaItem.id)),
                )
            }
        }
    }
}

/**
 * "最近添加的 {库名}" 一行:标题旁的 ">" 和标题本身都跳进该库(参照 Jellyfin Web mobile 的
 * 分组表头)。卡片右上角叠一个未看数角标——[MediaItem.unplayedBadgeCountOrNull] 保证角标为
 * 0 或缺失时不画,不会出现一个写着"0"的角标。
 */
@Composable
private fun RecentlyAddedGroupRow(
    group: RecentlyAddedGroup,
    baseUrl: String,
    onItemClick: (MediaItem) -> Unit,
    onLibraryClick: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLibraryClick(group.libraryId) }
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "最近添加的 ${group.libraryName}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(group.items, key = { it.id }) { mediaItem ->
            Box {
                PosterCard(
                    title = mediaItem.name,
                    subtitle = mediaItem.displaySubtitle.ifBlank { null },
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = { onItemClick(mediaItem) },
                    modifier = Modifier.testTag(HomeScreenTestTags.item(mediaItem.id)),
                )
                mediaItem.unplayedBadgeCountOrNull()?.let { count ->
                    UnplayedBadge(count = count, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun UnplayedBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

/**
 * 分区里某一项被点击时应该传给 [onItemClick] 的播放队列(修正 §3.2:"没有上一集")——原样返回
 * 整份分区列表,而不是只含被点的那一项。以前导航层传的是 `listOf(item)`,`PlayQueue` 长度
 * 恒为 1,系统媒体控制的"上一集"因此恒不可用(见 `core/player` 的 `PlayQueueTest`:队列长度
 * 够、起点不在队首,`hasPrevious()` 才会是 true)。和剧集详情页"整季即队列"
 * ([dev.insua.jellycast.feature.library.LibraryViewModel.queueFor])是同一个模式,只是这里的
 * "季"换成了"分区"。[tapped] 目前不参与运算,保留是为了和 `queueFor(episode)` 同样的调用形状,
 * 便于以后需要按起点过滤/排序时不用改调用方。
 */
internal fun HomeSection.playQueueFor(tapped: MediaItem): List<MediaItem> = items

/** 同上,针对"最近添加"按库分组的那一行——同一个库分组内的条目就是队列。 */
internal fun RecentlyAddedGroup.playQueueFor(tapped: MediaItem): List<MediaItem> = items

/**
 * "听过比例"的纯换算:输入输出全是毫秒/Float,不接触 ticks、不依赖 Android,可离线单测。
 * 三种退化情形都返回 null(= PosterCard 不画进度条),而不是画一条 0 宽或除零崩溃的条:
 * - 没有总时长(点播列表接口偶尔不带 RunTimeTicks)
 * - 总时长是 0
 * - 一秒都没听过(resumePositionMs == 0,不算"进行中")
 * 服务端上报的位置理论上不会超过总时长,但客户端仍要钳制到 1f,不能画出溢出的进度条。
 */
internal fun MediaItem.resumeProgressFraction(): Float? {
    val totalMs = runTimeMs
    if (totalMs == null || totalMs <= 0L || resumePositionMs <= 0L) return null
    return (resumePositionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * "未看数角标"要不要画的纯逻辑:输入输出全是 Int?,不接触 Compose,可离线单测。
 * null(服务端没给这个字段)和 0(确实没有未看)都返回 null —— 一个写着"0"的角标毫无意义,
 * 比不画角标更容易让用户以为是 bug。
 */
internal fun MediaItem.unplayedBadgeCountOrNull(): Int? = unplayedItemCount?.takeIf { it > 0 }
