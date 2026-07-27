package dev.insua.jellycast.feature.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.model.EndpointHealth

/**
 * 添加服务器表单:名称 + 可添加多个接入地址(URL + 标签)+ 用户名 + 密码。
 * 「测试连接」调用 [ServerViewModel.testConnection]([EndpointSelector.probeAll] 逐条诊断),
 * 提交调用 [ServerViewModel.submit]。遇到疑似自签证书的失败项,可以点「查看证书」弹窗确认指纹。
 */
@Composable
fun AddServerScreen(
    onDone: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.connectedServerId) {
        if (uiState.connectedServerId != null) {
            viewModel.consumeConnectedServer()
            onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("添加服务器", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = uiState.form.name,
            onValueChange = viewModel::onNameChange,
            label = { Text("服务器名称") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("接入地址", style = MaterialTheme.typography.titleSmall)
        uiState.form.endpoints.forEachIndexed { index, endpoint ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = endpoint.url,
                    onValueChange = { viewModel.onEndpointUrlChange(index, it) },
                    label = { Text("URL") },
                    placeholder = { Text("http://192.168.1.10:8096") },
                    // URL 是 ASCII 协议地址,不是自然语言——即使系统输入法当前是中文拼音,
                    // KeyboardType.Uri 也会让输入法按地址栏语境工作(不做全角标点转换/联想候选),
                    // 这样 "." ":" 这类字符才能所见即所得地录入。
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = endpoint.label,
                    onValueChange = { viewModel.onEndpointLabelChange(index, it) },
                    label = { Text("标签") },
                    placeholder = { Text("局域网 / Tailscale / 公网") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.removeEndpointField(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = "删除这个地址")
                }
            }
        }
        TextButton(onClick = viewModel::addEndpointField) { Text("+ 添加地址") }

        OutlinedTextField(
            value = uiState.form.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            // 根因(现场排查):软键盘默认对这个字段做自动首字母大写 + 自动纠错/联想候选,
            // Jellyfin 用户名虽然大小写不敏感,但输入法插入/替换字符仍会污染输入——
            // 关掉大写与自动纠错,让用户看到的就是实际提交的内容。
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.form.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // 根因(现场排查):这里之前完全没设 keyboardOptions——PasswordVisualTransformation
            // 只负责把明文渲染成圆点,并不会告诉输入法"这是密码字段"。软键盘因此照常做自动
            // 首字母大写/自动纠错,曾把 4 位密码悄悄改成 5 个点。KeyboardType.Password 让输入法
            // 按密码语境工作(不联想候选、不自动改写),同时关闭自动纠错兜底。
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::testConnection, enabled = !uiState.isProbing) {
                if (uiState.isProbing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("测试连接")
            }
            Button(onClick = viewModel::submit, enabled = !uiState.isSubmitting) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("连接并登录")
            }
        }

        if (uiState.diagnostics.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                uiState.diagnostics.forEachIndexed { index, health ->
                    DiagnosticRow(health = health, onInspectCertificate = { viewModel.onInspectCertificate(index) })
                }
            }
        }

        uiState.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    uiState.certConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCertificateConfirmation,
            title = { Text("确认信任此证书?") },
            text = {
                Column {
                    Text(confirmation.endpointUrl, style = MaterialTheme.typography.bodyMedium)
                    Text(confirmation.fingerprint, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCertificate) { Text("信任") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCertificateConfirmation) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DiagnosticRow(health: EndpointHealth, onInspectCertificate: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (health.reachable) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "可达",
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("${health.endpoint.label} · ${health.latencyMs}ms")
        } else {
            Icon(Icons.Filled.Error, contentDescription = "不可达", tint = MaterialTheme.colorScheme.error)
            Text("${health.endpoint.label}:${health.failureReason ?: "未知错误"}")
            val looksSelfSigned = health.failureReason
                ?.contains("SSL", ignoreCase = true) == true
            if (looksSelfSigned) {
                TextButton(onClick = onInspectCertificate) { Text("查看证书") }
            }
        }
    }
}
