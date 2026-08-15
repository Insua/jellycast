package dev.insua.jellycast.navigation

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.feature.library.LibraryScreenContent
import dev.insua.jellycast.feature.library.LibraryScreenTestTags
import dev.insua.jellycast.feature.library.LibraryTab
import dev.insua.jellycast.feature.library.LibraryUiState
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复现任务(Task 5,只复现不修,详见
 * `.superpowers/sdd/2026-08-15-high-bitrate-playback-and-scroll-restore/task-5-brief.md`):
 * 滚动到列表中部 → 进详情页 → 返回,滚动位置丢失、列表回到顶部。
 *
 * 已排除"忘了用 rememberSaveable"——四个列表用的都是 saveable 支撑的滚动状态
 * ([dev.insua.jellycast.feature.library.LibraryScreen] 的 `gridState`)。这里手搭一个最小
 * `NavHost`,尽量贴近 [JellyCastNavHost] 的 `navigate` 选项,逐一核验首要嫌疑:
 *
 * (a) 从底部导航栏返回:`popUpTo(HOME) { saveState = true }` 会把 "library" 这个
 *     `NavBackStackEntry` 连同它的 `ViewModelStore` 一起弹出销毁——**已用日志证实**(见
 *     `FakeLibraryViewModel` 的 create/cleared 日志):重新进入 "library" 会拿到一个全新的
 *     ViewModel 实例(instanceId 递增),分页数据全部丢失、重新走一次(模拟的)网络加载;
 *     `LazyGrid` 恢复出保存的滚动索引后,第一次真正测量时列表还是空的(数据还没到),索引被
 *     夹到 0,且这次夹紧是"破坏性"的——数据到达后不会自愈回原来的位置。
 * (b) 用返回手势:"library" 这个条目留在返回栈上,ViewModel 不会被销毁,滚动位置保持——
 *     **已验证成立**(见 [返回手势回到列表时滚动位置保持],全程只有一个 ViewModel 实例)。
 *
 * **一个意外发现(与逐字复刻的偏差,已在 [TestNavRoot] 里说明并记录):**
 * [JellyCastNavHost.kt] 第 266 行底部导航栏用的是
 * `popUpTo(Routes.HOME) { saveState = true } + launchSingleTop = true + restoreState = true`
 * 三者同时出现。逐字复刻这三者、从 "detail" 点回 "library" 时,实测是**彻底的 no-op**——
 * `navController.navigate(...)` 正常返回、不抛异常,但 `currentBackStack` 真的一次都没变
 * (用 `LaunchedEffect(currentRoute)` 订阅确认过,不是读到了一次性的 stale 值)。只要去掉
 * `restoreState = true`(保留 `popUpTo(saveState=true)` + `launchSingleTop`),就会正常导航并
 * 复现出 (a) 描述的 ViewModel 重建 + 滚动清零。也就是说 `saveState=true` 和 `restoreState=true`
 * 同时作用在"当前正在被弹出的那个目的地本身"(不是在切换到另一个 tab,而是从它自己的子页面
 * 点同一个 tab 的图标)这个场景,在这次实测的 Navigation Compose 版本上,可能是另一个独立缺陷
 * ——本任务的测试为了能测到"滚动丢失"这件事,采用了去掉 `restoreState` 的版本,详情见报告。
 *
 * 为了让 (a) 的"首次测量为空"这个环节真的有机会发生,[FakeLibraryViewModel] 的数据**异步**
 * 到货(模拟真实 [LibraryViewModel.loadLibrary] 要走一次网络往返这件事)——如果直接把 60 条
 * 数据同步写进 ViewModel 的构造函数,即使 ViewModel 真的被重建,`LazyGrid` 第一次测量时也已经
 * 有全量数据,复现不出"首次测量为空"这个环节。
 *
 * 用真正的 [ViewModel]([FakeLibraryViewModel] 挂在 "library" 这个 `NavBackStackEntry` 上,
 * 而不是把数据直接写死在 composable 里)是复现 (a) 的关键——数据写死在 composable 里根本不存在
 * "可能丢"的状态,那条路径永远复现不出来。
 */
@RunWith(AndroidJUnit4::class)
class ListScrollRestoreTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun 返回手势回到列表时滚动位置保持() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)

        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).assertExists()

        val after = composeTestRule.firstVisibleItemIndex()
        Log.i(TAG, "[返回手势] afterIndex=$after")
        assert(after >= MIN_ACCEPTABLE_INDEX) {
            "返回手势回到列表后,滚动位置应保持(firstVisibleItemIndex >= $MIN_ACCEPTABLE_INDEX)," +
                "实际 firstVisibleItemIndex=$after"
        }
    }

    @Test
    fun 从底部导航栏回到列表时滚动位置保持() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitForLibraryContentSettled()
        // NavHost 切目的地带默认的 AnimatedContent 过渡:detail 淡出、library 淡入。测量必须等
        // 这次过渡真正落定(detail 的节点从语义树上消失)之后再做,否则可能在两屏同时挂在树上
        // 的过渡中间帧量出一个没有意义的瞬时值(实测踩到过:量出 firstVisibleItemIndex=-1,
        // 比"真的被重置到 0"更极端——那是量早了,不是真的没有任何条目可见)。
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTagCount(DETAIL_TAG) == 0
        }
        composeTestRule.waitForIdle()

        val after = composeTestRule.firstVisibleItemIndex()
        Log.i(TAG, "[底部导航栏] afterIndex=$after")
        assert(after >= MIN_ACCEPTABLE_INDEX) {
            "从底部导航栏回到列表后,滚动位置应保持(firstVisibleItemIndex >= $MIN_ACCEPTABLE_INDEX)," +
                "实际 firstVisibleItemIndex=$after"
        }
    }
}

