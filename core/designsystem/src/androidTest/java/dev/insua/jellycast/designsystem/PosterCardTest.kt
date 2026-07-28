package dev.insua.jellycast.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun 提供progress时显示听过进度条() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = {},
                    progress = 0.4f,
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.PROGRESS_BAR, useUnmergedTree = true).assertExists()
    }

    @Test
    fun 不提供progress时不显示进度条() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.PROGRESS_BAR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun 不提供收藏已看状态时不显示对应按钮() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.FAVORITE_BUTTON, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(PosterCardTestTags.PLAYED_BUTTON, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun 点击收藏按钮触发onToggleFavorite而不触发onClick() {
        var toggled = false
        var cardClicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = { cardClicked = true },
                    isFavorite = false,
                    onToggleFavorite = { toggled = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.FAVORITE_BUTTON, useUnmergedTree = true).performClick()

        assert(toggled) { "点击收藏按钮应触发 onToggleFavorite" }
        assert(!cardClicked) { "点击收藏按钮不应触发 onClick(不冒泡到整卡)" }
    }

    @Test
    fun isFavorite为true时收藏按钮显示为已收藏() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = {},
                    isFavorite = true,
                    onToggleFavorite = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.FAVORITE_BUTTON, useUnmergedTree = true).assertExists()
    }

    @Test
    fun 点击已看按钮触发onTogglePlayed而不触发onClick() {
        var toggled = false
        var cardClicked = false
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = { cardClicked = true },
                    isPlayed = false,
                    onTogglePlayed = { toggled = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.PLAYED_BUTTON, useUnmergedTree = true).performClick()

        assert(toggled) { "点击已看按钮应触发 onTogglePlayed" }
        assert(!cardClicked) { "点击已看按钮不应触发 onClick(不冒泡到整卡)" }
    }

    @Test
    fun isPlayed为true时已看按钮显示为已看() {
        composeTestRule.setContent {
            JellyCastTheme {
                PosterCard(
                    title = "第一集",
                    subtitle = "示例剧名",
                    imageUrl = null,
                    onClick = {},
                    isPlayed = true,
                    onTogglePlayed = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(PosterCardTestTags.PLAYED_BUTTON, useUnmergedTree = true).assertExists()
    }
}
