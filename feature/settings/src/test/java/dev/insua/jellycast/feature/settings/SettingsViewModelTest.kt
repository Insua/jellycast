package dev.insua.jellycast.feature.settings

import android.content.Intent
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.diagnostics.DiagnosticsExporter
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.SessionByteCounter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun preferencesStore(): PreferencesStore {
        val store = mockk<PreferencesStore>(relaxed = true)
        every { store.playbackSpeed } returns flowOf(1.5f)
        every { store.rewindSeconds } returns flowOf(10)
        every { store.forwardSeconds } returns flowOf(15)
        every { store.autoPlayNext } returns flowOf(false)
        every { store.lyricsEnabled } returns flowOf(false)
        every { store.preferredSubtitleLanguage } returns flowOf("chi")
        every { store.audioBitRateKbps } returns flowOf(64)
        every { store.diagnosticsEnabled } returns flowOf(true)
        every { store.cacheMaxBytes } returns flowOf(5L * 1024 * 1024 * 1024)
        return store
    }

    private fun exporter(): DiagnosticsExporter = mockk(relaxed = true)

    private fun session(baseUrl: String? = "http://192.168.1.10:8096"): JellyfinSession {
        val s = mockk<JellyfinSession>()
        if (baseUrl != null) {
            coEvery { s.baseUrl() } returns baseUrl
        } else {
            coEvery { s.baseUrl() } throws IllegalStateException("没有已激活的服务器")
        }
        return s
    }

    private fun engine(state: PlaybackEngineState = PlaybackEngineState.Idle): AudioPlaybackEngine {
        val e = mockk<AudioPlaybackEngine>()
        every { e.state } returns MutableStateFlow(state)
        return e
    }

    private fun byteCounter(bytes: Long = 0L): SessionByteCounter {
        val c = mockk<SessionByteCounter>()
        every { c.totalBytes } returns MutableStateFlow(bytes)
        return c
    }

    @Test fun `启动后从 PreferencesStore 读取全部偏好项`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(preferencesStore(), session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1.5f, state.playbackSpeed)
        assertEquals(10, state.rewindSeconds)
        assertEquals(15, state.forwardSeconds)
        assertEquals(false, state.autoPlayNext)
        assertEquals(false, state.lyricsEnabled)
        assertEquals("chi", state.preferredSubtitleLanguage)
        assertEquals(64, state.audioBitRateKbps)
        assertEquals(true, state.diagnosticsEnabled)
        assertEquals(5L * 1024 * 1024 * 1024, state.cacheMaxBytes)
    }

    @Test fun `开发者信息读取当前 endpoint 与本次会话已传输字节数`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            preferencesStore(), session("http://100.1.1.1:8096"), engine(), byteCounter(12_345L), exporter(),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("http://100.1.1.1:8096", state.currentEndpoint)
        assertEquals(12_345L, state.sessionBytesTransferred)
    }

    @Test fun `没有已激活服务器时 currentEndpoint 保持 null 而不是崩溃`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(preferencesStore(), session(null), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentEndpoint)
    }

    @Test fun `播放中时开发者信息显示当前音频降级级别`() = runTest(testDispatcher) {
        val source = PlaybackSource(
            itemId = "ep1", mediaSourceId = "ms1", streamUrl = "https://nas/Audio/ep1/universal",
            level = AudioDeliveryLevel.SERVER_AUDIO_ONLY, isHls = false, playSessionId = null,
            audioTracks = emptyList(), textSubtitles = emptyList(),
        )
        val viewModel = SettingsViewModel(
            preferencesStore(), session(), engine(PlaybackEngineState.Ready(source)), byteCounter(), exporter(),
        )
        advanceUntilIdle()

        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY, viewModel.uiState.value.currentDeliveryLevel)
    }

    /**
     * 复审 Task 5 Finding 1:命中缓存的 PlaybackSource.level 仍是 SERVER_AUDIO_ONLY(实现细节),
     * ViewModel 必须把 isLocalFile 原样透传出去,显示层(SettingsScreen 的
     * deliveryLevelDisplayLabel)才有依据在播本地文件时不显示「L1 · 服务端纯音频」这种假话。
     */
    @Test fun `播放本地缓存文件时开发者信息标记为本地文件`() = runTest(testDispatcher) {
        val source = PlaybackSource(
            itemId = "ep1", mediaSourceId = "ep1", streamUrl = "file:///cache/ep1.m4a",
            level = AudioDeliveryLevel.SERVER_AUDIO_ONLY, isHls = false, playSessionId = null,
            audioTracks = emptyList(), textSubtitles = emptyList(), isLocalFile = true,
        )
        val viewModel = SettingsViewModel(
            preferencesStore(), session(), engine(PlaybackEngineState.Ready(source)), byteCounter(), exporter(),
        )
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.currentSourceIsLocalFile)
    }

    @Test fun `修改快退秒数会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        viewModel.onRewindSecondsChange(30)
        advanceUntilIdle()

        coVerify { store.setRewindSeconds(30) }
    }

    @Test fun `修改音频码率会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        viewModel.onAudioBitRateKbpsChange(256)
        advanceUntilIdle()

        coVerify { store.setAudioBitRateKbps(256) }
    }

    @Test fun `关闭诊断日志开关会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        viewModel.onDiagnosticsEnabledChange(false)
        advanceUntilIdle()

        coVerify { store.setDiagnosticsEnabled(false) }
    }

    @Test fun `修改缓存最大占用存储会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        viewModel.onCacheMaxBytesChange(10L * 1024 * 1024 * 1024)
        advanceUntilIdle()

        coVerify { store.setCacheMaxBytes(10L * 1024 * 1024 * 1024) }
    }

    /**
     * task-7-brief 明确要防的坑:「不限制」和某个具体字节数(比如 1 GB)一旦在写回时被混淆,
     * 用户选的「不限制」就会静默退化成一个有限值。这里断言 ViewModel 把 `null` 原样透传给
     * [PreferencesStore],不做任何"null 就当默认值处理"之类的转换。
     */
    @Test fun `选择不限制时写回 null 而不是某个具体字节数`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter(), exporter())
        advanceUntilIdle()

        viewModel.onCacheMaxBytesChange(null)
        advanceUntilIdle()

        coVerify { store.setCacheMaxBytes(null) }
    }

    @Test fun `导出诊断日志委托给 DiagnosticsExporter 并返回其分享 Intent`() = runTest(testDispatcher) {
        val fakeIntent = mockk<Intent>()
        val diagnosticsExporter = mockk<DiagnosticsExporter>()
        every { diagnosticsExporter.buildShareIntent() } returns fakeIntent
        val viewModel = SettingsViewModel(preferencesStore(), session(), engine(), byteCounter(), diagnosticsExporter)
        advanceUntilIdle()

        val result = viewModel.buildDiagnosticsExportIntent()

        assertEquals(fakeIntent, result)
        verify { diagnosticsExporter.buildShareIntent() }
    }

    @Test fun `没有任何诊断日志内容时导出返回 null 而不是崩溃`() = runTest(testDispatcher) {
        val diagnosticsExporter = mockk<DiagnosticsExporter>()
        every { diagnosticsExporter.buildShareIntent() } returns null
        val viewModel = SettingsViewModel(preferencesStore(), session(), engine(), byteCounter(), diagnosticsExporter)
        advanceUntilIdle()

        assertNull(viewModel.buildDiagnosticsExportIntent())
    }
}
