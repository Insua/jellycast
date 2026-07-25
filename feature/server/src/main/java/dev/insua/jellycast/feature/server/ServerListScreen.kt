package dev.insua.jellycast.feature.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 服务器列表:每台显示名称 + 当前选中的 endpoint 与延迟(如 "Tailscale · 42ms")。
 * 选路结果未知(还没探测完成,或全部不可达)时不展示这一行,而不是展示假数据。
 *
 * `onAddServer` 不在 Task 17 brief 规定的签名里,但没有它这个列表页永远无法进入添加服务器
 * 表单——给了默认空实现,不破坏 `ServerListScreen(onServerReady)` 这个必须保留的调用形态。
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Filled.Add, contentDescription = "添加服务器")
            }
        }
    ) { padding ->
        if (uiState.servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有添加服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.servers, key = { it.id }) { server ->
                    ServerRow(server = server, onClick = { onServerReady(server.id) })
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: ServerListItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
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
}
