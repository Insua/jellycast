package dev.insua.jellycast.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.datastore.PreferencesStore
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
    val sessionBytesTransferred: Long = 0L,
)

/**
 * 设置页。前六项([playbackSpeed]…[preferredSubtitleLanguage])直接把 [PreferencesStore] 的
 * Flow 摆到 UI 上,双向绑定——没有额外的领域逻辑。
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
            playbackEngine.state.collect { state ->
                val level = (state as? PlaybackEngineState.Ready)?.source?.level
                _uiState.update { it.copy(currentDeliveryLevel = level) }
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
}
