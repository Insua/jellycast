package dev.insua.jellycast.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.model.AudioDeliveryLevel

private val REWIND_OPTIONS = listOf(5, 10, 15, 30)
private val FORWARD_OPTIONS = listOf(10, 15, 30, 60)
private val BIT_RATE_OPTIONS = listOf(64, 128, 256)

/**
 * 设置页(Task 21):服务器管理入口 / 默认倍速 / 快退快进秒数 / 自动连播 / 歌词式字幕 /
 * 首选字幕语言 / 音频码率(修正 §3) / 开发者信息(折叠)。
 */
@Composable
fun SettingsScreen(
    onManageServers: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var developerInfoExpanded by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        item {
            ListItem(
                headlineContent = { Text("服务器管理") },
                supportingContent = { Text("添加 / 切换 / 管理已登录的 Jellyfin 服务器") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickableItem(onManageServers),
            )
            Divider()
        }

        item {
            SectionTitle("播放")
            SliderRow(
                label = "默认播放倍速",
                valueLabel = "${"%.1f".format(uiState.playbackSpeed)}x",
                value = uiState.playbackSpeed,
                valueRange = 0.5f..3.0f,
                steps = 24, // (3.0-0.5)/0.1 - 1 = 24 个中间刻度,共 26 档,步进 0.1
                onValueChangeFinished = viewModel::onPlaybackSpeedChange,
            )
            ChoiceRow(
                label = "快退秒数",
                options = REWIND_OPTIONS,
                selected = uiState.rewindSeconds,
                onSelect = viewModel::onRewindSecondsChange,
            )
            ChoiceRow(
                label = "快进秒数",
                options = FORWARD_OPTIONS,
                selected = uiState.forwardSeconds,
                onSelect = viewModel::onForwardSecondsChange,
            )
            SwitchRow(
                label = "自动连播下一集",
                checked = uiState.autoPlayNext,
                onCheckedChange = viewModel::onAutoPlayNextChange,
            )
        }

        item {
            SectionTitle("字幕")
            SwitchRow(
                label = "歌词式字幕",
                checked = uiState.lyricsEnabled,
                onCheckedChange = viewModel::onLyricsEnabledChange,
            )
            ListItem(
                headlineContent = { Text("首选字幕语言") },
                supportingContent = { Text(uiState.preferredSubtitleLanguage ?: "跟随媒体默认") },
            )
        }

        item {
            // 音频码率:本产品的核心价值(纯音频省流量)唯一可调旋钮,Spike 实测
            // 128kbps≈132kbps 实测码率、64kbps≈71kbps 实测码率。
            SectionTitle("网络")
            ChoiceRow(
                label = "音频码率",
                options = BIT_RATE_OPTIONS,
                selected = uiState.audioBitRateKbps,
                onSelect = viewModel::onAudioBitRateKbpsChange,
                formatOption = { "${it}k" },
            )
        }

        item {
            Divider()
            ListItem(
                headlineContent = { Text("开发者信息") },
                trailingContent = {
                    Icon(
                        if (developerInfoExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickableItem { developerInfoExpanded = !developerInfoExpanded },
            )
            if (developerInfoExpanded) {
                DeveloperInfoSection(
                    endpoint = uiState.currentEndpoint,
                    deliveryLevel = uiState.currentDeliveryLevel,
                    bytesTransferred = uiState.sessionBytesTransferred,
                )
            }
        }
    }
}

@Composable
private fun DeveloperInfoSection(endpoint: String?, deliveryLevel: AudioDeliveryLevel?, bytesTransferred: Long) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        DevInfoLine("当前 endpoint", endpoint ?: "未连接")
        DevInfoLine("当前音频降级级别", deliveryLevel.toDisplayLabel())
        DevInfoLine("本次会话已传输", formatBytes(bytesTransferred))
    }
}

private fun AudioDeliveryLevel?.toDisplayLabel(): String = when (this) {
    AudioDeliveryLevel.SERVER_AUDIO_ONLY -> "L1 · 服务端纯音频"
    AudioDeliveryLevel.CLIENT_VIDEO_DISABLED -> "L3 · 客户端禁用视频轨(兜底)"
    null -> "未在播放"
}

/** 纯换算,不接触 Android——可离线单测。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun DevInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onValueChangeFinished(draft) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    formatOption: (Int) -> String = { "${it}s" },
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(formatOption(option)) },
                )
            }
        }
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
