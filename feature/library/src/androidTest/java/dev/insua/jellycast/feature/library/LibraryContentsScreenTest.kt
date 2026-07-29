package dev.insua.jellycast.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PageState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LibraryContentsScreenContent] 是纯函数(不依赖 ViewModel/Hilt/网络),这里直接喂手工构造的
 * [PageState],覆盖修正 §3.1(「我的媒体」库卡片可进入并列出内容)要求的交互与状态呈现——
 * 与 [LibraryScreenTest] 同样的拆分理由,同样的断言优先级(已加载条目失败时绝不清空)。
 */
@RunWith(AndroidJUnit4::class)
class LibraryContentsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun mediaItem(id: String, kind: MediaKind = MediaKind.SERIES, name: String = id) =
        MediaItem(id = id, kind = kind, name = name)

    private fun setScreen(
        page: PageState<MediaItem>,
        isOffline: Boolean = false,
        onLoadNextPage: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSeriesClick: (String) -> Unit = {},
        onPlay: (MediaItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibraryContentsScreenContent(
                page = page,
                isOffline = isOffline,
                onLoadNextPage = onLoadNextPage,
                onRetry = onRetry,
                onSeriesClick = onSeriesClick,
                onPlay = onPlay,
            )
        }
    }

    // ---- 剧集条目点进去导航到详情,电影条目直接触发播放 ----

    @Test
    fun 点击剧集条目触发onSeriesClick而不是onPlay() {
        var clickedSeriesId: String? = null
        var played: MediaItem? = null
        val page = PageState(items = listOf(mediaItem("s1", MediaKind.SERIES)), totalCount = 1)
        setScreen(page, onSeriesClick = { clickedSeriesId = it }, onPlay = { played = it })

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.item("s1")).performClick()

        assert(clickedSeriesId == "s1") { "点击剧集条目应导航到剧集详情,而不是直接播放" }
        assert(played == null)
    }

    @Test
    fun 点击电影条目直接触发onPlay() {
        var played: MediaItem? = null
        val page = PageState(items = listOf(mediaItem("m1", MediaKind.MOVIE)), totalCount = 1)
        setScreen(page, onPlay = { played = it })

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.item("m1")).performClick()

        assert(played?.id == "m1") { "电影是完整可播放单元,点击应直接播放" }
    }

    // ---- 加载失败时已加载条目必须继续显示 ----

    @Test
    fun 加载失败时已加载条目继续显示且展示重试行() {
        val page = PageState(
            items = listOf(mediaItem("a", name = "库内容A")),
            totalCount = 100,
            error = "网络错误",
        )
        setScreen(page)

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.item("a")).assertExists()
        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.ERROR_ROW).assertExists()
    }

    @Test
    fun 点击重试行触发retry回调() {
        var retried = false
        val page = PageState(items = listOf(mediaItem("a")), totalCount = 100, error = "网络错误")
        setScreen(page, onRetry = { retried = true })

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.ERROR_ROW).performClick()

        assert(retried) { "点击重试行应触发 retry 回调" }
    }

    @Test
    fun 库为空时显示空态文案() {
        val page = PageState<MediaItem>(items = emptyList(), totalCount = 0, isLoading = false)
        setScreen(page)

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.EMPTY_STATE).assertExists()
    }

    @Test
    fun 首屏加载中显示转圈() {
        setScreen(PageState(isLoading = true))

        composeTestRule.onNodeWithTag(LibraryContentsScreenTestTags.LOADING_INITIAL).assertExists()
    }

    // ---- 无限滚动:滚到接近底部必须真的触发 onLoadNextPage ----

    @Test
    fun 滚动到接近底部触发加载下一页() {
        var loadCalls = 0
        val loaded = (1..60).map { mediaItem("s$it", name = "条目$it") }

        var page by mutableStateOf(PageState<MediaItem>(isLoading = true))
        composeTestRule.setContent {
            LibraryContentsScreenContent(
                page = page,
                isOffline = false,
                onLoadNextPage = { loadCalls++ },
                onRetry = {},
                onSeriesClick = {},
                onPlay = {},
            )
        }
        composeTestRule.waitForIdle()

        page = PageState(items = loaded, totalCount = 200)
        composeTestRule.waitForIdle()

        assert(loadCalls == 0) { "还没滚动就不该预取下一页,实际调用了 $loadCalls 次" }

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(loaded.lastIndex)
        composeTestRule.waitForIdle()

        assert(loadCalls > 0) { "滚动到列表底部必须触发 onLoadNextPage(无限滚动),实际一次都没触发" }
    }
}
