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
 * edge-to-edge —— 那时 `WindowInsets.statusBars` 才是非零,外层 `Scaffold`(把系统栏 inset
 * 计入内容内边距)和 Material3 `TopAppBar`(默认应用 `TopAppBarDefaults.windowInsets`,含状态栏)
 * 就会各加一次,顶部凭空多出一个状态栏的高度。手上的模拟器是 API 34,默认**不是** edge-to-edge、
 * inset 恒为 0,直接跑复现不出来 —— 所以这里显式 `setDecorFitsSystemWindows(false)`,
 * 把用户真机上的那个条件搬进测试,再逐像素量。
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
    private var topBarStandardPx = 0
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
            topBarStandardPx = with(density) { STANDARD_TOP_BAR_HEIGHT.roundToPx() }
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
    fun 首页顶栏只消费一次状态栏inset() {
        setEdgeToEdgeContent()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.TOP_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HomeScreenTestTags.TAB_ROW).assertIsDisplayed()

        val topBar = composeTestRule.onNodeWithTag(HomeScreenTestTags.TOP_BAR).fetchSemanticsNode()
        val topBarTop = topBar.boundsInRoot.top
        val topBarHeight = topBar.size.height

        assertTrue(
            "测试前提没成立:状态栏 inset 是 0,说明窗口不是 edge-to-edge,量不出重复计算",
            statusBarPx > 0,
        )
        // Scaffold 已经把状态栏高度作为内容内边距推下来了——顶栏顶边就该在状态栏下面。
        assertTrue(
            "Scaffold 应当已经消费了状态栏 inset:顶栏顶边 ${topBarTop}px,状态栏 ${statusBarPx}px",
            topBarTop >= statusBarPx - 1f,
        )
        // 顶栏**自己**不该再垫一次:它的高度就该是 TopAppBar 的标准高度。
        assertTrue(
            "状态栏 inset 被算了两次:顶栏顶边已在 ${topBarTop}px(= 状态栏 ${statusBarPx}px)," +
                "顶栏自身高度却是 ${topBarHeight}px,比标准高度 ${topBarStandardPx}px 多出 " +
                "${topBarHeight - topBarStandardPx}px —— 和状态栏高度 ${statusBarPx}px 吻合",
            topBarHeight <= topBarStandardPx + TOLERANCE_PX,
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

/** Material3 `TopAppBar` 的标准容器高度(不含任何 window inset)。 */
private val STANDARD_TOP_BAR_HEIGHT = 64.dp

/** Material3 `NavigationBar` 的标准容器高度(不含任何 window inset)。 */
private val STANDARD_BOTTOM_BAR_HEIGHT = 80.dp

private const val BOTTOM_BAR_TAG = "app_shell_bottom_bar"

/** 留一点余量给 M3 内部的取整,但远小于系统栏高度,不会把重复计算放过去。 */
private const val TOLERANCE_PX = 8
