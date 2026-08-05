package dev.insua.jellycast.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.datastore.DEFAULT_CACHE_MAX_BYTES
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.diagnostics.DiagnosticsExporter
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.SessionByteCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val playbackSpeed: Float = 1.0f,
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val autoPlayNext: Boolean = true,
    val lyricsEnabled: Boolean = true,
    val preferredSubtitleLanguage: String? = null,
    val audioBitRateKbps: Int = 128,
    val currentEndpoint: String? = null,
    val currentDeliveryLevel: AudioDeliveryLevel? = null,
    /**
     * 当前播放源是否来自本地音频缓存([dev.insua.jellycast.model.PlaybackSource.isLocalFile])。
     *
     * 复审发现:命中缓存的 `PlaybackSource.level` 仍然是 [AudioDeliveryLevel.SERVER_AUDIO_ONLY]
     * (`CacheAwareSourceProvider` 的实现细节,见其 KDoc),但那条流其实**一次请求都没发给服务端**。
     * `currentDeliveryLevel` 单独暴露会让「开发者信息」面板在播缓存文件时显示「L1 · 服务端纯音频」
     * ——这是假话,而且会和旁边几乎是 0 的「本次会话已传输字节数」自相矛盾。这个面板存在的唯一
     * 目的就是让人能看清"这次到底走的是哪条网络路径",报错比什么都不报更糟。
     *
     * 这里单独暴露 [isLocalFile],由显示层([SettingsScreen] 的 `deliveryLevelDisplayLabel`)在
     * `isLocalFile` 为真时整体覆盖显示文案——而不是往 [AudioDeliveryLevel] 里加一个新枚举值:
     * `SettingsScreen` 对该枚举有一处穷尽 `when`,加值会牵连到那处无关代码。
     */
    val currentSourceIsLocalFile: Boolean = false,
    val sessionBytesTransferred: Long = 0L,
    val diagnosticsEnabled: Boolean = true,
    /**
     * 音频缓存的存储上限,单位字节(Task 7,design doc §4.3)。`null` = 不限制——和
     * [dev.insua.jellycast.datastore.PreferencesStore.cacheMaxBytes] 的表示一致。默认值直接引用
     * [DEFAULT_CACHE_MAX_BYTES],与该 Flow 无数据时的默认值保持同一份定义,不再各自维护。
     */
    val cacheMaxBytes: Long? = DEFAULT_CACHE_MAX_BYTES,
)

