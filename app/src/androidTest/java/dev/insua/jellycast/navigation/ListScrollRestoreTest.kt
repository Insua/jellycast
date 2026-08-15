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
 * `.superpowers/sdd/2026-08-15-high-bitrate-playback-and-scroll-restore/task-5-brief.md`),
 * **Fix round 1 之后的版本**——上一轮复审(Critical 1/2/3)推翻了 round 0 的"从底部导航栏返回"
 * 用例:那个红是靠去掉 `restoreState = true` 造出来的,用户实际走不到那个状态(详见下方
 * [从详情页点回自己所在tab时navigate是no_op_已确认缺陷] 和 `task-5-report.md` 的 fix round 1 节)。
 *
 * 已排除"忘了用 rememberSaveable"——四个列表用的都是 saveable 支撑的滚动状态
 * ([dev.insua.jellycast.feature.library.LibraryScreen] 的 `gridState`)。这里手搭一个最小
 * `NavHost`,这一轮起**三个** `navigate` 调用全部逐字复刻生产代码,不再有任何 navOptions 层面
 * 的裁剪:
 *
 * - 底部导航栏(`JellyCastNavHost.kt:266`):`popUpTo(HOME){saveState=true} + launchSingleTop
 *   + restoreState=true`。
 * - 播放序列结束回首页(`JellyCastNavHost.kt:105-108`,`AppSessionViewModel.returnToHome`
 *   触发):`popUpTo(HOME){inclusive=true} + launchSingleTop`,**没有** `saveState`。
 * - 剧集条目点进详情(`JellyCastNavHost.kt:190`):无特殊 `NavOptions`。
 *
 * **这一轮验证出的两件事:**
 *
 * 1. **底部导航栏那个 no-op 是确认缺陷,不是待查疑点。** 见
 *    [从详情页点回自己所在tab时navigate是no_op_已确认缺陷]——从 "library" 的子页面点回
 *    "library" 自己的 tab 图标,`navController.navigate(...)` 正常返回、不抛异常,但返回栈
 *    (entry id、当前路由)真的一次都没变,用户仍然停在详情页。这与用户报的"回到了列表但滚动
 *    到顶部"是**不同的症状**,所以不能拿它当"滚动丢失"的复现,但它本身是一个真实、可达的缺陷。
 * 2. **一条可达的路径,真的复现出滚动丢失:** 播放序列结束 → 自动回首页
 *    (`popUpTo(HOME){inclusive=true}`,没有 `saveState`,"library" 连同它上面的 "detail" 被
 *    彻底销毁,不留任何可恢复的痕迹)→ 用户再次点『媒体库』tab。此时 "library" 已经不在返回栈
 *    上,`popUpTo(HOME)` 找不到东西可弹,`restoreState=true` 在 `backStackMap` 里也找不到
 *    "library" 的 id(它从来没有被 `saveState=true` 保存过)——于是这是一次彻头彻尾的全新
 *    push:全新 `NavBackStackEntry`、全新 `ViewModelStore`、全新 `LazyGridState`。见
 *    [播放序列结束返回首页后再次进入媒体库滚动位置丢失]。
 *
 * `FakeLibraryViewModel` 的数据**异步**到货(模拟真实 [LibraryViewModel.loadLibrary] 要走一次
 * 网络往返这件事),`instanceId` + [Log] 把"ViewModel 有没有被重建"直接打进 logcat,不是从代码
 * 读出来的推测。
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

    /**
     * **Fix round 1 —— 取代 round 0 里那个靠裁剪 navOptions 造红的"从底部导航栏返回"用例。**
     *
     * 复审用一个更小的探针在真实路由表上实测确认:生产代码原样的三个 `navOptions`
     * (`saveState=true` + `launchSingleTop=true` + `restoreState=true`)同时出现,且这次
     * `popUpTo` 弹出、`restoreState` 要恢复的目的地是**同一个**("library" 自己,不是切到另一个
     * tab)时,`navController.navigate(...)` 是彻底的 no-op——正常返回、不抛异常,但返回栈的
     * entry id、当前路由一个都没变(Probe A)。这不是本测试模型的产物,是 Navigation-Compose
     * 2.9.8 的真实行为,也是一个真实、用户可达的缺陷:**从剧集详情页点『媒体库』tab 图标,
     * 界面停在原地,什么反应都没有**。
     *
     * 这与用户原始报告的"滚动丢失"是不同症状,所以这里断言的是 no-op 本身(backstack 没变、
     * 仍在详情页),不再断言滚动位置——那条路径上滚动位置从来没有被检验的机会。
     */
    @Test
    fun 从详情页点回自己所在tab时navigate是no_op_已确认缺陷() {
        var lastBackstack: List<String?> = emptyList()
        composeTestRule.setContent {
            TestNavRoot(onBackstackChanged = { lastBackstack = it })
        }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)
        val beforeClick = lastBackstack
        Log.i(TAG, "[no-op 探测] 点击前 backstack=$beforeClick")

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        composeTestRule.waitForIdle()
        // 给它一个正常导航/过渡动画绰绰有余的窗口——如果这段时间里 backstack 真的变了,
        // lastBackstack 一定会被上面那个回调更新到。
        Thread.sleep(1_000)
        composeTestRule.waitForIdle()

        val afterClick = lastBackstack
        Log.i(TAG, "[no-op 探测] 点击后 backstack=$afterClick")

        assert(afterClick == beforeClick) {
            "预期是已确认的 no-op 缺陷(backstack 不变),但这次 backstack 变了:" +
                "点击前=$beforeClick,点击后=$afterClick —— 说明这条路径在这次环境下不是 no-op," +
                "需要重新核对复审的探针结论。"
        }
        // no-op 意味着仍然停在详情页,没有回到列表——这正是缺陷本身。
        composeTestRule.onNodeWithTag(DETAIL_TAG).assertExists()
    }

    /**
     * **Fix round 1 —— 复审指出的可达路径 (ii)。**
     *
     * `JellyCastNavHost.kt:105-108`:播放序列结束(整部剧播完/电影播完)时,
     * `AppSessionViewModel.returnToHome` 变 true,触发
     * `navigate(HOME){ popUpTo(HOME){inclusive=true}; launchSingleTop=true }`——**没有
     * `saveState`**,"library" 连同它上面的 "detail" 被彻底销毁,不留任何 `backStackMap` 记录。
     *
     * 用户流程:滚动媒体库列表 → 点进一部剧的详情(模拟"看了一集,期间播放序列结束") →
     * 触发这次 `returnToHome` → 回到首页 → 再点一次『媒体库』tab。此时 "library" 已经不在
     * 返回栈上,第二次点『媒体库』的 `popUpTo(HOME)` 找不到东西可弹、`restoreState=true` 在
     * `backStackMap` 里也找不到 "library" 的 id——是一次彻底全新的 push,全新 `ViewModelStore`、
     * 全新 `LazyGridState`。
     *
     * 断言:`firstVisibleItemIndex` 应该 `>= MIN_ACCEPTABLE_INDEX`(不应该丢),实测应为红。
     */
    @Test
    fun 播放序列结束返回首页后再次进入媒体库滚动位置丢失() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)

        // 逐字复刻 JellyCastNavHost.kt:105-108 的 returnToHome 效应。
        composeTestRule.onNodeWithTag(END_PLAYBACK_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_TAG).assertExists()

        // 再次点『媒体库』——这次 "library" 已经完全不在返回栈上了,不是 no-op 那条自我指涉的
        // 边界情况(见上面那条用例),navigate 会正常发生。
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitForLibraryContentSettled()

        val after = composeTestRule.firstVisibleItemIndex()
        Log.i(TAG, "[播放结束返回首页] afterIndex=$after")
        assert(after >= MIN_ACCEPTABLE_INDEX) {
            "播放序列结束回首页、再次进入媒体库后,滚动位置应保持(firstVisibleItemIndex >= " +
                "$MIN_ACCEPTABLE_INDEX),实际 firstVisibleItemIndex=$after"
        }
    }
}

