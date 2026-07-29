package dev.insua.jellycast.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ServerListScreenContent] 是纯函数(不依赖 ViewModel/Hilt),这里直接喂手工构造的
 * [ServerUiState],覆盖 Task 4(删除服务器)最重要的一条:删除是破坏性操作(会丢登录态),
 * 必须先弹二次确认——取消绝不能删,只有点了弹窗里的「删除」才会触发确认回调。
 */
@RunWith(AndroidJUnit4::class)
class ServerListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun serverItem(id: String, name: String = "服务器-$id") = ServerListItem(id = id, name = name)

    // ---- 点删除只弹确认,不直接触发删除 ----

    @Test
    fun 点击删除按钮请求二次确认弹窗() {
        var requestedId: String? = null
        var state by mutableStateOf(ServerUiState(servers = listOf(serverItem("srv-1"))))
        composeTestRule.setContent {
            ServerListScreenContent(
                uiState = state,
                onServerClick = {},
                onAddServer = {},
                onDeleteRequest = { id ->
                    requestedId = id
                    state = state.copy(deleteConfirmation = id)
                },
                onDeleteConfirm = {},
                onDeleteDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG).assertDoesNotExist()

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.deleteButton("srv-1")).performClick()

        assertEquals("srv-1", requestedId)
        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG).assertExists()
    }

    // ---- 取消:弹窗消失,绝不触发删除回调 ----

    @Test
    fun 弹窗中点取消不触发删除且弹窗消失() {
        var confirmCalls = 0
        var state by mutableStateOf(
            ServerUiState(servers = listOf(serverItem("srv-1")), deleteConfirmation = "srv-1")
        )
        composeTestRule.setContent {
            ServerListScreenContent(
                uiState = state,
                onServerClick = {},
                onAddServer = {},
                onDeleteRequest = {},
                onDeleteConfirm = { confirmCalls++ },
                onDeleteDismiss = { state = state.copy(deleteConfirmation = null) },
            )
        }

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG).assertExists()

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CANCEL_BUTTON).performClick()

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG).assertDoesNotExist()
        assertEquals(0, confirmCalls)
        assertNull(state.deleteConfirmation)
    }

    // ---- 确认:才真正触发删除回调,并带上正确的服务器 id ----

    @Test
    fun 弹窗中点删除才触发确认回调并清空弹窗() {
        var confirmedId: String? = null
        var state by mutableStateOf(
            ServerUiState(servers = listOf(serverItem("srv-1")), deleteConfirmation = "srv-1")
        )
        composeTestRule.setContent {
            ServerListScreenContent(
                uiState = state,
                onServerClick = {},
                onAddServer = {},
                onDeleteRequest = {},
                onDeleteConfirm = {
                    confirmedId = state.deleteConfirmation
                    state = state.copy(deleteConfirmation = null, servers = emptyList())
                },
                onDeleteDismiss = { state = state.copy(deleteConfirmation = null) },
            )
        }

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_BUTTON).performClick()

        assertEquals("srv-1", confirmedId)
        composeTestRule.onNodeWithTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG).assertDoesNotExist()
        // 删到列表为空:回到"还没有添加服务器"的空状态,而不是一个没有解释的空白列表。
        composeTestRule.onNodeWithTag(ServerListScreenTestTags.EMPTY_STATE).assertExists()
    }

    // ---- 删除失败:不静默,列表页要展示错误提示 ----

    @Test
    fun 删除失败时错误提示展示在列表页() {
        val state = ServerUiState(servers = listOf(serverItem("srv-1")), error = "删除服务器失败:disk full")
        composeTestRule.setContent {
            ServerListScreenContent(
                uiState = state,
                onServerClick = {},
                onAddServer = {},
                onDeleteRequest = {},
                onDeleteConfirm = {},
                onDeleteDismiss = {},
            )
        }

        composeTestRule.onNodeWithTag(ServerListScreenTestTags.ERROR_ROW).assertExists()
    }
}
