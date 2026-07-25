package dev.insua.jellycast.designsystem

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
                    onPlayPause = { playPauseClicked = true },
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
                    onPlayPause = { playPauseClicked = true },
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
                    onPlayPause = {},
                    onExpand = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("暂停").assertExists()
    }
}
