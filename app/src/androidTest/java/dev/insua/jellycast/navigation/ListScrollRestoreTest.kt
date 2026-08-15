package dev.insua.jellycast.navigation

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * 复现/回归任务(Task 5 起,详见 `.superpowers/sdd/2026-08-15-high-bitrate-playback-and-scroll-restore/`
 * 下 task-5/task-6 的 brief 与 report)。**Fix round 2 之后的版本。**
 *
 * 已排除"忘了用 rememberSaveable"——四个列表用的都是 saveable 支撑的滚动状态
 * ([dev.insua.jellycast.feature.library.LibraryScreen] 的 `gridState`)。这里手搭一个最小
 * `NavHost`,尽量贴近生产的真实结构:
 *
 * - **三个 tab**(在听/媒体库/设置),路由字符串直接复用 [Routes] 里的常量/工厂函数——不是
 *   照着敲一遍长得像的字符串,是同一份真值来源。
 * - **底部导航栏的决策**直接调用生产纯函数 [tabNavAction](`TabNavAction.kt`),不再手写一份
 *   `when` 分支去"模拟"它——Fix round 2 复审的 Important 5 明确指出:round 1 的变异验证只打在
 *   测试镜像上,生产代码从来没被验证过。现在镜像和生产用的是**同一个函数**,不存在"镜像跟生产
 *   代码不同步"的问题。
 * - **`returnToHome` 的决策**同样调用生产纯函数 [restorableTabOwner],只有 `popBackStack`/
 *   `navigate` 这两行必须触碰真实 `NavController` 的部分留在这里手写(无法抽成纯函数)。
 * - **子路由用真实的模式**:`library/series/{seriesId}`、`library/view/{libraryId}`、
 *   `settings` 下挂一个不带 `"settings/"` 前缀的 `servers`——round 1 的镜像只有一个
 *   `detail/{seriesId}`,和生产的路由表形状不一样,所以看不见 Critical 2(`library/view` 从
 *   首页直连,中途不经过 `"library"`)和 Important 4(`servers` 不该被当成 `settings` 的一部分
 *   存起来)。
 * - **`returnToHome` 触发按钮是顶层常驻的**,不再塞在某个具体子页面里——生产里它是
 *   `JellyCastNavHost` 顶层的 `LaunchedEffect(returnToHome)`,不依赖当前站在哪个目的地上,
 *   这样才能从"库详情页"以外的地方(比如 `library/view`、`servers`)也触发它。
 *
 * `FakeLibraryViewModel` / `FakeLibraryViewViewModel` 的数据**异步**到货(模拟真实 ViewModel 要
 * 走一次网络往返这件事),`instanceId` + [Log] 把"ViewModel 有没有被重建/清除"直接打进 logcat,
 * 不是从代码读出来的推测。
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
     * 缺陷 A(详情页点回自己所在 tab 是 no-op)修好之后的正确行为:退回 tab 根,`popBackStack`
     * 保住的是**同一个** `NavBackStackEntry`,`library` 的 entry id 点击前后必须一致——证明保住
     * 的是原来那个条目、连同它的 `ViewModelStore`/`SaveableStateHolder`,不是重新 push 出来的
     * 新条目。
     */
    @Test
    fun 从详情页点回自己所在tab时退回tab根且保住同一个条目() {
        var lastBackstack: List<BackStackEntrySnapshot> = emptyList()
        composeTestRule.setContent {
            TestNavRoot(onBackstackChanged = { lastBackstack = it })
        }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)
        val beforeClick = lastBackstack
        Log.i(TAG, "[退回 tab 根探测] 点击前 backstack=$beforeClick")
        val libraryIdBefore = beforeClick.single { it.route == Routes.LIBRARY }.id

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        composeTestRule.waitForIdle()

        val afterClick = lastBackstack
        Log.i(TAG, "[退回 tab 根探测] 点击后 backstack=$afterClick")

        assert(afterClick.last().route == Routes.LIBRARY) {
            "点『媒体库』tab 后应当退回它的根(library),实际最终路由=${afterClick.last().route}"
        }
        composeTestRule.onNodeWithTag(DETAIL_TAG).assertDoesNotExist()

        val libraryIdAfter = afterClick.single { it.route == Routes.LIBRARY }.id
        assert(libraryIdAfter == libraryIdBefore) {
            "退回「媒体库」应保住同一个 NavBackStackEntry(id 不变),实际点击前 id=$libraryIdBefore," +
                "点击后 id=$libraryIdAfter —— 说明这是重新 push 的新条目,ViewModelStore/滚动状态会丢。"
        }
    }

    /**
     * **Fix round 2 —— Critical 1 的回归用例。**
     *
     * Fix round 1 里 `BottomNavBar` 对"在听"(`HOME`)tab 无条件走
     * `navController.popBackStack(tab.route, inclusive = false)` 的返回值当条件——`HOME` 是起始
     * 目的地,永远在栈底,这个调用对**任何**当前路由都会"成功"(把它上面的一切弹光,不带
     * `saveState`)。从库详情页点「在听」会被误判成"退回 HOME 的根",把「媒体库」的整段状态
     * (`library` + `library/series/{id}`)直接销毁——复审实测:滚动到 40 → 点「在听」→ 点
     * 「媒体库」→ `firstVisibleItemIndex=0`,`ViewModelStore` 被清空,列表从第一页重新拉取。
     *
     * 修好之后(`tabNavAction` 按路由前缀判断归属,`HOME` 没有任何 `"home/"` 前缀的子路由):
     * 从库详情页点「在听」落在 [TabNavAction.SwitchTab] 分支,带 `saveState=true`,「媒体库」的
     * 状态被保留。切回「媒体库」时,`restoreState` 恢复的是离开时那一整段(`library` +
     * `library/series/{id}`),**先落在详情页**——这是正常的跨 tab 切换语义(复审明确认可:
     * "Landing first on the detail screen they were on, with the grid beneath it intact,
     * satisfies this — that is normal tab behaviour"),退一步才看得到列表本身,验证滚动位置
     * 没丢。
     */
    @Test
    fun 从库详情页切到别的tab再切回媒体库时状态不丢() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_HOME_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_TAG).assertExists()

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        composeTestRule.waitForIdle()
        // 正常的跨 tab 恢复语义:落在离开时所在的子页面(详情页),不是列表本身。
        composeTestRule.onNodeWithTag(DETAIL_TAG).assertExists()

        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.waitForLibraryContentSettled()

        val after = composeTestRule.firstVisibleItemIndex()
        Log.i(TAG, "[Critical1 回归] afterIndex=$after")
        assert(after >= MIN_ACCEPTABLE_INDEX) {
            "从库详情页切到别的 tab 再切回来,详情页底下的媒体库列表滚动位置应保持" +
                "(firstVisibleItemIndex >= $MIN_ACCEPTABLE_INDEX),实际 firstVisibleItemIndex=$after"
        }
    }

    /**
     * `returnToHome`(播放序列结束回首页)修好之后的正确行为:从库详情页触发,先退到「媒体库」
     * 根(子页面被丢弃,不 `saveState`——它已经播完了,不需要以后自动弹回那一集的详情页),再对
     * 「媒体库」根做 `popUpTo(HOME){saveState=true}`。之后再点『媒体库』tab,恢复出来的是列表
     * 本身,滚动位置保持。
     */
    @Test
    fun 播放序列结束返回首页后再次进入媒体库滚动位置保持() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.enterLibraryAndOpenDetailAt(SCROLL_TARGET_INDEX)

        composeTestRule.onNodeWithTag(END_PLAYBACK_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_TAG).assertExists()

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

    /**
     * **Fix round 2 —— Critical 2 / Important 3 的回归用例。**
     *
     * 首页「我的媒体」库卡片直接跳 `Routes.libraryView(libraryId)`(`JellyCastNavHost.kt` 的
     * `onLibraryClick`),中途从来没有把 `"library"` 本身压过栈。Fix round 1 的 `returnToHome`
     * 写的是 `navController.popBackStack(Routes.LIBRARY, inclusive = false)`——在这条路径上
     * `"library"` 根本不在栈里,这一行返回 `false`、不改动任何东西,后续
     * `navigate(HOME){popUpTo(HOME){saveState=true};restoreState=true}` 的 `restoreState` 又把
     * `library/view/{libraryId}` 命中的那一段原样恢复——复审实测:点击前后 `stack=[null, home,
     * library/view/{libraryId}]` 逐字节不变,界面停在原地,`returnToHome` 变成彻底的 no-op。
     *
     * 修好之后:`restorableTabOwner` 在栈里找不到 `LIBRARY`/`SETTINGS`,老实退到 `HOME` 自己,
     * `popBackStack(HOME, inclusive = false)` 把 `library/view/{libraryId}` 干净弹掉(默认不
     * `saveState`)——不会变成一段以后没有任何 `navigate()` 会去恢复、`ViewModelStore` 永远出
     * 不来的僵尸状态(Important 3),而且这次真的落地在首页。
     */
    @Test
    fun 从首页直接进按库浏览页播放结束返回首页时正确落地() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.onNodeWithTag(HOME_OPEN_LIBRARY_VIEW_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIBRARY_VIEW_TAG).assertExists()

        composeTestRule.onNodeWithTag(END_PLAYBACK_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HOME_TAG).assertExists()
        composeTestRule.onNodeWithTag(LIBRARY_VIEW_TAG).assertDoesNotExist()
    }

    /**
     * **Fix round 2 —— Important 4 的回归用例。**
     *
     * 设置 tab 下的「管理服务器」(`Routes.SERVERS`)不在 `"settings/"` 前缀下(它同时也是登录前
     * 的顶层路由),也不在 `Routes.CHROME_ROUTES` 里。Fix round 1 的 `returnToHome` 对
     * `library` 之外的分支不做任何"退到根"的处理,直接对当前路由(`servers`)做
     * `popUpTo(HOME){saveState=true}`——弹出的 `[settings, servers]` 整段被存成一个整体,之后点
     * 「设置」tab、`restoreState` 命中同一个 key,恢复出来的是**整段**,用户看见的是服务器
     * 列表,不是设置页,底部 tab 栏也跟着消失(`servers ∉ CHROME_ROUTES`)。
     *
     * 修好之后:`restorableTabOwner` 从栈顶往栈底找,`settings` 比栈底的 `home` 更靠近栈顶,
     * 先命中 `settings`,`popBackStack(settings, inclusive = false)` 把 `servers` 弹掉(默认不
     * `saveState`),不会混进被保留的那一段里。
     */
    @Test
    fun 设置管理服务器播放结束返回首页后点设置回到设置页() {
        composeTestRule.setContent { TestNavRoot() }

        composeTestRule.onNodeWithTag(BOTTOM_NAV_SETTINGS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SETTINGS_TAG).assertExists()

        composeTestRule.onNodeWithTag(SETTINGS_MANAGE_SERVERS_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(SERVERS_TAG).assertExists()

        composeTestRule.onNodeWithTag(END_PLAYBACK_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HOME_TAG).assertExists()

        composeTestRule.onNodeWithTag(BOTTOM_NAV_SETTINGS_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SETTINGS_TAG).assertExists()
        composeTestRule.onNodeWithTag(SERVERS_TAG).assertDoesNotExist()
    }
}

