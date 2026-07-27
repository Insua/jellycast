package dev.insua.jellycast.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 离线提示条(设计文档 §3.2 第二行)的两条硬要求:
 * 1. **不打断浏览** —— 它只是页面顶部的一条,底下的内容照常可见、可点。用弹窗就废掉了整个离线
 *    缓存的意义:好不容易把上次的内容显示出来了,却盖一层模态框让用户先点"确定"。
 * 2. **可关闭** —— 用户知道自己没网,不需要一条永远赶不走的横幅占着屏幕。
 */
@RunWith(AndroidJUnit4::class)
class OfflineBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun 显示离线文案且不遮挡下方内容() {
        composeTestRule.setContent {
            JellyCastTheme {
                Column {
                    OfflineBanner(onDismiss = {})
                    Text("下面的内容")
                }
            }
        }

        composeTestRule.onNodeWithTag(OfflineBannerTestTags.ROOT).assertIsDisplayed()
        composeTestRule.onNodeWithText(OFFLINE_BANNER_MESSAGE).assertIsDisplayed()
        // 提示条不是弹窗:同一棵树里它下面的内容照样可见。
        composeTestRule.onNodeWithText("下面的内容").assertIsDisplayed()
    }

    @Test
    fun 点关闭按钮之后提示条消失() {
        composeTestRule.setContent {
            JellyCastTheme {
                var dismissed by remember { mutableStateOf(false) }
                if (!dismissed) OfflineBanner(onDismiss = { dismissed = true })
            }
        }

        composeTestRule.onNodeWithTag(OfflineBannerTestTags.DISMISS_BUTTON).performClick()

        composeTestRule.onNodeWithTag(OfflineBannerTestTags.ROOT).assertDoesNotExist()
    }
}