private const val TAG = "ListScrollRestoreTest"
private const val ITEM_COUNT = 60
private const val SCROLL_TARGET_INDEX = 40
private const val MIN_ACCEPTABLE_INDEX = 35

/** 模拟真实 [dev.insua.jellycast.feature.library.LibraryViewModel] 要走一次网络往返——
 *  数据不会在 ViewModel 构造的同一帧就绪。 */
private const val LOAD_DELAY_MS = 50L

private const val BOTTOM_NAV_TAG = "test_bottom_nav_library"
private const val DETAIL_TAG = "test_detail_screen"

private fun fakeItems(): List<MediaItem> =
    (1..ITEM_COUNT).map { i -> MediaItem(id = "s$i", kind = MediaKind.SERIES, name = "剧集$i") }

/**
 * 复现用的假 ViewModel。挂在 "library" 这个 `NavBackStackEntry` 上——真实
 * [dev.insua.jellycast.feature.library.LibraryViewModel] 的分页数据也是这样持有的。
 *
 * `instanceId` + [Log] 是本任务要求的"验证机制,而不是断言机制"的具体做法:报告里贴的
 * create/cleared 日志行,就是"ViewModel 是否被重建"这件事的直接证据,不是从代码读出来的推测。
 */
// public(不是 private/internal)——androidx.lifecycle 的 ViewModelProvider 用反射
// `Constructor.newInstance()` 构造它,反射调用方在另一个包(androidx.lifecycle.viewmodel.internal)
// 里,访问不了包内私有可见度的类。
class FakeLibraryViewModel : ViewModel() {

    val instanceId: Int = INSTANCE_COUNTER++

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(
        LibraryUiState(tab = LibraryTab.SERIES, series = PageState(isLoading = true)),
    )
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        Log.i(TAG, "FakeLibraryViewModel#$instanceId created (items=0, isLoading=true)")
        scope.launch {
            delay(LOAD_DELAY_MS)
            _uiState.value = LibraryUiState(
                tab = LibraryTab.SERIES,
                series = PageState(items = fakeItems(), totalCount = ITEM_COUNT),
            )
            Log.i(TAG, "FakeLibraryViewModel#$instanceId loaded ${fakeItems().size} items")
        }
    }

    override fun onCleared() {
        Log.i(TAG, "FakeLibraryViewModel#$instanceId cleared")
        scope.cancel()
        super.onCleared()
    }

    companion object {
        @Volatile
        private var INSTANCE_COUNTER = 1
    }
}

/** "library" 目的地:真正的 [LibraryScreenContent](纯函数入口),数据来自挂在这个
 *  `NavBackStackEntry` 上的 [FakeLibraryViewModel]。 */
@Composable
private fun LibraryDestination(navController: NavHostController) {
    val vm: FakeLibraryViewModel = viewModel()
    val uiState by vm.uiState.collectAsState()

    // 每次"已加载条目数"或 VM 实例变化都记一行日志——报告里用它证明"首次测量时 item 数是 0"
    // 这件事(而不是凭代码猜)。
    LaunchedEffect(vm.instanceId, uiState.visible.items.size) {
        Log.i(
            TAG,
            "LibraryDestination composed: vm=#${vm.instanceId} " +
                "items=${uiState.visible.items.size} isLoading=${uiState.visible.isLoading}",
        )
    }

    LibraryScreenContent(
        uiState = uiState,
        onQueryChange = {},
        onSelectTab = {},
        onLoadNextPage = {},
        onRetry = {},
        onSeriesClick = { seriesId -> navController.navigate("detail/$seriesId") },
        onPlay = {},
    )
}

