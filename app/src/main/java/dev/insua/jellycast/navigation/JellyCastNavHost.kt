package dev.insua.jellycast.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.insua.jellycast.designsystem.MiniPlayerBar
import dev.insua.jellycast.feature.home.HomeScreen
import dev.insua.jellycast.feature.library.CollectionDetailScreen
import dev.insua.jellycast.feature.library.LibraryContentsScreen
import dev.insua.jellycast.feature.library.LibraryScreen
import dev.insua.jellycast.feature.library.SeriesDetailScreen
import dev.insua.jellycast.feature.player.PlayerScreen
import dev.insua.jellycast.feature.server.AddServerScreen
import dev.insua.jellycast.feature.server.ServerListScreen
import dev.insua.jellycast.feature.settings.SettingsScreen

/**
 * 装配全部 `:feature:*` 入口 Composable(修正 §4/§9)。
 *
 * **关键:迷你播放条不在 [NavHost] 内部。** 它和底部 tab 栏一起放在 [Scaffold] 的 `bottomBar` 里,
 * 是 [NavHost] 的兄弟节点而不是子节点——切 Tab 只是 [NavHost] 内部换目的地,`bottomBar` 整体
 * 不会重组/消失,迷你播放条自然常驻。只有 [MiniPlayerUiState] 非 null(有正在播放的内容)时才
 * 显示,且只在"有 tab 栏的目的地"([Routes.CHROME_ROUTES])显示——服务器列表/添加表单是登录前
 * 流程,不需要,也不应该在那两个页面上出现底部 UI。
 *
 * **`player` 不是一个 NavHost 路由。** 全屏播放页作为 [ModalBottomSheet] 覆盖在 `Scaffold` 之上,
 * 用一个本地布尔状态([playerExpanded])驱动展开/收起,而不是 `navController.navigate("player")`——
 * 原因是 bottom sheet 需要在展开时**仍然看得到/保留**底下的迷你播放条状态和整个 tab 结构,
 * 用真正的 NavHost 目的地会整页替换掉,不符合"点迷你条展开、收起后回到原来的 tab"这个交互。
 * 效果上完全等价于修正 §4 路由表里"player(bottom sheet)"这一行的要求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyCastNavHost(
    navController: NavHostController = rememberNavController(),
    sessionViewModel: AppSessionViewModel = hiltViewModel(),
) {
    val startDestination by sessionViewModel.startDestination.collectAsState()
    val start = startDestination

    if (start == null) {
        // 起点判定中(读一次 ServerStore.activeServerId):瞬时状态,不值得单独设计骨架屏。
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    val baseUrl by sessionViewModel.baseUrl.collectAsState()
    val miniPlayer by sessionViewModel.miniPlayer.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showChrome = currentRoute in Routes.CHROME_ROUTES

    var playerExpanded by remember { mutableStateOf(false) }

    // 断网时点播放的提示(设计文档 §3.2 第四行)。用 Snackbar 而不是对话框:它不拦截操作,
    // 用户可以立刻接着浏览缓存里的内容。
    val snackbarHostState = remember { SnackbarHostState() }
    val message by sessionViewModel.message.collectAsState()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        // 播放没起来,却把全屏播放页展开了,用户会盯着一个空白播放页发呆(还看不到底下的
        // Snackbar)。所以先收起来,再提示。
        playerExpanded = false
        snackbarHostState.showSnackbar(text)
        sessionViewModel.onMessageShown()
    }

    // 「这一串播完了」→ 收起播放页、回首页(2026-07-29 用户需求:整部剧播完 / 电影播完都回首页;
    // 「已播放完」的提示走上面那条 message 通道)。决策本身在 AppSessionViewModel 的纯函数
    // playbackSequenceEndEffect 里,:core:player 只发信号、不认识导航(设计文档 §5)。
    val returnToHome by sessionViewModel.returnToHome.collectAsState()
    LaunchedEffect(returnToHome) {
        if (!returnToHome) return@LaunchedEffect
        playerExpanded = false
        // 播放序列结束时可能正停在「媒体库」tab 的子页面上(剧集详情/合集详情/按库浏览)——
        // 这一集/这部剧已经看完了,以后回到「媒体库」tab 不需要自动弹回那个子页面,只需要保住
        // "媒体库"这个列表本身的浏览位置。所以先把子页面永久弹掉(不 saveState,它们不该被
        // 恢复),只留 tab 根在栈上,再统一走 popUpTo(HOME){saveState=true} 把"根"存进
        // backStackMap。
        //
        // 🔴 为什么不能只调整下面 navigate 的 NavOptions 了事(brief 给的两种写法都实测跑不通):
        // - `popUpTo(HOME){inclusive=true; saveState=true}`:inclusive 把 HOME 自己也弹出、也
        //   存进 backStackMap,这时 HOME 是"被弹出的这段里最深的那个目的地",于是 restoreState
        //   命中的不是空首页,而是**连同它上面的 library/detail 一起原样恢复**——从详情页触发
        //   这次跳转后,界面停在原地的详情页,`home` 目的地根本没出现过(实测:连
        //   `test_home_screen` 都找不到)。这比"首页没重置"更糟,是彻底的导航失败。
        // - `popUpTo(HOME){saveState=true}`(不带 inclusive,brief 给的备选):HOME 确实正常
        //   落地了,但 popUpTo(HOME) 弹出的是 [library, detail] **整段**,按"这段里最深的目的地"
        //   (library)存成一个整体。下次点『媒体库』tab、`restoreState` 命中这个 key 时,恢复的
        //   是**整段**,落地在 detail 上,不是 library 列表——实测 logcat:
        //   `backstack changed: currentRoute=detail/{seriesId} stack=[..., library#fbc5, detail#2370]`,
        //   entry id 与销毁前逐字相同,列表网格根本没显示,`firstVisibleItemIndex=-1`。
        // 只有先把子页面单独弹掉(不存)、只留 library 自己进 backStackMap,才能保证以后恢复出来
        // 的正是列表本身。
        navController.popBackStack(Routes.LIBRARY, inclusive = false)
        navController.navigate(Routes.HOME) {
            // saveState:播放序列结束回首页,不该把用户在「媒体库」里的浏览位置一并销毁。
            // 少了它,library 条目连同它的 ViewModelStore / SaveableStateHolder 槽位被整个销毁,
            // 下次进媒体库是一次全新 push,列表从第一页重拉、滚动回到顶部(实测)。
            // 不带 inclusive:HOME 自己不需要参与 save/restore,原因见上面那段注释。
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        sessionViewModel.onReturnToHomeHandled()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showChrome) {
                Column {
                    miniPlayer?.let { state ->
                        MiniPlayerBar(
                            title = state.title,
                            subtitle = state.subtitle,
                            posterUrl = state.posterUrl,
                            isPlaying = state.isPlaying,
                            progress = state.progress,
                            hasNext = state.hasNext,
                            onPlayPause = sessionViewModel::onMiniPlayerPlayPause,
                            onSkipNext = sessionViewModel::onMiniPlayerSkipNext,
                            onExpand = { playerExpanded = true },
                        )
                    }
                    BottomNavBar(currentRoute = currentRoute, navController = navController)
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.SERVERS) {
                ServerListScreen(
                    onServerReady = {
                        sessionViewModel.onServerConnected()
                        navController.navigate(Routes.HOME) { popUpTo(0) }
                    },
                    onAddServer = { navController.navigate(Routes.SERVERS_ADD) },
                )
            }
            composable(Routes.SERVERS_ADD) {
                AddServerScreen(
                    onDone = {
                        sessionViewModel.onServerConnected()
                        navController.navigate(Routes.HOME) { popUpTo(0) }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    // 队列(修正 §3.2,"没有上一集"):HomeScreen 按点击来源(继续收听/下一集/
                    // 最近添加分组)算好整份队列传出来,这里原样转给 sessionViewModel.play——
                    // 不能再退化成 listOf(item),否则 PlayQueue 长度恒为 1、上一集恒不可用。
                    onItemClick = { item, queue ->
                        sessionViewModel.play(item, queue)
                        playerExpanded = true
                    },
                    // 「我的媒体」库卡片(修正 §3.1):之前这里没接,走了 HomeScreen 参数的默认空
                    // 实现,点了没反应。跳到新增的按库浏览路由,库内容用 /Items?parentId= 拉取。
                    onLibraryClick = { libraryId -> navController.navigate(Routes.libraryView(libraryId)) },
                    // 搜索入口跳进媒体库页——搜索框本来就长在那里(见 LibraryScreen 的
                    // LibraryScreenTestTags.SEARCH_FIELD),顶部栏这里不重新实现一遍搜索。
                    onSearchClick = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // 账户入口跳进设置页——服务器管理(登录态/账号切换)本来就在那里。
                    onAccountClick = { navController.navigate(Routes.SETTINGS) },
                    baseUrl = baseUrl,
                    // 播放页是 bottom sheet、不是 NavHost 目的地(见本文件顶部 KDoc)——展开/
                    // 收起不产生任何 Lifecycle 转换,首页的静默刷新单靠 Lifecycle 感知不到"被
                    // 播放页盖住"。这里把 playerExpanded 原样喂给 HomeScreen,由它自己的
                    // HomeLiveRefreshEffect 决定要不要把这算作"首页不可见"。
                    isPlayerExpanded = playerExpanded,
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onSeriesClick = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
                    onPlay = { item ->
                        sessionViewModel.play(item, listOf(item))
                        playerExpanded = true
                    },
                    onCollectionClick = { collectionId ->
                        navController.navigate(Routes.collectionDetail(collectionId))
                    },
                    baseUrl = baseUrl,
                )
            }
            composable(Routes.SERIES_DETAIL_PATTERN) { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                SeriesDetailScreen(
                    seriesId = seriesId,
                    onPlay = { episode, queue ->
                        sessionViewModel.play(episode, queue)
                        playerExpanded = true
                    },
                    baseUrl = baseUrl,
                )
            }
            composable(Routes.COLLECTION_DETAIL_PATTERN) { entry ->
                val collectionId = entry.arguments?.getString("collectionId") ?: return@composable
                CollectionDetailScreen(
                    collectionId = collectionId,
                    onSeriesClick = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
                    onPlay = { item ->
                        sessionViewModel.play(item, listOf(item))
                        playerExpanded = true
                    },
                    baseUrl = baseUrl,
                )
            }
            composable(Routes.LIBRARY_VIEW_PATTERN) { entry ->
                val libraryId = entry.arguments?.getString("libraryId") ?: return@composable
                LibraryContentsScreen(
                    libraryId = libraryId,
                    onSeriesClick = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
                    onPlay = { item ->
                        sessionViewModel.play(item, listOf(item))
                        playerExpanded = true
                    },
                    baseUrl = baseUrl,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onManageServers = { navController.navigate(Routes.SERVERS) })
            }
        }
    }

    if (playerExpanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { playerExpanded = false }, sheetState = sheetState) {
            PlayerScreen(onCollapse = { playerExpanded = false }, baseUrl = baseUrl)
        }
    }
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab(Routes.HOME, "在听", Icons.Filled.Headphones),
    BottomTab(Routes.LIBRARY, "媒体库", Icons.Filled.VideoLibrary),
    BottomTab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
private fun BottomNavBar(currentRoute: String?, navController: NavHostController) {
    NavigationBar {
        BOTTOM_TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    when {
                        // 已经站在这个 tab 的根上:什么都不做。
                        currentRoute == tab.route -> Unit

                        // 已经在这个 tab 的子页面里(该 tab 的根还在返回栈上):退回它的根。
                        //
                        // 🔴 这里**不能**走下面那条 navigate 分支。Navigation-Compose 2.9.8 的
                        // navigate 先做 popBackStackInternal(saveState = true),把弹出的那段按
                        // 「最深被弹出的目的地」为键存起来——在这个扁平图里那个键就是 tab 自己——
                        // 然后 restoreState 又把它原样恢复回来,净效果是零。实测:详情页上点
                        // 「媒体库」,返回栈逐字节不变,entry id 都没变。
                        //
                        // popBackStack 保住的是**同一个** NavBackStackEntry,因此
                        // ViewModelStore 和 SaveableStateHolder 槽位都还在,滚动位置随之保住——
                        // 这正是我们要的。目的地不在栈上时它返回 false 且**不改动栈**,所以可以
                        // 安全地当条件用。
                        navController.popBackStack(tab.route, inclusive = false) -> Unit

                        // 真正的跨 tab 切换:保留原有的 save/restore 语义(切走再切回来,回到你
                        // 离开时的位置)。
                        else -> navController.navigate(tab.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
