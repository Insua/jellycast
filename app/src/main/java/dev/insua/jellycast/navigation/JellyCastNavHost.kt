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
                            onPlayPause = sessionViewModel::onMiniPlayerPlayPause,
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
                    onItemClick = { item ->
                        sessionViewModel.play(item, listOf(item))
                        playerExpanded = true
                    },
                    baseUrl = baseUrl,
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onSeriesClick = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
                    onPlay = { item ->
                        sessionViewModel.play(item, listOf(item))
                        playerExpanded = true
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
                    if (currentRoute != tab.route) {
                        navController.navigate(tab.route) {
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