internal const val TAG = "ListScrollRestoreTest"
internal const val ITEM_COUNT = 60
internal const val SCROLL_TARGET_INDEX = 40
internal const val MIN_ACCEPTABLE_INDEX = 35

/** 模拟真实 [dev.insua.jellycast.feature.library.LibraryViewModel] 要走一次网络往返——
 *  数据不会在 ViewModel 构造的同一帧就绪。 */
internal const val LOAD_DELAY_MS = 50L

internal const val BOTTOM_NAV_HOME_TAG = "test_bottom_nav_home"
internal const val BOTTOM_NAV_TAG = "test_bottom_nav_library"
internal const val BOTTOM_NAV_SETTINGS_TAG = "test_bottom_nav_settings"
internal const val DETAIL_TAG = "test_detail_screen"
internal const val HOME_TAG = "test_home_screen"
internal const val HOME_OPEN_LIBRARY_VIEW_TAG = "test_home_open_library_view_button"
internal const val LIBRARY_VIEW_TAG = "test_library_view_screen"
internal const val SETTINGS_TAG = "test_settings_screen"
internal const val SETTINGS_MANAGE_SERVERS_TAG = "test_settings_manage_servers_button"
internal const val SERVERS_TAG = "test_servers_screen"
internal const val END_PLAYBACK_TAG = "test_end_playback_return_home"

