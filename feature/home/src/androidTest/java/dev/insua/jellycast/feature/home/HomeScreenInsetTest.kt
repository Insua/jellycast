package dev.insua.jellycast.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.insua.jellycast.designsystem.JellyCastTheme
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 2 的真机证据:**首页顶部的状态栏 inset 只能被消费一次,而且底部导航栏 inset 不受牵连。**
 *
 * 复现条件说明:App 的 `targetSdk` 是 36,在 Android 15(API 35)及以上的真机上系统**强制**
 * edge-to-edge —— 那时 `WindowInsets.statusBars` 才是非零,外层 `Scaffold` 会把系统栏 inset
 * 计入内容内边距。手上的模拟器是 API 34,默认**不是** edge-to-edge、inset 恒为 0,直接跑复现
 * 不出来 —— 所以这里显式 `setDecorFitsSystemWindows(false)`,把用户真机上的那个条件搬进测试,
 * 再逐像素量。
 *
 * ⚠️ 原本这里量的是 `HomeTopBar`(独立的 Material3 `TopAppBar`)——它默认自带
 * `TopAppBarDefaults.windowInsets`(含状态栏),会和 `Scaffold` 的内容内边距叠加、把状态栏
 * inset 算两次,靠"顶栏自身高度超过 TopAppBar 标准高度"抓到这个 bug。Task 2 把 `HomeTopBar`
 * 整个删掉、图标并进了 `TabRow` 所在的那一行(见 `HomeScreen.kt`)——但**这不代表双重计算这
 * 类 bug 不会再发生**:`Column` 内、`TabRow` 之上的任何地方(哪怕是以后不小心加的一行
 * `Modifier.statusBarsPadding()`)都可能再垫一次状态栏 inset,`TabRow` 自己不应用 window
 * inset 这件事只保证"它不会自己犯错",挡不住"它上面有人犯错"。
 *
 * 所以这里改为**双向**卡住首页第一行([HomeScreenTestTags.TAB_ROW])的顶边位置:既要
 * `>= statusBarPx`(`Scaffold` 必须已经让出状态栏 inset),也要`<= statusBarPx + 容差`
 * (`TabRow` 上面不能再有任何东西二次消费/叠加 inset)——只测下界测不出"content 内部又多垫了
 * 一次"这类 bug,只有双向夹住才能像原来测 `TopAppBar` 高度那样,抓住"内容根到 TabRow 之间的
 * 距离比状态栏 inset 本身更长"这件事。`TabRow` 自身高度不超标准高度作为次要断言保留。
 *
 * 外壳刻意照抄 `JellyCastNavHost`:`Scaffold` + `bottomBar`(迷你播放条 + 底部 tab 栏)+
 * `padding(padding)` 的内容区。顶部改动不能把底部搞坏,所以底部也一并断言。
 *
 * 断言用 [assertIsDisplayed] 而不是 `assertExists`:v3 的离线提示条就是"节点在语义树里、
 * 却被挤出视口",全绿的测试骗过了所有人。
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenInsetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun uiState() = HomeUiState(
        isLoading = false,
        sections = listOf(
            HomeSection(HomeSectionKind.RESUME, "继续收听", List(4) { item("r$it") }),
            HomeSection(HomeSectionKind.NEXT_UP, "下一集", List(4) { item("n$it") }),
        ),
    )

    private fun item(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = "示例条目 $id")

    private var statusBarPx = 0
    private var navBarPx = 0
    private var tabRowStandardPx = 0
    private var bottomBarStandardPx = 0

    /** 打开 edge-to-edge 并摆出和 `JellyCastNavHost` 同构的外壳。 */
    private fun setEdgeToEdgeContent() {
        composeTestRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        composeTestRule.setContent {
            val density = LocalDensity.current
            statusBarPx = WindowInsets.statusBars.getTop(density)
            navBarPx = WindowInsets.navigationBars.getBottom(density)
            tabRowStandardPx = with(density) { STANDARD_TAB_ROW_HEIGHT.roundToPx() }
            bottomBarStandardPx = with(density) { STANDARD_BOTTOM_BAR_HEIGHT.roundToPx() }
            JellyCastTheme(darkTheme = false) {
                Scaffold(bottomBar = { AppShellBottomBar() }) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        HomeScreenContent(uiState = uiState(), onItemClick = { _, _ -> })
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Composable
    private fun AppShellBottomBar() {
        Column(modifier = Modifier.testTag(BOTTOM_BAR_TAG)) {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("首页") },
                )
            }
        }
    }

    @Test
    fun 首页顶部只消费一次状态栏inset() {
        setEdgeToEdgeContent()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.TAB_ROW).assertIsDisplayed()

        val tabRow = composeTestRule.onNodeWithTag(HomeScreenTestTags.TAB_ROW).fetchSemanticsNode()
        val tabRowTop = tabRow.boundsInRoot.top
        val tabRowHeight = tabRow.size.height

        assertTrue(
            "测试前提没成立:状态栏 inset 是 0,说明窗口不是 edge-to-edge,量不出重复计算",
            statusBarPx > 0,
        )
        // Scaffold 已经把状态栏高度作为内容内边距推下来了——首页第一行(TabRow 所在的那一行)
        // 顶边就该紧贴在状态栏下面,下界原来就有。
        assertTrue(
            "Scaffold 应当已经消费了状态栏 inset:第一行顶边 ${tabRowTop}px,状态栏 ${statusBarPx}px",
            tabRowTop >= statusBarPx - 1f,
        )
        // ⚠️ 双向夹住才是原来那条测试真正守住的东西:下界只能抓到"Scaffold 没让出 inset",
        // 抓不到"content 内部(Column/TabRow 之上任何位置)又二次消费/叠加了一次 inset"——
        // 后者正是 v5 的 bug 形状,也是这里加上界的唯一理由。任何人在 TabRow 之上加一行
        // `Modifier.statusBarsPadding()`(或任何吃 window inset 的东西),第一行顶边就会比
        // 状态栏高度本身还靠下,这条上界必须能抓到。
        assertTrue(
            "状态栏 inset 被算了两次(或 TabRow 之上多了别的内边距):第一行顶边 ${tabRowTop}px," +
                "比状态栏高度 ${statusBarPx}px 多出 ${tabRowTop - statusBarPx}px,超过容差 ${TOLERANCE_PX}px",
            tabRowTop <= statusBarPx + TOLERANCE_PX,
        )
        // 次要断言:TabRow 不应用任何 window inset,它自身高度也不该超过标准高度。
        assertTrue(
            "TabRow 自身高度 ${tabRowHeight}px 超过标准高度 ${tabRowStandardPx}px + 容差",
            tabRowHeight <= tabRowStandardPx + TOLERANCE_PX,
        )
    }

    /**
     * 顶部的改动不能把底部搞坏:迷你播放条和底部 tab 栏在 `Scaffold` 的 `bottomBar` 里,
     * 导航栏 inset 必须**照旧生效**(由 `NavigationBar` 自己消费),按钮不能被系统导航栏压住。
     */
    @Test
    fun 底部导航栏inset依然生效() {
        setEdgeToEdgeContent()

        composeTestRule.onNodeWithTag(BOTTOM_BAR_TAG).assertIsDisplayed()
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val bottomBar = composeTestRule.onNodeWithTag(BOTTOM_BAR_TAG).fetchSemanticsNode()

        assertTrue("测试前提没成立:导航栏 inset 是 0", navBarPx > 0)
        assertTrue(
            "底部栏应当贴着窗口底边:底边 ${bottomBar.boundsInRoot.bottom}px,窗口高 ${root.size.height}px",
            bottomBar.boundsInRoot.bottom >= root.size.height - TOLERANCE_PX,
        )
        assertTrue(
            "底部导航栏 inset 没生效:底部栏高度 ${bottomBar.size.height}px," +
                "应当 >= 标准高度 ${bottomBarStandardPx}px + 导航栏 ${navBarPx}px",
            bottomBar.size.height >= bottomBarStandardPx + navBarPx - TOLERANCE_PX,
        )
    }

    /** 截一张真机图存到 /sdcard,作为报告里的 before/after 证据。 */
    @Test
    fun 首页顶部截图() {
        setEdgeToEdgeContent()
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val fd = automation.executeShellCommand("screencap -p /sdcard/jellycast_home_top.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                val buffer = ByteArray(4096)
                @Suppress("ControlFlowWithEmptyBody")
                while (stream.read(buffer) >= 0) {
                }
            }
        }
    }
}

/** Material3 `TabRow`(纯文本 Tab)的标准容器高度(不含任何 window inset)。 */
private val STANDARD_TAB_ROW_HEIGHT = 48.dp

/** Material3 `NavigationBar` 的标准容器高度(不含任何 window inset)。 */
private val STANDARD_BOTTOM_BAR_HEIGHT = 80.dp

private const val BOTTOM_BAR_TAG = "app_shell_bottom_bar"

/** 留一点余量给 M3 内部的取整,但远小于系统栏高度,不会把重复计算放过去。 */
private const val TOLERANCE_PX = 8
