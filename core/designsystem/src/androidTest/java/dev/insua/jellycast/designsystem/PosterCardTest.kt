package dev.insua.jellycast.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