/** 三个 tab 根,和生产 `BOTTOM_TABS`(`JellyCastNavHost.kt`,file-private,同包也拿不到)
 *  代表同一组路由——生产那份没法从这里引用,但值本身来自同一个 [Routes]。 */
internal val TAB_ROUTES = listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)

internal fun fakeItems(): List<MediaItem> =
    (1..ITEM_COUNT).map { i -> MediaItem(id = "s$i", kind = MediaKind.SERIES, name = "剧集$i") }

/**
 * 复现用的假 ViewModel。挂在 [Routes.LIBRARY] 这个 `NavBackStackEntry` 上——真实
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

/**
 * 挂在 [Routes.LIBRARY_VIEW_PATTERN] 这个 `NavBackStackEntry` 上的假 ViewModel——只需要证明
 * "活着 / 被清了",不需要完整的分页 UI。用来验证 Critical 2/Important 3:从首页直接进这个页面
 * (中途不经过 `"library"`)时,`returnToHome` 必须让它被正常 `cleared`(销毁),不能把它悬在
 * `backStackMap` 里出不来。
 */
class FakeLibraryViewViewModel : ViewModel() {

    val instanceId: Int = INSTANCE_COUNTER++

    init {
        Log.i(TAG, "FakeLibraryViewViewModel#$instanceId created")
    }

