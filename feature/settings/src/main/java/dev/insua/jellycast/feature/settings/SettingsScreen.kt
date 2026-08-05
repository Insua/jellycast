package dev.insua.jellycast.feature.settings

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.model.AudioDeliveryLevel

private val REWIND_OPTIONS = listOf(5, 10, 15, 30)
private val FORWARD_OPTIONS = listOf(10, 15, 30, 60)
private val BIT_RATE_OPTIONS = listOf(64, 128, 256)

private const val GB = 1024L * 1024L * 1024L

/** 设计文档 §4.3 / task-7-brief:1 GB / 5 GB / 10 GB / 不限制(`null`),默认 1 GB。 */
private val CACHE_MAX_BYTES_OPTIONS: List<Long?> = listOf(1L * GB, 5L * GB, 10L * GB, null)

/**
 * 设置页(Task 21):服务器管理入口 / 默认倍速 / 快退快进秒数 / 自动连播 / 歌词式字幕 /
 * 首选字幕语言 / 音频码率(修正 §3) / 缓存最大占用存储(Task 7)/ 诊断日志开关与导出(Task 5)/
 * 开发者信息(折叠)。
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
                formatOption = { "${it}s" },
            )
            ChoiceRow(
                label = "快进秒数",
                options = FORWARD_OPTIONS,
                selected = uiState.forwardSeconds,
                onSelect = viewModel::onForwardSecondsChange,
                formatOption = { "${it}s" },
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
            // Task 7 / design doc §4.3:存储上限是"缓存会占多少设备空间"这个问题,和上面「网络」
            // 分组关心的"这次播放走多少流量"是两个维度,所以单独起一个分组而不是塞进「网络」。
            SectionTitle("缓存")
            ChoiceRow(
                label = "最大占用存储",
                options = CACHE_MAX_BYTES_OPTIONS,
                selected = uiState.cacheMaxBytes,
                onSelect = viewModel::onCacheMaxBytesChange,
                formatOption = ::formatCacheMaxBytesOption,
            )
            Text(
                "只限制缓存总占用空间;每部剧最多缓存当前及之后 10 集,“不限制”不会突破这条上限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item {
            // Task 5 / design doc §5:真机调试来回成本过高,让 App 自己收集崩溃与关键错误证据,
            // 设置页一键导出,不需要再搭一遍无线调试环境。落盘前已脱敏(见 :core:diagnostics 的
            // Redactor)——不含 token / 密码 / 完整服务器地址。
            SectionTitle("诊断")
            SwitchRow(
                label = "记录诊断日志",
                checked = uiState.diagnosticsEnabled,
                onCheckedChange = viewModel::onDiagnosticsEnabledChange,
            )
            val context = LocalContext.current
            ListItem(
                headlineContent = { Text("导出诊断日志") },
                supportingContent = { Text("崩溃与关键错误记录,已脱敏,通过系统分享发送") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickableItem {
                    val intent = viewModel.buildDiagnosticsExportIntent()
                    if (intent != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "暂无可导出的诊断日志", Toast.LENGTH_SHORT).show()
                    }
                },
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
                    isLocalFile = uiState.currentSourceIsLocalFile,
                    bytesTransferred = uiState.sessionBytesTransferred,
                )
            }
        }
    }
}

@Composable
private fun DeveloperInfoSection(
    endpoint: String?,
    deliveryLevel: AudioDeliveryLevel?,
    isLocalFile: Boolean,
    bytesTransferred: Long,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        DevInfoLine("当前 endpoint", endpointDisplayLabel(endpoint, isLocalFile))
        DevInfoLine("当前音频降级级别", deliveryLevelDisplayLabel(deliveryLevel, isLocalFile))
        DevInfoLine("本次会话已传输", formatBytes(bytesTransferred))
    }
}

/**
 * 复审发现:同一类"命中缓存时面板说瞎话"问题的第二处——[deliveryLevelDisplayLabel] 已经堵住了
 * "L1 · 服务端纯音频"这句谎言,但「当前 endpoint」这一行原样显示 [endpoint](`JellyfinSession
 * .baseUrl()` 的缓存值),没有看 [isLocalFile]。播一集完全没发网络请求的本地缓存文件时,这一行
 * 照样显示"192.168.1.10:8096"之类的地址,像是这次播放真的在跟那个 endpoint 通信——不是。
 * 命中本地缓存时这里改显示"本地缓存文件",和 [deliveryLevelDisplayLabel] 同一种"isLocalFile
 * 优先、整体覆盖显示文案"的做法。
 *
 * 纯函数,不接触 Compose/Android——可离线单测,和 [formatBytes] 同一种做法。
 */
internal fun endpointDisplayLabel(endpoint: String?, isLocalFile: Boolean): String = when {
    isLocalFile -> "本地缓存文件(未连接 endpoint)"
    else -> endpoint ?: "未连接"
}

/**
 * 复审发现:命中音频缓存的 `PlaybackSource.level` 仍然是 `AudioDeliveryLevel.SERVER_AUDIO_ONLY`
 * (`CacheAwareSourceProvider` 的实现细节),但那条流其实一次请求都没发给服务端——如果这里只看
 * [level] 不看 [isLocalFile],面板会在播本地缓存文件时显示「L1 · 服务端纯音频」,和几乎为 0 的
 * 「本次会话已传输字节数」自相矛盾。[isLocalFile] 因此在这里优先于 [level] 判断,整体覆盖显示文案
 * ——不新增 [AudioDeliveryLevel] 枚举值(会牵连到别处对它的穷尽 `when`),纯粹是显示层的判断。
 *
 * 纯函数,不接触 Compose/Android——可离线单测,和 [formatBytes] 同一种做法。
 */
internal fun deliveryLevelDisplayLabel(level: AudioDeliveryLevel?, isLocalFile: Boolean): String = when {
    isLocalFile -> "本地缓存 · 未发起网络请求"
    level == AudioDeliveryLevel.SERVER_AUDIO_ONLY -> "L1 · 服务端纯音频"
    level == AudioDeliveryLevel.CLIENT_VIDEO_DISABLED -> "L3 · 客户端禁用视频轨(兜底)"
    else -> "未在播放"
}

/** 纯换算,不接触 Android——可离线单测。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * 「最大占用存储」ChoiceRow 的选项文案。`null` = 不限制(Task 7,design doc §4.3)——和
 * [dev.insua.jellycast.datastore.PreferencesStore.cacheMaxBytes] / `CachePrefetchController`
 * 的 `maxBytes: Long?` 契约保持一致。
 *
 * 纯函数,不接触 Compose/Android——可离线单测,和 [formatBytes] 同一种做法。
 */
internal fun formatCacheMaxBytesOption(bytes: Long?): String =
    if (bytes == null) "不限制" else "${bytes / GB} GB"

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

/**
 * 泛型化(Task 7):原本只接 [Int] 选项,「最大占用存储」的选项是 [Long]`?`(`null` = 不限制,
 * 见 [formatCacheMaxBytesOption])——没有一个对全部 `T` 都成立的合理默认格式化方式,
 * [formatOption] 因此改成必填参数,各调用点显式传入。
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    formatOption: (T) -> String,
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
