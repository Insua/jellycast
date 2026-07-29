package dev.insua.jellycast.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Task 1b 的真机证据:**播放中封面收起、把空间让给歌词;暂停时封面恢复接近满宽。**
 *
 * 为什么必须是真机测试:这是纯布局问题,JVM 单测测不出"分到多少像素"。为什么必须
 * [assertIsDisplayed] 而不是 `assertExists`:v3 的离线提示条就是「节点在语义树里、却被挤出
 * 视口」,全绿的测试骗过了所有人。当前行更进一步用 [assertFullyVisible](一个像素都没被裁)。
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenCoverCollapseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun timeline(lineCount: Int = 24) = SubtitleTimeline(
        (0 until lineCount).map { index ->
            SubtitleLine(
                startMs = index * 2_000L,
                endMs = index * 2_000L + 1_900L,
                text = "字幕行 $index",
            )
        },
    )

    private fun uiState(positionMs: Long, isPlaying: Boolean) = PlayerUiState(
        mediaItem = MediaItem(id = "x", kind = MediaKind.EPISODE, name = "示例条目", seriesName = "示例分组"),
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = 60_000L,
        subtitleTimeline = timeline(),
        isSubtitleLoading = false,
    )

    private val currentIndex = 12
    private val currentPositionMs = currentIndex * 2_000L + 500L

    @Test
    fun 播放中封面收起且当前行与上下各一行完整可见() {
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                PlayerScreenContent(uiState = uiState(currentPositionMs, isPlaying = true))
            }
        }
        composeTestRule.waitForIdle()

        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width
        val coverWidth = composeTestRule.onNodeWithTag(PlayerScreenTestTags.COVER)
            .fetchSemanticsNode().size.width
        val lyricsHeight = composeTestRule.onNodeWithTag(LyricsViewTestTags.CONTAINER)
            .fetchSemanticsNode().size.height

        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex))
            .assertFullyVisible("播放中的当前字幕行")
        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex - 1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex + 1)).assertIsDisplayed()

        assertTrue(
            "播放中封面应当收起给歌词让路:封面宽 ${coverWidth}px / 屏宽 ${rootWidth}px = " +
                "${"%.2f".format(coverWidth.toFloat() / rootWidth)},歌词区 ${lyricsHeight}px",
            coverWidth <= rootWidth * MAX_PLAYING_COVER_FRACTION,
        )
        composeTestRule.captureScreen("player_playing")
    }

    @Test
    fun 暂停时封面恢复到接近满宽() {
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                PlayerScreenContent(uiState = uiState(currentPositionMs, isPlaying = false))
            }
        }
        composeTestRule.waitForIdle()

        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width
        val coverWidth = composeTestRule.onNodeWithTag(PlayerScreenTestTags.COVER)
            .fetchSemanticsNode().size.width

        composeTestRule.onNodeWithTag(PlayerScreenTestTags.COVER).assertIsDisplayed()
        assertTrue(
            "暂停时封面必须恢复接近满宽(v1 设计文档 §6:海报占「专辑封面」的位置是产品身份):" +
                "实测封面宽 ${coverWidth}px / 屏宽 ${rootWidth}px = " +
                "${"%.2f".format(coverWidth.toFloat() / rootWidth)}",
            coverWidth >= rootWidth * MIN_PAUSED_COVER_FRACTION,
        )
        composeTestRule.captureScreen("player_paused")
    }

    /**
     * 两个状态之间必须是动画过渡(不是硬切),且**动画过程中当前行一帧都不能被裁**——
     * 封面尺寸变化会改变歌词视口高度,居中偏移必须跟着重算(而且要把 LazyColumn 的
     * beforeContentPadding 算进去)。这里关掉自动推进时钟,逐帧检查。
     */
    @Test
    fun 播放暂停切换是动画过渡且当前行全程不被裁() {
        var isPlaying by mutableStateOf(true)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            JellyCastTheme(darkTheme = false) {
                PlayerScreenContent(uiState = uiState(currentPositionMs, isPlaying = isPlaying))
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000L)

        val playingCoverWidth = composeTestRule.onNodeWithTag(PlayerScreenTestTags.COVER)
            .fetchSemanticsNode().size.width

        isPlaying = false
        val widths = mutableListOf<Int>()
        repeat(FRAME_STEPS) { step ->
            composeTestRule.mainClock.advanceTimeBy(FRAME_MS)
            widths += composeTestRule.onNodeWithTag(PlayerScreenTestTags.COVER)
                .fetchSemanticsNode().size.width
            composeTestRule.onNodeWithTag(LyricsViewTestTags.line(currentIndex))
                .assertFullyVisible("切到暂停态的第 ${step + 1} 帧,当前字幕行")
        }
        val pausedCoverWidth = widths.last()

        // 硬切的话中间不会出现任何「既不是播放态尺寸、也不是暂停态尺寸」的中间值。
        val intermediate = widths.count { it != playingCoverWidth && it != pausedCoverWidth }
        assertTrue("封面尺寸变化必须是动画过渡而不是硬切:逐帧宽度 $widths", intermediate >= 2)
        assertTrue(
            "暂停后封面应比播放中更大:$playingCoverWidth -> $pausedCoverWidth",
            pausedCoverWidth > playingCoverWidth,
        )
    }
}

/**
 * "完整可见":先要求 [assertIsDisplayed](节点被放置、在窗口内、不透明),再要求裁剪后的可见
 * 高度不小于节点自身的布局高度——被父级 LazyColumn 裁掉一角的行会在这里被抓住。
 */
internal fun SemanticsNodeInteraction.assertFullyVisible(label: String) {
    assertIsDisplayed()
    val node = fetchSemanticsNode()
    val visibleHeight = node.boundsInRoot.height
    val layoutHeight = node.size.height.toFloat()
    assertTrue(
        "$label 被裁剪了:可见高度 ${visibleHeight}px < 布局高度 ${layoutHeight}px(bounds=${node.boundsInRoot})",
        visibleHeight >= layoutHeight - 1f,
    )
}

/**
 * 真机截图存到 `/sdcard/`,由 `adb pull` 取回作为报告证据(布局问题必须有真机截图为证)。
 * 走 UiAutomation 的 shell 通道:shell 用户可写 /sdcard,应用自身在 API 33+ 上不行。
 * 截图失败绝不让断言测试变红——它只是证据,不是被测行为。
 */
internal fun ComposeContentTestRule.captureScreen(name: String) {
    waitForIdle()
    runCatching {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val fd = automation.executeShellCommand("screencap -p /sdcard/jellycast_$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            val buffer = ByteArray(4096)
            @Suppress("ControlFlowWithEmptyBody")
            while (stream.read(buffer) >= 0) {
            }
        }
    }
}

private const val FRAME_MS = 32L
private const val FRAME_STEPS = 14

/** 播放中:封面必须收到屏宽的一半左右以内,歌词才拿得到 4 行以上的高度。 */
private const val MAX_PLAYING_COVER_FRACTION = 0.55f

/** 暂停时:封面必须回到接近 Task 1 之前的观感(那时是内容区宽度的 0.78,约屏宽的 0.70)。 */
private const val MIN_PAUSED_COVER_FRACTION = 0.60f