    override fun onCleared() {
        Log.i(TAG, "FakeLibraryViewViewModel#$instanceId cleared")
        super.onCleared()
    }

    companion object {
        @Volatile
        private var INSTANCE_COUNTER = 1
    }
}

/** [Routes.LIBRARY] 目的地:真正的 [LibraryScreenContent](纯函数入口),数据来自挂在这个
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
        onSeriesClick = { seriesId -> navController.navigate(Routes.seriesDetail(seriesId)) },
        onPlay = {},
    )
}

/** [Routes.SERIES_DETAIL_PATTERN] 目的地。 */
@Composable
internal fun SeriesDetailDestination(seriesId: String) {
    Text("详情 $seriesId", modifier = Modifier.testTag(DETAIL_TAG))
}

/** [Routes.LIBRARY_VIEW_PATTERN] 目的地——首页「我的媒体」库卡片直连,中途不经过
 *  [Routes.LIBRARY]。 */
@Composable
internal fun LibraryViewDestination(libraryId: String) {
    val vm: FakeLibraryViewViewModel = viewModel()
    Text("按库浏览 $libraryId (vm#${vm.instanceId})", modifier = Modifier.testTag(LIBRARY_VIEW_TAG))
}

/** [Routes.HOME] 目的地——多带一个「我的媒体」库卡片按钮,逐字复刻生产
 *  `onLibraryClick = { libraryId -> navController.navigate(Routes.libraryView(libraryId)) }`。 */
@Composable
internal fun HomeDestination(navController: NavHostController) {
    Column {
        Text("首页", modifier = Modifier.testTag(HOME_TAG))
        Button(
            onClick = { navController.navigate(Routes.libraryView("lib1")) },
            modifier = Modifier.testTag(HOME_OPEN_LIBRARY_VIEW_TAG),
        ) { Text("我的媒体") }
    }
}

/** [Routes.SETTINGS] 目的地——多带一个「管理服务器」按钮,逐字复刻生产
 *  `onManageServers = { navController.navigate(Routes.SERVERS) }`。 */
@Composable
internal fun SettingsDestination(navController: NavHostController) {
    Column {
        Text("设置", modifier = Modifier.testTag(SETTINGS_TAG))
        Button(
            onClick = { navController.navigate(Routes.SERVERS) },
            modifier = Modifier.testTag(SETTINGS_MANAGE_SERVERS_TAG),
        ) { Text("管理服务器") }
    }
}

