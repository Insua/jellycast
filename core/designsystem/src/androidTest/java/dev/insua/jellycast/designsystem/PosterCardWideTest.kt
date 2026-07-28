package dev.insua.jellycast.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterCardWideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun progress大于0时显示进度条和播放叠层() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCardWide(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    imageUrl = null,
                    onClick = {},
                    progress = 0.4f,
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PROGRESS_BAR, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PLAY_OVERLAY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun progress为0时不显示进度条但仍显示播放叠层() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCardWide(
                    title = "第一集",
                    subtitle = "示例剧名 · S01E01",
                    imageUrl = null,
                    onClick = {},
                    progress = 0f,
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PROGRESS_BAR, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PLAY_OVERLAY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun progress为null时不显示进度条() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCardWide(
                    title = "第一集",
                    subtitle = null,
                    imageUrl = null,
                    onClick = {},
                    progress = null,
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PROGRESS_BAR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun 点击卡片触发onClick() {
        var clicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCardWide(
                    title = "第一集",
                    subtitle = null,
                    imageUrl = null,
                    onClick = { clicked = true },
                    progress = null,
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardWideTestTags.PLAY_OVERLAY, useUnmergedTree = true)
            .performClick()

        assert(clicked) { "点击卡片应触发 onClick" }
    }
}
