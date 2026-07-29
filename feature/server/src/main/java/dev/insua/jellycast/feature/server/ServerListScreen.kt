package dev.insua.jellycast.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** [ServerListScreenTest] 用来定位节点的测试标签,不依赖文案(文案会改)。 */
object ServerListScreenTestTags {
    const val EMPTY_STATE = "server_list_empty_state"
    const val ERROR_ROW = "server_list_error_row"
    const val DELETE_CONFIRM_DIALOG = "server_list_delete_confirm_dialog"
    const val DELETE_CONFIRM_BUTTON = "server_list_delete_confirm_button"
    const val DELETE_CANCEL_BUTTON = "server_list_delete_cancel_button"

    fun row(id: String) = "server_list_row_$id"
    fun deleteButton(id: String) = "server_list_delete_button_$id"
}

/**
 * 服务器列表:每台显示名称 + 当前选中的 endpoint 与延迟(如 "Tailscale · 42ms")。
 * 选路结果未知(还没探测完成,或全部不可达)时不展示这一行,而不是展示假数据。
 *
 * `onAddServer` 不在 Task 17 brief 规定的签名里,但没有它这个列表页永远无法进入添加服务器
 * 表单——给了默认空实现,不破坏 `ServerListScreen(onServerReady)` 这个必须保留的调用形态。
 *
 * 真正的界面在 [ServerListScreenContent](纯函数、无 ViewModel 依赖)——这里只负责从
 * [ServerViewModel] 取 `uiState` 并把回调转接过去,方便 Compose UI 测试直接喂一个手工
 * 构造的 [ServerUiState] 而不必拉起真的 ViewModel/Hilt(参见 :feature:library 的
 * `LibraryScreen` / `LibraryScreenContent` 同一种拆法)。
 */
@Composable
fun ServerListScreen(
    onServerReady: (String) -> Unit,
    onAddServer: () -> Unit = {},
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.connectedServerId) {
        uiState.connectedServerId?.let { serverId ->
            onServerReady(serverId)
            viewModel.consumeConnectedServer()
        }
    }

    ServerListScreenContent(
        uiState = uiState,
        onServerClick = onServerReady,
        onAddServer = onAddServer,
        onDeleteRequest = viewModel::requestDeleteServer,
        onDeleteConfirm = viewModel::confirmDeleteServer,
        onDeleteDismiss = viewModel::dismissDeleteConfirmation,
    )
}

/**
 * 删除服务器是破坏性操作(会丢登录态),所以必须走二次确认弹窗——[uiState].deleteConfirmation
 * 非空时才展示;取消不调用任何回调,只有点了弹窗里的「删除」才触发 [onDeleteConfirm]。
 * 删除失败时 [uiState].error 非空,渲染成列表顶部一行文字,不静默。
 */
@Composable
fun ServerListScreenContent(
    uiState: ServerUiState,
    onServerClick: (String) -> Unit,
    onAddServer: () -> Unit,
    onDeleteRequest: (String) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteDismiss: () -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Filled.Add, contentDescription = "添加服务器")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag(ServerListScreenTestTags.ERROR_ROW),
                )
            }
            if (uiState.servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().testTag(ServerListScreenTestTags.EMPTY_STATE),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有添加服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.servers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            onClick = { onServerClick(server.id) },
                            onDelete = { onDeleteRequest(server.id) },
                        )
                    }
                }
            }
        }
    }

    uiState.deleteConfirmation?.let { serverId ->
        val serverName = uiState.servers.find { it.id == serverId }?.name.orEmpty()
        AlertDialog(
            modifier = Modifier.testTag(ServerListScreenTestTags.DELETE_CONFIRM_DIALOG),
            onDismissRequest = onDeleteDismiss,
            title = { Text("删除服务器?") },
            text = { Text("将删除“$serverName”并清除已保存的登录状态与缓存,此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = onDeleteConfirm,
                    modifier = Modifier.testTag(ServerListScreenTestTags.DELETE_CONFIRM_BUTTON),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDeleteDismiss,
                    modifier = Modifier.testTag(ServerListScreenTestTags.DELETE_CANCEL_BUTTON),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServerRow(server: ServerListItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)
            .testTag(ServerListScreenTestTags.row(server.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = server.name, style = MaterialTheme.typography.titleMedium)
            val status = server.selectedEndpointLabel?.let { label ->
                if (server.latencyMs != null) "$label · ${server.latencyMs}ms" else label
            }
            Text(
                text = status ?: "未连接",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag(ServerListScreenTestTags.deleteButton(server.id)),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除${server.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
