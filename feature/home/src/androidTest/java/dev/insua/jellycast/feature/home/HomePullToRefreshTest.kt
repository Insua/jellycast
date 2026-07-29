package dev.insua.jellycast.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 3 的 Compose 层证据(设计文档 §2.3):下拉刷新指示器的显示/隐藏,以及手势确实能
 * 触发 [HomeScreenContent] 接到的 `onRefresh` 回调——纯逻辑单测([HomeViewModelTest]的
 * `refresh()` 系列)测不出"真正接线是不是对的",跟 [HomeScreenQueueTest] 同样的理由。
 *
 * ⚠️ 全程用 [assertIsDisplayed]/[assertIsNotDisplayed],不用 `assertExists()`——
 * `PullToRefreshBox` 的默认指示器不管有没有在刷新都"存在"于语义树里,只是通过位移/透明度
 * 决定看不看得见,`assertExists()` 测不出这个区别。这正是本项目 v3 离线横幅那个坑的同一种
 * 形状("存在但被挤出可视区,测试全绿、真机上什么都看不见"),CLAUDE.md 明令必须用
 * `assertIsDisplayed()`。
 */
@RunWith(AndroidJUnit4::class)
class HomePullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(id: String) = MediaItem(id = id, kind = MediaKind.EPISODE, name = "示例条目 $id")

    private fun uiState(isRefreshing: Boolean) = HomeUiState(
        isLoading = false,
        isRefreshing = isRefreshing,
        sections = listOf(HomeSection(HomeSectionKind.RESUME, "继续收听", List(6) { item("r$it") })),
    )

    @Test
    fun 刷新中指示器可见_未刷新时不可见() {
        var state by mutableStateOf(uiState(isRefreshing = false))
        composeTestRule.setContent {
            HomeScreenContent(uiState = state, onItemClick = { _, _ -> })
        }
        composeTestRule.waitForIdle()

        // 还没开始刷新:指示器根本不该被组合进树(不是"存在但不可见")。
        composeTestRule.onNodeWithTag(HomeScreenTestTags.PULL_REFRESH_INDICATOR).assertDoesNotExist()

        state = uiState(isRefreshing = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.PULL_REFRESH_INDICATOR).assertIsDisplayed()

        state = uiState(isRefreshing = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.PULL_REFRESH_INDICATOR).assertDoesNotExist()
    }

    @Test
    fun 下拉手势触发onRefresh回调() {
        var refreshed = false
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = uiState(isRefreshing = false),
                onItemClick = { _, _ -> },
                onRefresh = { refreshed = true },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.PULL_REFRESH_CONTAINER)
            .performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()

        assertTrue("下拉手势应该触发 onRefresh 回调", refreshed)
    }

    @Test
    fun 刷新期间已显示的内容不会被清空() {
        var state by mutableStateOf(uiState(isRefreshing = false))
        composeTestRule.setContent {
            HomeScreenContent(uiState = state, onItemClick = { _, _ -> })
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HomeScreenTestTags.item("r0")).assertIsDisplayed()

        // 刷新中——ViewModel 层的契约是"不清空已有内容",这里从 Compose 树上再确认一遍:
        // 只要 uiState.sections 没变,条目就该原样留在屏幕上,指示器转动不代表列表被清空重建。
        state = uiState(isRefreshing = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HomeScreenTestTags.item("r0")).assertIsDisplayed()
    }
}