/**
 * 设置页。前八项([playbackSpeed]…[cacheMaxBytes])直接把 [PreferencesStore] 的
 * Flow 摆到 UI 上,双向绑定——没有额外的领域逻辑。
 *
 * [buildDiagnosticsExportIntent](Task 5,design doc §5)是个例外:导出诊断日志不是一条偏好,
 * 是个一次性动作,委托给 [DiagnosticsExporter]——真正的落盘/脱敏/轮转逻辑都在 `:core:diagnostics`,
 * 这里只做转发,方便测试(mock [DiagnosticsExporter] 而不用真的碰文件系统)。
 *
 * "开发者信息(折叠)"三项(修正 §3)是本类唯一有一点点逻辑的地方:
 * - [SettingsUiState.currentEndpoint] 来自 [JellyfinSession]——只读一次当前缓存值,不主动触发
 *   选路(设置页不应该因为被打开就发起网络探测)。
 * - [SettingsUiState.currentDeliveryLevel] 订阅 [AudioPlaybackEngine.state],播放中才有值,
 *   对应 `AudioDeliveryLevel`(Spike 之后只剩 L1/L3 两级,`HLS_AUDIO_RENDITION` 已删)。
 * - [SettingsUiState.sessionBytesTransferred] 订阅 [SessionByteCounter],进程级、不落盘。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: PreferencesStore,
    private val session: JellyfinSession,
    private val playbackEngine: AudioPlaybackEngine,
    private val byteCounter: SessionByteCounter,
    private val diagnosticsExporter: DiagnosticsExporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesStore.playbackSpeed.collect { v -> _uiState.update { it.copy(playbackSpeed = v) } }
        }
        viewModelScope.launch {
            preferencesStore.rewindSeconds.collect { v -> _uiState.update { it.copy(rewindSeconds = v) } }
        }
        viewModelScope.launch {
            preferencesStore.forwardSeconds.collect { v -> _uiState.update { it.copy(forwardSeconds = v) } }
        }
        viewModelScope.launch {
            preferencesStore.autoPlayNext.collect { v -> _uiState.update { it.copy(autoPlayNext = v) } }
        }
        viewModelScope.launch {
            preferencesStore.lyricsEnabled.collect { v -> _uiState.update { it.copy(lyricsEnabled = v) } }
        }
        viewModelScope.launch {
            preferencesStore.preferredSubtitleLanguage.collect { v ->
                _uiState.update { it.copy(preferredSubtitleLanguage = v) }
            }
        }
        viewModelScope.launch {
            preferencesStore.audioBitRateKbps.collect { v -> _uiState.update { it.copy(audioBitRateKbps = v) } }
        }
        viewModelScope.launch {
            preferencesStore.diagnosticsEnabled.collect { v -> _uiState.update { it.copy(diagnosticsEnabled = v) } }
        }
        viewModelScope.launch {
            preferencesStore.cacheMaxBytes.collect { v -> _uiState.update { it.copy(cacheMaxBytes = v) } }
        }
        viewModelScope.launch {
            playbackEngine.state.collect { state ->
                val source = (state as? PlaybackEngineState.Ready)?.source
                _uiState.update {
                    it.copy(
                        currentDeliveryLevel = source?.level,
                        currentSourceIsLocalFile = source?.isLocalFile ?: false,
                    )
                }
            }
        }
        viewModelScope.launch {
            byteCounter.totalBytes.collect { v -> _uiState.update { it.copy(sessionBytesTransferred = v) } }
        }
        viewModelScope.launch {
            val endpoint = runCatching { session.baseUrl() }.getOrNull()
            _uiState.update { it.copy(currentEndpoint = endpoint) }
        }
    }

    fun onPlaybackSpeedChange(value: Float) = viewModelScope.launch { preferencesStore.setPlaybackSpeed(value) }

    fun onRewindSecondsChange(value: Int) = viewModelScope.launch { preferencesStore.setRewindSeconds(value) }

    fun onForwardSecondsChange(value: Int) = viewModelScope.launch { preferencesStore.setForwardSeconds(value) }

    fun onAutoPlayNextChange(value: Boolean) = viewModelScope.launch { preferencesStore.setAutoPlayNext(value) }

    fun onLyricsEnabledChange(value: Boolean) = viewModelScope.launch { preferencesStore.setLyricsEnabled(value) }

    fun onPreferredSubtitleLanguageChange(value: String?) =
        viewModelScope.launch { preferencesStore.setPreferredSubtitleLanguage(value) }

    fun onAudioBitRateKbpsChange(value: Int) = viewModelScope.launch { preferencesStore.setAudioBitRateKbps(value) }

    fun onDiagnosticsEnabledChange(value: Boolean) =
        viewModelScope.launch { preferencesStore.setDiagnosticsEnabled(value) }

    fun onCacheMaxBytesChange(value: Long?) = viewModelScope.launch { preferencesStore.setCacheMaxBytes(value) }

    /**
     * "导出诊断日志"背后的动作(design doc §5)。返回 `null` 时(从未记录过任何内容,或用户此前
     * 已关闭诊断日志)UI 层应该给一条"暂无可导出的诊断日志"之类的提示,而不是弹出一个空的分享面板。
     */
    fun buildDiagnosticsExportIntent(): Intent? = diagnosticsExporter.buildShareIntent()
}
