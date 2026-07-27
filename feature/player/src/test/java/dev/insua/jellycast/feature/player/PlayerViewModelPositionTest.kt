package dev.insua.jellycast.feature.player

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.subtitle.SubtitleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 全支线复审 Critical 1 在 UI 层的落地:**播放页的位置必须是条目内绝对位置,时长必须来自元数据。**
 *
 * 两个前提都由 Spike 实测决定(docs/superpowers/specs/2026-07-25-spike-results.md):
 * 1. 转码流 `Accept-Ranges: none` → seek/续播都换一条新流 → `Player.currentPosition` 每次归零,
 *    只是"这条流里播了多久"。歌词时间戳是**绝对**的,拿流内相对位置去 `indexAt()` 会让从 8:00
 *    续播的那一集从第 1 行开始高亮——产品的门面功能第一帧就是错的。
 * 2. 转码输出是 chunked AAC,`Player.duration` 往往是 `C.TIME_UNSET` → 进度条会钉在 100%,
 *    一拖就把这一集从头开始。权威总时长是 Jellyfin 元数据 `MediaItem.runTimeMs`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelPositionTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val episode = MediaItem(
        id = "ep12",
        kind = MediaKind.EPISODE,
        name = "第 12 集",
        seriesName = "银魂",
        runTimeMs = 1_500_000L,      // 25 分钟
    )

    private class FakeNowPlaying(override val mediaItem: MediaItem) : NowPlayingInfo {
        override val mediaSourceId = "ms1"
        override val audioTracks = emptyList<dev.insua.jellycast.model.AudioTrack>()
        override val subtitleTracks = emptyList<dev.insua.jellycast.model.SubtitleTrackRef>()
    }

    private fun preferencesStore(): PreferencesStore = mockk<PreferencesStore>().also {
        every { it.rewindSeconds } returns flowOf(15)
        every { it.forwardSeconds } returns flowOf(30)
        every { it.preferredSubtitleLanguage } returns flowOf(null)
        every { it.lyricsEnabled } returns flowOf(true)
    }

    private class Fixture(
        val viewModel: PlayerViewModel,
        val player: Player,
        val playerFlow: MutableStateFlow<Player?>,
        val connection: PlayerConnection,
    )

    /**
     * 底层 player 报的是"从 8:00 那条新流里播了 20 秒"= 20_000(流内相对位置),
     * 引擎报的绝对位置是 500_000(8:20)。UI 必须显示后者。
     */
    private fun fixture(
        absolutePositionMs: Long = 500_000L,
        streamRelativePositionMs: Long = 20_000L,
        nowPlaying: NowPlayingInfo? = FakeNowPlaying(episode),
    ): Fixture {
        val player = mockk<Player>(relaxed = true)
        every { player.currentPosition } returns streamRelativePositionMs
        every { player.duration } returns C.TIME_UNSET      // chunked AAC 转码流:时长未知
        val playerFlow = MutableStateFlow<Player?>(player)
        val connection = mockk<PlayerConnection>(relaxed = true)
        every { connection.player } returns playerFlow
        every { connection.nowPlaying } returns MutableStateFlow(nowPlaying)
        every { connection.absolutePositionMs() } returns absolutePositionMs
        val viewModel = PlayerViewModel(connection, mockk<SubtitleRepository>(), preferencesStore())
        return Fixture(viewModel, player, playerFlow, connection)
    }

    /** [PlayerViewModel.observePlaybackState] 是个无限轮询循环,测试收尾必须让它停下来(见姊妹测试注释)。 */
    private fun kotlinx.coroutines.test.TestScope.stopPolling(fixture: Fixture) {
        fixture.playerFlow.value = null
        runCurrent()
    }

    @Test fun `positionMs 是引擎报的绝对位置,不是底层 player 的流内相对位置`() = runTest(testDispatcher) {
        val f = fixture()

        runCurrent()

        assertEquals(500_000L, f.viewModel.uiState.value.positionMs)
        stopPolling(f)
    }

    @Test fun `durationMs 取元数据 runTimeMs,底层 duration 是 TIME_UNSET 也不会退化成 0`() = runTest(testDispatcher) {
        val f = fixture()

        runCurrent()

        assertEquals(1_500_000L, f.viewModel.uiState.value.durationMs)
        stopPolling(f)
    }

    @Test fun `没有元数据时长时回退到底层 duration,仍然不为负`() = runTest(testDispatcher) {
        val player = mockk<Player>(relaxed = true)
        every { player.currentPosition } returns 0L
        every { player.duration } returns 900_000L
        val playerFlow = MutableStateFlow<Player?>(player)
        val connection = mockk<PlayerConnection>(relaxed = true)
        every { connection.player } returns playerFlow
        every { connection.nowPlaying } returns MutableStateFlow(FakeNowPlaying(episode.copy(runTimeMs = null)))
        every { connection.absolutePositionMs() } returns 0L
        val viewModel = PlayerViewModel(connection, mockk<SubtitleRepository>(), preferencesStore())

        runCurrent()

        assertEquals(900_000L, viewModel.uiState.value.durationMs)
        playerFlow.value = null
        runCurrent()
    }

    /** 核心验收:从 8:20 续播后快进 30 秒必须 seek 到 8:50,而不是从流内 20 秒算出 0:50。 */
    @Test fun `快进以绝对位置为起点,不以底层流内相对位置为起点`() = runTest(testDispatcher) {
        val f = fixture()
        runCurrent()

        f.viewModel.onSkipForward()

        verify(exactly = 1) { f.player.seekTo(530_000L) }
        stopPolling(f)
    }

    @Test fun `快退以绝对位置为起点`() = runTest(testDispatcher) {
        val f = fixture()
        runCurrent()

        f.viewModel.onSkipBack()

        verify(exactly = 1) { f.player.seekTo(485_000L) }
        stopPolling(f)
    }

    /** 快进不得越过片尾:钳制用的总时长也必须是元数据时长,不是 TIME_UNSET 的底层时长。 */
    @Test fun `快进被钳制在元数据总时长之内`() = runTest(testDispatcher) {
        val f = fixture(absolutePositionMs = 1_490_000L)
        runCurrent()

        f.viewModel.onSkipForward()

        verify(exactly = 1) { f.player.seekTo(1_500_000L) }
        stopPolling(f)
    }
}