/** [Routes.SERVERS] 目的地——不在 `"settings/"` 前缀下,也不在 [Routes.CHROME_ROUTES] 里。 */
@Composable
internal fun ServersDestination() {
    Text("服务器列表", modifier = Modifier.testTag(SERVERS_TAG))
}

/** 一次返回栈快照里的一条记录——路由 + entry id,用来区分"内容看着一样但其实是全新条目"
 *  和"真的是同一个条目"。 */
internal data class BackStackEntrySnapshot(val route: String?, val id: String)

/**
 * 手搭的最小 `NavHost`,路由结构、决策函数都直接复用生产的 [Routes] / [tabNavAction] /
 * [restorableTabOwner]——见本文件顶部类 KDoc 的说明。
 *
 * [onBackstackChanged] 是测试专用的观测口——不是生产代码的一部分,只用来在断言里核实返回栈的
 * 真实内容(路由 + entry id)。
 */
@Composable
internal fun TestNavRoot(onBackstackChanged: (List<BackStackEntrySnapshot>) -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(currentRoute) {
        val stack = navController.currentBackStack.value.map {
            BackStackEntrySnapshot(it.destination.route, it.id)
        }
        Log.i(TAG, "backstack changed: currentRoute=$currentRoute stack=$stack")
        onBackstackChanged(stack)
    }

    Scaffold(
        bottomBar = {
            Column {
                // 逐字复刻 JellyCastNavHost.kt 里 `LaunchedEffect(returnToHome)`(修好之后的
                // 版本)——生产里这是顶层效应,不依赖当前具体目的地,所以这里也放在顶层常驻可点,
                // 不塞进某个具体子页面,这样才能从"库详情页"以外的地方也触发它
                // (比如 library/view、servers)。
                Button(
                    onClick = {
                        val stackRoutes = navController.currentBackStack.value.map { it.destination.route }
                        val owner = restorableTabOwner(stackRoutes, TAB_ROUTES)
                        if (owner != null) navController.popBackStack(owner, inclusive = false)
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.testTag(END_PLAYBACK_TAG),
                ) { Text("结束播放返回首页") }

                Row {
                    listOf(
                        Triple(Routes.HOME, BOTTOM_NAV_HOME_TAG, "在听"),
                        Triple(Routes.LIBRARY, BOTTOM_NAV_TAG, "媒体库"),
                        Triple(Routes.SETTINGS, BOTTOM_NAV_SETTINGS_TAG, "设置"),
                    ).forEach { (route, tag, label) ->
                        // 逐字复刻 JellyCastNavHost.kt 里 `BottomNavBar` 的 onClick(修好之后的
                        // 版本)——直接调用生产纯函数 tabNavAction,不是重新手写一份"像"它的逻辑。
                        Button(
                            onClick = {
                                when (val action = tabNavAction(currentRoute, route)) {
                                    TabNavAction.None -> Unit
                                    is TabNavAction.PopToTabRoot ->
                                        navController.popBackStack(action.route, inclusive = false)
                                    is TabNavAction.SwitchTab -> navController.navigate(action.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            modifier = Modifier.testTag(tag),
                        ) { Text(label) }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.HOME) { HomeDestination(navController = navController) }
            composable(Routes.LIBRARY) { LibraryDestination(navController = navController) }
            composable(Routes.SERIES_DETAIL_PATTERN) { entry ->
                val seriesId = entry.arguments?.getString("seriesId").orEmpty()
                SeriesDetailDestination(seriesId = seriesId)
            }
            composable(Routes.LIBRARY_VIEW_PATTERN) { entry ->
                val libraryId = entry.arguments?.getString("libraryId").orEmpty()
                LibraryViewDestination(libraryId = libraryId)
            }
            composable(Routes.SETTINGS) { SettingsDestination(navController = navController) }
            composable(Routes.SERVERS) { ServersDestination() }
        }
    }
}

/**
 * 共用的测试驱动:从 [Routes.HOME] 点底部导航栏第一次进入 [Routes.LIBRARY](与真实首次进 tab
 * 同一条路径)→ 等数据到货 → 滚到 [targetIndex] → 点该处的条目进详情页。
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
