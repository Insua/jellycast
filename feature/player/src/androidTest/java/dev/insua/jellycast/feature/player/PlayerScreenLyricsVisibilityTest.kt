package dev.insua.jellycast.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.designsystem.JellyCastTheme
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 招牌功能的真机布局证据:播放页正在播放的那一行字幕**必须完整可见**,且上下各至少有一行上下文。
 *
 * 为什么必须是真机测试、且必须用 [assertIsDisplayed] 而不是 `assertExists`:
 * v3 的离线提示条就是"节点存在于语义树里、却被 LazyColumn 挤出了视口",全部 Compose 测试
 * 通过、用户却什么也看不见。`assertExists` 在这里是无效断言。
 *
 * 更进一步:[assertIsDisplayed] 只保证"有一部分可见"——被裁掉一半的行也能通过。所以当前行用
 * [assertFullyVisible] 断言"裁剪后的高度 == 布局高度",即一个像素都没被裁。
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenLyricsVisibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** 每行 2 秒的规整时间轴;文案与真实片源无关,只为占位。 */
    private fun timeline(lineCount: Int = 24) = SubtitleTimeline(
        (0 until lineCount).map { index ->
            SubtitleLine(
                startMs = index * 2_000L,
                endMs = index * 2_000L + 1_900L,
                text = "字幕行 $index",
            )
        },
    )

    private fun uiState(positionMs: Long) = PlayerUiState(
        mediaItem = MediaItem(id = "x", kind = MediaKind.EPISODE, name = "示例条目", seriesName = "示例分组"),
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 60_000L,
        subtitleTimeline = timeline(),
        isSubtitleLoading = false,
    )

    @Test
    fun 播放中当前字幕行完整可见且上下各有一行上下文() {
        val currentIndex = 12
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                PlayerScreenContent(uiState = uiState(positionMs = currentIndex * 2_000L + 500L))
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex))
            .assertFullyVisible("当前正在播放的字幕行")

        // 歌词区本身必须真的分到了高度(至少放得下当前行 + 上下各一行)——v3 的教训是
        // "节点在语义树里、视口却几乎没有高度",单看某一行的断言不足以说明布局没退化。
        val container = composeTestRule.onNodeWithTag(LyricsViewTestTags.CONTAINER).fetchSemanticsNode()
        val currentLineHeight = composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex))
            .fetchSemanticsNode().size.height
        assertTrue(
            "歌词区高度只有 ${container.size.height}px,放不下当前行(${currentLineHeight}px)加上下各一行",
            container.size.height >= 3 * currentLineHeight,
        )
        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex - 1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex + 1)).assertIsDisplayed()
    }

    /** 边界:第一行是"当前行"时,下方仍要有上下文,且它本身不能被顶出视口。 */
    @Test
    fun 首行为当前行时依然完整可见并有下文() {
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                PlayerScreenContent(uiState = uiState(positionMs = 500L))
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(0)).assertFullyVisible("首行")
        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(1)).assertIsDisplayed()
    }
}
