package dev.insua.jellycast.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PageState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LibraryScreenContent] 是纯函数(不依赖 ViewModel/Hilt/网络),这里直接喂手工构造的
 * [LibraryUiState],覆盖 修正 §1(a)/(b) 要求的状态呈现与交互行为。
 *
 * 最重要的一条(审查明确点名):`error != null` 时,已经加载的条目必须继续显示,
 * 只在底部追加一行重试提示——绝不能因为出错就把用户已经看到的列表清空。
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun mediaItem(id: String, kind: MediaKind = MediaKind.SERIES, name: String = id) =
        MediaItem(id = id, kind = kind, name = name)

    private fun setScreen(
        initialState: LibraryUiState,
        onQueryChange: (String) -> Unit = {},
        onSelectTab: (LibraryTab) -> Unit = {},
        onLoadNextPage: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSeriesClick: (String) -> Unit = {},
        onPlay: (MediaItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibraryScreenContent(
                uiState = initialState,
                onQueryChange = onQueryChange,
                onSelectTab = onSelectTab,
                onLoadNextPage = onLoadNextPage,
                onRetry = onRetry,
                onSeriesClick = onSeriesClick,
                onPlay = onPlay,
            )
        }
    }

    // ---- 修正 §1(b)-1:输入搜索词隐藏双 Tab,清空后恢复 ----

    @Test
    fun 输入搜索词后双Tab隐藏_清空后恢复() {
        var state by mutableStateOf(LibraryUiState(series = PageState(items = listOf(mediaItem("s1")), totalCount = 1)))
        composeTestRule.setContent {
            LibraryScreenContent(
                uiState = state,
                onQueryChange = { q -> state = state.copy(query = q) },
                onSelectTab = {},
                onLoadNextPage = {},
                onRetry = {},
                onSeriesClick = {},
                onPlay = {},
            )
        }

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.TAB_ROW).assertExists()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.SEARCH_FIELD).performTextInput("银魂")
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.TAB_ROW).assertDoesNotExist()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.SEARCH_CLEAR_BUTTON).assertExists()

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.SEARCH_CLEAR_BUTTON).performClick()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.TAB_ROW).assertExists()
    }

    // ---- 修正 §1(b)-2:最重要的一条——出错时已加载内容必须继续显示 ----

    @Test
    fun 加载失败时已加载条目继续显示且展示重试行() {
        val state = LibraryUiState(
            tab = LibraryTab.SERIES,
            series = PageState(
                items = listOf(mediaItem("s1", name = "剧集A"), mediaItem("s2", name = "剧集B")),
                totalCount = 100,
                isLoading = false,
                error = "网络错误",
            ),
        )
        setScreen(state)

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.item("s1")).assertExists()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.item("s2")).assertExists()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.ERROR_ROW).assertExists()
    }

    @Test
    fun 点击重试行触发retry回调() {
        var retried = false
        val state = LibraryUiState(series = PageState(items = listOf(mediaItem("s1")), totalCount = 100, error = "网络错误"))
        setScreen(state, onRetry = { retried = true })

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.ERROR_ROW).performClick()

        assert(retried) { "点击重试行应触发 retry 回调" }
    }

    // ---- 修正 §1(a):空态文案按搜索/浏览区分 ----

    @Test
    fun 搜索无结果显示没有匹配文案() {
        val state = LibraryUiState(
            query = "xyz",
            searchResults = PageState(items = emptyList(), totalCount = 0, isLoading = false),
        )
        setScreen(state)

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.EMPTY_STATE)
            .assertTextContains("没有匹配的剧集或电影")
    }

    @Test
    fun 浏览态无内容显示库还没有内容文案() {
        val state = LibraryUiState(series = PageState(items = emptyList(), totalCount = 0, isLoading = false))
        setScreen(state)

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.EMPTY_STATE)
            .assertTextContains("这个库还没有内容")
    }

    // ---- 修正 §1(a):补充覆盖 isLoading 的两种转圈场景,这是本 Task 新增的状态呈现 ----

    @Test
    fun 首屏加载中显示转圈() {
        val state = LibraryUiState(series = PageState(isLoading = true))
        setScreen(state)

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.LOADING_INITIAL).assertExists()
    }

    @Test
    fun 已有条目时加载下一页显示底部转圈且条目仍可见() {
        val state = LibraryUiState(
            series = PageState(items = listOf(mediaItem("s1")), totalCount = 100, isLoading = true),
        )
        setScreen(state)

        composeTestRule.onNodeWithTag(LibraryScreenTestTags.item("s1")).assertExists()
        composeTestRule.onNodeWithTag(LibraryScreenTestTags.LOADING_MORE).assertExists()
    }
}