internal const val TAG = "ListScrollRestoreTest"
internal const val ITEM_COUNT = 60
internal const val SCROLL_TARGET_INDEX = 40
internal const val MIN_ACCEPTABLE_INDEX = 35

/** 模拟真实 [dev.insua.jellycast.feature.library.LibraryViewModel] 要走一次网络往返——
 *  数据不会在 ViewModel 构造的同一帧就绪。 */
internal const val LOAD_DELAY_MS = 50L

internal const val BOTTOM_NAV_TAG = "test_bottom_nav_library"
internal const val DETAIL_TAG = "test_detail_screen"
internal const val HOME_TAG = "test_home_screen"
internal const val END_PLAYBACK_TAG = "test_end_playback_return_home"

internal fun fakeItems(): List<MediaItem> =
    (1..ITEM_COUNT).map { i -> MediaItem(id = "s$i", kind = MediaKind.SERIES, name = "剧集$i") }

/**
 * 复现用的假 ViewModel。挂在 "library" 这个 `NavBackStackEntry` 上——真实
 * [dev.insua.jellycast.feature.library.LibraryViewModel] 的分页数据也是这样持有的。
 *
 * `instanceId` + [Log] 是本任务要求的"验证机制,而不是断言机制"的具体做法:报告里贴的
 * create/cleared 日志行,就是"ViewModel 是否被重建"这件事的直接证据,不是从代码读出来的推测。
 *
 * `internal`(不是 private)——同包的 [ListScrollProcessDeathTest]([android]androidTest 里)复用
 * 这个假 ViewModel 去验证复审提出的候选 (i)(进程回收 / Activity 重建)。
 */
