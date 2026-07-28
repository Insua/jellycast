package dev.insua.jellycast.designsystem

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun 点击播放暂停按钮触发onPlayPause() {
        var playPauseClicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                MiniPlayerBar(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    posterUrl = null,
                    isPlaying = false,
                    progress = 0.3f,
                    hasNext = true,
                    onPlayPause = { playPauseClicked = true },
                    onSkipNext = {},
                    onExpand = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(MiniPlayerBarTestTags.PLAY_PAUSE_BUTTON).performClick()

        assert(playPauseClicked) { "点击播放/暂停按钮应触发 onPlayPause" }
    }

    @Test
    fun 点击整条触发onExpand而不触发onPlayPause() {
        var expandClicked = false
        var playPauseClicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                MiniPlayerBar(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    posterUrl = null,
                    isPlaying = false,
                    progress = 0.3f,
                    hasNext = true,
                    onPlayPause = { playPauseClicked = true },
                    onSkipNext = {},
                    onExpand = { expandClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(MiniPlayerBarTestTags.ROOT).performClick()

        assert(expandClicked) { "点击整条应触发 onExpand" }
        assert(!playPauseClicked) { "点击整条不应触发 onPlayPause" }
    }

    @Test
    fun isPlaying为true时显示暂停图标() {
        composeTestRule.setContent {
            JellyCastTheme {
                MiniPlayerBar(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    posterUrl = null,
                    isPlaying = true,
                    progress = 0.3f,
                    hasNext = true,
                    onPlayPause = {},
                    onSkipNext = {},
                    onExpand = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("暂停").assertExists()
    }

    @Test
    fun hasNext为true时点击下一集按钮触发onSkipNext而不触发onPlayPause或onExpand() {
        var skipNextClicked = false
        var playPauseClicked = false
        var expandClicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                MiniPlayerBar(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    posterUrl = null,
                    isPlaying = false,
                    progress = 0.3f,
                    hasNext = true,
                    onPlayPause = { playPauseClicked = true },
                    onSkipNext = { skipNextClicked = true },
                    onExpand = { expandClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(MiniPlayerBarTestTags.SKIP_NEXT_BUTTON).performClick()

        assert(skipNextClicked) { "点击下一集按钮应触发 onSkipNext" }
        assert(!playPauseClicked) { "点击下一集按钮不应触发 onPlayPause" }
        assert(!expandClicked) { "点击下一集按钮不应触发 onExpand(不冒泡到整条)" }
    }

    @Test
    fun hasNext为false时下一集按钮禁用() {
        composeTestRule.setContent {
            JellyCastTheme {
                MiniPlayerBar(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    posterUrl = null,
                    isPlaying = false,
                    progress = 0.3f,
                    hasNext = false,
                    onPlayPause = {},
                    onSkipNext = {},
                    onExpand = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(MiniPlayerBarTestTags.SKIP_NEXT_BUTTON).assertIsNotEnabled()
    }
}