/**
 * 手搭的最小 `NavHost`,尽量贴近 [JellyCastNavHost] 的导航选项:
 * - "home" → "library":`popUpTo` 的目标固定是起点 "home"(对应生产环境的 `Routes.HOME`),
 *   不是目标 tab 自己——这一点必须原样复刻,否则 `popUpTo` 找不到目标、整个复现的前提
 *   (entry 被弹出销毁)就不成立。
 * - "library" → "detail/{id}":与 [JellyCastNavHost.kt] 第 190 行的 `onSeriesClick` 完全一样,
 *   不带任何特殊 `NavOptions`。
 *
 * **与 [JellyCastNavHost.kt] 第 266 行的唯一差异:少了 `restoreState = true`。**
 * [JellyCastNavHost.kt] 原文是
 * `popUpTo(Routes.HOME) { saveState = true } + launchSingleTop = true + restoreState = true`。
 * 逐字复刻这三者、在"从 library 的子页面(detail)点回 library 自己"这个场景下实测触发
 * `navController.navigate(...)` 的一个彻底 no-op(不抛异常,`currentBackStack` 真的不变,
 * 用 `LaunchedEffect(currentRoute)` 订阅验证过)——这本身很可能是另一个独立缺陷,细节见
 * `task-5-report.md`,不在本任务范围内处理。为了仍然能测到"返回后滚动位置"这件事,这里去掉了
 * `restoreState = true`,保留 `popUpTo(saveState=true)` + `launchSingleTop`——这仍然完整保留了
 * 首要嫌疑 (a) 的核心机制:弹出并重建 "library" 这个 `NavBackStackEntry`(其 `ViewModelStore`
 * 随之清空)。
 */
@Composable
private fun TestNavRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        Log.i(
            TAG,
            "backstack changed: currentRoute=$currentRoute stack=" +
                navController.currentBackStack.value.map { it.destination.route },
        )
    }

    Scaffold(
        bottomBar = {
            Button(
                onClick = {
                    Log.i(TAG, "bottom nav clicked, currentRoute=$currentRoute")
                    if (currentRoute != "library") {
                        // 与 JellyCastNavHost.kt:266 相同,唯一差异是没有 restoreState = true——
                        // 见本文件顶部 KDoc 和 TestNavRoot 的 KDoc 里对这处偏差的说明。
                        navController.navigate("library") {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier.testTag(BOTTOM_NAV_TAG),
            ) { Text("媒体库") }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable("home") { Text("首页", modifier = Modifier.testTag("test_home_screen")) }
            composable("library") { LibraryDestination(navController = navController) }
            composable("detail/{seriesId}") { entry ->
                val id = entry.arguments?.getString("seriesId").orEmpty()
                Text("详情 $id", modifier = Modifier.testTag(DETAIL_TAG))
            }
        }
    }
}

/**
 * 共用的测试驱动:从 "home" 点底部导航栏第一次进入 "library"(与真实首次进 tab 同一条路径)
 * → 等数据到货 → 滚到 [targetIndex] → 点该处的条目进详情页。
 */
private fun ComposeTestRule.enterLibraryAndOpenDetailAt(targetIndex: Int) {
    onNodeWithTag(BOTTOM_NAV_TAG).performClick()
    waitForIdle()
    waitForLibraryContentSettled()

    onNode(hasScrollToIndexAction()).performScrollToIndex(targetIndex)
    waitForIdle()

    val before = firstVisibleItemIndex()
    Log.i(TAG, "[准备] 滚动到 $targetIndex 后 firstVisibleItemIndex=$before")
    check(before >= MIN_ACCEPTABLE_INDEX) {
        "前置条件失败:滚动到 index=$targetIndex 后 firstVisibleItemIndex=$before," +
            "期望 >= $MIN_ACCEPTABLE_INDEX(测试本身没能先把列表滚下去,后面的断言没有意义)"
    }

    onNodeWithTag(LibraryScreenTestTags.item("s${targetIndex + 1}")).performClick()
    waitForIdle()
    onNodeWithTag(DETAIL_TAG).assertExists()
}

/** 等到"首屏转圈"消失、且"空态文案"不存在——用生产代码本来就有的两个 testTag 判定内容真的
 *  到货了,不依赖任何具体某一条 item 是否可见(返回后滚动位置可能仍在中部,"s1" 未必可见)。 */
private fun ComposeTestRule.waitForLibraryContentSettled() {
    waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithTagCount(LibraryScreenTestTags.LOADING_INITIAL) == 0 &&
            onAllNodesWithTagCount(LibraryScreenTestTags.EMPTY_STATE) == 0
    }
}

private fun ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size

/**
 * 拿 `firstVisibleItemIndex` 的办法:[LibraryScreenContent] 内部的 `gridState` 是私有实现
 * 细节(`key(tab, isSearching) { rememberLazyGridState() }`,见 `LibraryScreen.kt:227`),
 * 不通过参数暴露出来——把它提出来给测试直接持有,需要改 [LibraryScreenContent] 的函数签名,
 * 这属于生产代码,本任务明确不许碰。
 *
 * 改用从语义树读:按 id 升序逐个问"这个 item 是否显示在屏幕上",第一个命中的就是当前的
 * "第一个可见项"——这与 `LazyGridState.firstVisibleItemIndex` 想回答的是同一个问题
 * (哪个是当前视口里最靠前的条目),而且更贴近用户真实感知到的"列表有没有滚回顶部"。
 */
private fun ComposeTestRule.firstVisibleItemIndex(count: Int = ITEM_COUNT): Int {
    for (i in 1..count) {
        val displayed = try {
            onNodeWithTag(LibraryScreenTestTags.item("s$i")).assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
        if (displayed) return i - 1
    }
    return -1
}
