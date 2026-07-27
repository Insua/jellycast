package dev.insua.jellycast.feature.settings

import dev.insua.jellycast.datastore.PreferencesStore
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
        return store
    }

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
        val viewModel = SettingsViewModel(preferencesStore(), session(), engine(), byteCounter())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1.5f, state.playbackSpeed)
        assertEquals(10, state.rewindSeconds)
        assertEquals(15, state.forwardSeconds)
        assertEquals(false, state.autoPlayNext)
        assertEquals(false, state.lyricsEnabled)
        assertEquals("chi", state.preferredSubtitleLanguage)
        assertEquals(64, state.audioBitRateKbps)
    }

    @Test fun `开发者信息读取当前 endpoint 与本次会话已传输字节数`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(
            preferencesStore(), session("http://100.1.1.1:8096"), engine(), byteCounter(12_345L),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("http://100.1.1.1:8096", state.currentEndpoint)
        assertEquals(12_345L, state.sessionBytesTransferred)
    }

    @Test fun `没有已激活服务器时 currentEndpoint 保持 null 而不是崩溃`() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(preferencesStore(), session(null), engine(), byteCounter())
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
            preferencesStore(), session(), engine(PlaybackEngineState.Ready(source)), byteCounter(),
        )
        advanceUntilIdle()

        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY, viewModel.uiState.value.currentDeliveryLevel)
    }

    @Test fun `修改快退秒数会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter())
        advanceUntilIdle()

        viewModel.onRewindSecondsChange(30)
        advanceUntilIdle()

        coVerify { store.setRewindSeconds(30) }
    }

    @Test fun `修改音频码率会写回 PreferencesStore`() = runTest(testDispatcher) {
        val store = preferencesStore()
        val viewModel = SettingsViewModel(store, session(), engine(), byteCounter())
        advanceUntilIdle()

        viewModel.onAudioBitRateKbpsChange(256)
        advanceUntilIdle()

        coVerify { store.setAudioBitRateKbps(256) }
    }
}