// public(不是 private)——androidx.lifecycle 的 ViewModelProvider 用反射
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
internal fun LibraryDestination(navController: NavHostController) {
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
 * 手搭的最小 `NavHost`。**Fix round 1 起,三个 `navigate` 调用全部逐字复刻生产代码**,不再有
 * 任何裁剪:
 *
 * - "home" → "library"(底部导航栏,对应 [JellyCastNavHost.kt] 第 266 行):
 *   `popUpTo(Routes.HOME){saveState=true} + launchSingleTop=true + restoreState=true`。
 * - "library"/"detail" → "home"(播放序列结束回首页,对应 [JellyCastNavHost.kt] 第 105-108 行):
 *   `popUpTo(Routes.HOME){inclusive=true} + launchSingleTop=true`,**没有** `saveState`。
 * - "library" → "detail/{id}"(对应 [JellyCastNavHost.kt] 第 190 行的 `onSeriesClick`):
 *   无特殊 `NavOptions`。
 *
 * [onBackstackChanged] 是测试专用的观测口——不是生产代码的一部分,只用来在断言里核实
 * "这次 navigate 到底有没有真的改变返回栈"([ListScrollRestoreTest.从详情页点回自己所在tab时navigate是no_op_已确认缺陷]
 * 需要这个信号来区分"no-op"和"真的导航了但滚动位置凑巧一样"这两种情况)。
 */
@Composable
internal fun TestNavRoot(onBackstackChanged: (List<String?>) -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        val stack = navController.currentBackStack.value.map { it.destination.route }
        Log.i(TAG, "backstack changed: currentRoute=$currentRoute stack=$stack")
        onBackstackChanged(stack)
    }

    Scaffold(
        bottomBar = {
            Button(
                onClick = {
                    Log.i(TAG, "bottom nav clicked, currentRoute=$currentRoute")
                    if (currentRoute != "library") {
                        // 逐字复刻 JellyCastNavHost.kt:266。
                        navController.navigate("library") {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
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
            composable("home") { Text("首页", modifier = Modifier.testTag(HOME_TAG)) }
            composable("library") { LibraryDestination(navController = navController) }
            composable("detail/{seriesId}") { entry ->
                val id = entry.arguments?.getString("seriesId").orEmpty()
                Text("详情 $id", modifier = Modifier.testTag(DETAIL_TAG))
                // 生产里 `returnToHome` 效应挂在 JellyCastNavHost 顶层(不依赖当前具体目的地,
                // 见 JellyCastNavHost.kt:101-110 的 LaunchedEffect(returnToHome))——这里放一个
                // 按钮在 "detail" 目的地上代替"播放序列结束"这个真实触发条件(整部剧/电影播完),
                // 但 navigate 调用本身逐字复刻,与从哪个目的地触发无关。
                Button(
                    onClick = {
                        // 逐字复刻 JellyCastNavHost.kt:105-108。
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.testTag(END_PLAYBACK_TAG),
                ) { Text("结束播放返回首页") }
            }
        }
    }
}

/**
 * 共用的测试驱动:从 "home" 点底部导航栏第一次进入 "library"(与真实首次进 tab 同一条路径)
 * → 等数据到货 → 滚到 [targetIndex] → 点该处的条目进详情页。
 */
internal fun ComposeTestRule.enterLibraryAndOpenDetailAt(targetIndex: Int) {
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
internal fun ComposeTestRule.waitForLibraryContentSettled() {
    waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithTagCount(LibraryScreenTestTags.LOADING_INITIAL) == 0 &&
            onAllNodesWithTagCount(LibraryScreenTestTags.EMPTY_STATE) == 0
    }
}

internal fun ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
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
internal fun ComposeTestRule.firstVisibleItemIndex(count: Int = ITEM_COUNT): Int {
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
