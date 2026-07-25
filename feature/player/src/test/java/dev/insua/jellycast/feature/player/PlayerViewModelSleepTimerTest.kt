package dev.insua.jellycast.feature.player

import androidx.media3.common.Player
import dev.insua.jellycast.datastore.PreferencesStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Finding 1 + Finding 2:睡眠定时器的「播完本集」模式。
 *
 * 关键断言不是"ViewModel 记住了选了哪个选项"这种表面状态,而是它调用了
 * [PlayerConnection.setStopAfterCurrentEpisode] —— 这是真正会被 Task 22 接到
 * `AutoPlayNextController.armStopAfterCurrentEpisode()` 的那个信号,决定"下一集要不要开始播放"
 * 的地方在 core:player,不在这里;这里只负责把用户的选择转发过去。
 *
 * 每个测试结束前都把 `connection.player` 置回 null 再 `runCurrent()`——[PlayerViewModel] 的
 * `observePlaybackState()` 是一个绑定 player 生命周期的无限轮询循环(每 500ms 一次,生产环境靠
 * `onCleared()`/player 置空来终止),不这样收尾的话 `runTest` 收尾阶段会把虚拟时钟一直往前推来
 * "耗尽"这个永远有下一个 delay(500) 排队的循环,直接把测试进程堆内存吃满。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelSleepTimerTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakePreferencesStore(): PreferencesStore {
        val store = mockk<PreferencesStore>()
        every { store.rewindSeconds } returns flowOf(15)
        every { store.forwardSeconds } returns flowOf(30)
        every { store.preferredSubtitleLanguage } returns flowOf(null)
        return store
    }

    private fun viewModel(connection: PlayerConnection): PlayerViewModel {
        val subtitleRepository = mockk<dev.insua.jellycast.subtitle.SubtitleRepository>()
        return PlayerViewModel(connection, subtitleRepository, fakePreferencesStore())
    }

    private class Fixture(val connection: PlayerConnection, val playerFlow: MutableStateFlow<Player?>)

    private fun fakeConnection(player: Player): Fixture {
        val playerFlow = MutableStateFlow<Player?>(player)
        val connection = mockk<PlayerConnection>(relaxed = true)
        every { connection.player } returns playerFlow
        every { connection.nowPlaying } returns MutableStateFlow(null)
        return Fixture(connection, playerFlow)
    }

    private suspend fun TestScope.stopPolling(fixture: Fixture) {
        fixture.playerFlow.value = null
        runCurrent()
    }

    @Test fun `选择固定分钟数到点后暂停播放,不武装播完本集`() = runTest(testDispatcher) {
        val player = mockk<Player>(relaxed = true)
        val fixture = fakeConnection(player)
        val viewModel = viewModel(fixture.connection)

        viewModel.onSetSleepTimer(SleepTimerOption.Minutes(15))
        advanceTimeBy(15 * 60_000L)
        runCurrent()

        verify(exactly = 1) { player.pause() }
        verify(exactly = 0) { fixture.connection.setStopAfterCurrentEpisode(true) }
        assertNull(viewModel.uiState.value.sleepTimerOption)

        stopPolling(fixture)
    }

    @Test fun `选择播完本集立即武装,不启动倒计时`() = runTest(testDispatcher) {
        val player = mockk<Player>(relaxed = true)
        val fixture = fakeConnection(player)
        val viewModel = viewModel(fixture.connection)

        viewModel.onSetSleepTimer(SleepTimerOption.EndOfEpisode)

        verify(exactly = 1) { fixture.connection.setStopAfterCurrentEpisode(true) }
        assertEquals(SleepTimerOption.EndOfEpisode, viewModel.uiState.value.sleepTimerOption)

        // 播完本集不是"等一段墙钟时间再暂停"——推进任意长时间都不应该调用 player.pause()。
        advanceTimeBy(120 * 60_000L)
        runCurrent()
        verify(exactly = 0) { player.pause() }

        stopPolling(fixture)
    }

    @Test fun `从播完本集切回固定分钟数会解除武装并重新开始倒计时`() = runTest(testDispatcher) {
        val player = mockk<Player>(relaxed = true)
        val fixture = fakeConnection(player)
        val viewModel = viewModel(fixture.connection)

        viewModel.onSetSleepTimer(SleepTimerOption.EndOfEpisode)
        viewModel.onSetSleepTimer(SleepTimerOption.Minutes(15))

        verify(atLeast = 1) { fixture.connection.setStopAfterCurrentEpisode(false) }

        advanceTimeBy(15 * 60_000L)
        runCurrent()
        verify(exactly = 1) { player.pause() }

        stopPolling(fixture)
    }

    @Test fun `关闭定时器会解除播完本集的武装`() = runTest(testDispatcher) {
        val player = mockk<Player>(relaxed = true)
        val fixture = fakeConnection(player)
        val viewModel = viewModel(fixture.connection)

        viewModel.onSetSleepTimer(SleepTimerOption.EndOfEpisode)
        viewModel.onSetSleepTimer(null)

        verify(atLeast = 1) { fixture.connection.setStopAfterCurrentEpisode(false) }
        assertNull(viewModel.uiState.value.sleepTimerOption)

        stopPolling(fixture)
    }
}
