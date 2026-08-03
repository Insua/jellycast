package dev.insua.jellycast.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.designsystem.JellyCastTheme
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 2(设计文档 §2):首页顶部合并为一行。改动前第一个分区标题(「继续收听」)之上压着
 * 状态栏 inset + 8dp padding + 64dp `TopAppBar` + 48dp `TabRow`,合计约 144dp——其中
 * `TopAppBar` 整整占一行,却只装了一个用户早就知道的静态应用名和两个图标。
 *
 * 外壳照抄 `HomeScreenInsetTest`:同一个 edge-to-edge 真机复现条件(App `targetSdk` 36,
 * Android 15/API 35+ 强制 edge-to-edge,状态栏 inset 才非零)——手上模拟器是 API 34,默认不是
 * edge-to-edge、inset 恒为 0,所以同样显式 `setDecorFitsSystemWindows(false)`。
 *
 * 量的是:「继续收听」这个分区标题的顶部 y 坐标,减去内容根的顶部 y 坐标——这段距离就是顶部
 * chrome 的总高度,不关心中间具体由哪些控件组成。
 *
 * `BASELINE_PX` 不是设计文档估算值,是**改动前**实测出来的:在本文件删掉高度断言、只留一个
 * `assertTrue("...", false)` 把 `measureTopChromeHeightPx()` 的返回值打到失败信息里,对着改动前
 * 的 `HomeScreen.kt` 跑
 * `./gradlew :feature:home:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.feature.home.HomeTopChromeHeightTest`,
 * 在 Pixel 3a API 34 模拟器(440dpi,density≈2.75)上量出 396.0px(≈144dp,与设计文档 §2 的
 * 估算吻合)。
 */
@RunWith(AndroidJUnit4::class)
class HomeTopChromeHeightTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun uiState() = HomeUiState(
        isLoading = false,
        sections = listOf(
            HomeSection(HomeSectionKind.RESUME, "继续收听", List(4) { item("r$it") }),
        ),
    )

    private fun item(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = "示例条目 $id")

    /** 照抄 `HomeScreenInsetTest.setEdgeToEdgeContent`:同一套真机复现条件。 */
    private fun setEdgeToEdgeContent() {
        composeTestRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                Scaffold { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        HomeScreenContent(uiState = uiState(), onItemClick = { _, _ -> })
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun measureTopChromeHeightPx(): Float {
        setEdgeToEdgeContent()
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val title = composeTestRule.onNodeWithText("继续收听").fetchSemanticsNode()
        return title.boundsInRoot.top - root.boundsInRoot.top
    }

    @Test
    fun 顶部chrome高度不超过基线的六成() {
        val actual = measureTopChromeHeightPx()
        assertTrue(
            "首页顶部 chrome 高度 ${actual}px,超过基线 ${BASELINE_PX}px 的 60%。" +
                "设计文档 §2:去掉只装了一个静态应用名的 TopAppBar,把图标并进标签行。",
            actual <= BASELINE_PX * 0.6,
        )
    }

    /** 省了空间不能把功能一并省掉:搜索入口必须还在、还能点。 */
    @Test
    fun 搜索按钮仍存在且可点() {
        setEdgeToEdgeContent()
        composeTestRule.onNodeWithTag(HomeScreenTestTags.SEARCH_BUTTON)
            .assertIsDisplayed()
            .performClick()
    }

    /** 省了空间不能把功能一并省掉:账户入口必须还在、还能点。 */
    @Test
    fun 账户按钮仍存在且可点() {
        setEdgeToEdgeContent()
        composeTestRule.onNodeWithTag(HomeScreenTestTags.ACCOUNT_BUTTON)
            .assertIsDisplayed()
            .performClick()
    }
}

/**
 * 改动前实测的基线(px,Pixel 3a API 34 模拟器)。见本文件顶部 KDoc:如何量出来的。
 */
private const val BASELINE_PX = 396.0
