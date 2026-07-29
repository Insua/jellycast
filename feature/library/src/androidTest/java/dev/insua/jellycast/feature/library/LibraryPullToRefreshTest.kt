package dev.insua.jellycast.feature.library

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
import dev.insua.jellycast.model.PageState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 3 的 Compose 层证据(设计文档 §2.3):下拉刷新指示器的显示/隐藏,以及手势确实能
 * 触发 [LibraryScreenContent] 接到的 `onRefresh` 回调——纯逻辑单测([LibraryViewModelTest]的
 * `refresh()` 系列)测不出"真正接线是不是对的"。
 *
 * ⚠️ 全程用 [assertIsDisplayed]/[assertIsNotDisplayed],不用 `assertExists()`——理由与
 * `feature:home` 的 `HomePullToRefreshTest` 相同(见其 KDoc),也是 CLAUDE.md 明令的规则。
 */
@RunWith(AndroidJUnit4::class)
class LibraryPullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun mediaItem(id: String, kind: MediaKind = MediaKind.SERIES, name: String = id) =
        MediaItem(id = id, kind = kind, name = name)

    private fun uiState(isRefreshing: Boolean) = LibraryUiState(
        tab = LibraryTab.SERIES,
        series = PageState(items = List(6) { mediaItem("s$it") }, totalCount = 6, isLoading = false),
        isRefreshing = isRefreshing,
    )

    private fun setScreen(
        state: LibraryUiState,
        onRefresh: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibraryScreenContent(
                uiState = state,
                onQueryChange = {},
                onSelectTab = {},
                onLoadNextPage = {},
                onRetry = {},
                onRefresh = onRefresh,
                onSeriesClick = {},
                onPlay = {},
            )
        }
    }

    @Test
    fun 刷新中指示器可见_未刷新时不可见() {
        var state by mutableStateOf(uiState(isRefreshing = false))
        composeTestRule.setContent {
            LibraryScreenContent(
                uiState = state,
                onQueryChange = {},
                onSelectTab = {},
                onLoadNextPage = {},
                onRetry = {},
                onSeriesClick = {},
                onPlay = {},
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.PULL_REFRESH_INDICATOR).assertDoesNotExist()

        state = uiState(isRefreshing = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.PULL_REFRESH_INDICATOR).assertIsDisplayed()

        state = uiState(isRefreshing = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.PULL_REFRESH_INDICATOR).assertDoesNotExist()
    }

    @Test
    fun 下拉手势触发onRefresh回调() {
        var refreshed = false
        setScreen(uiState(isRefreshing = false), onRefresh = { refreshed = true })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.PULL_REFRESH_CONTAINER)
            .performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()

        assertTrue("下拉手势应该触发 onRefresh 回调", refreshed)
    }

    @Test
    fun 刷新期间已显示的内容不会被清空() {
        var state by mutableStateOf(uiState(isRefreshing = false))
        composeTestRule.setContent {
            LibraryScreenContent(
                uiState = state,
                onQueryChange = {},
                onSelectTab = {},
                onLoadNextPage = {},
                onRetry = {},
                onSeriesClick = {},
                onPlay = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.item("s0")).assertIsDisplayed()

        state = uiState(isRefreshing = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.item("s0")).assertIsDisplayed()
    }
}
