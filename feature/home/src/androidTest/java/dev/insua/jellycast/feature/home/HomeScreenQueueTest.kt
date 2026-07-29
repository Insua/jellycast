package dev.insua.jellycast.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 修正 §3.2("没有上一集")的 Compose 层证据:[HomeScreen] 内部把分区点击接到
 * [HomeSection.playQueueFor]([HomeScreenTest] 已经在纯逻辑层覆盖了这个函数本身),这里额外
 * 证明**真正接线**是对的——点击分区里的第二张卡片时,[HomeScreen] 的 `onItemClick` 拿到的
 * 第二个参数必须是整个分区,而不是只有被点的那一项。
 *
 * 这是当年缺陷的真实形状:以前 `JellyCastNavHost` 固定传 `listOf(item)`,`PlayQueue` 长度
 * 恒为 1,系统媒体控制的"上一集"恒不可用——单靠 [HomeScreenTest] 那种纯函数测试测不出"点击
 * 事件真的把正确的参数传出来了",必须在 Compose 树上验证一次。
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenQueueTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun episode(id: String, name: String = id) = MediaItem(id = id, kind = MediaKind.EPISODE, name = name)

    @Test
    fun 点击继续收听分区第二项_onItemClick收到该分区完整队列() {
        val itemA = episode("a", "剧集A")
        val itemB = episode("b", "剧集B")
        val itemC = episode("c", "剧集C")
        val resumeSection = HomeSection(HomeSectionKind.RESUME, "继续收听", listOf(itemA, itemB, itemC))

        var clickedItem: MediaItem? = null
        var receivedQueue: List<MediaItem>? = null

        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(isLoading = false, sections = listOf(resumeSection)),
                onItemClick = { item, queue -> clickedItem = item; receivedQueue = queue },
            )
        }

        composeTestRule.onNodeWithTag(HomeScreenTestTags.item("b")).performClick()

        assertEquals("b", clickedItem?.id)
        assertEquals(
            "队列必须是整个「继续收听」分区,不能退化成只有被点的这一项——那样上一集恒不可用",
            listOf(itemA, itemB, itemC),
            receivedQueue,
        )
    }
}
