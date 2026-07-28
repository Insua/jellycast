package dev.insua.jellycast.feature.player

import androidx.media3.common.Player
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.subtitle.SubtitleRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 缺陷 1(设计文档 §3.3):`PlayerViewModel` 此前默认选轨走 `subtitleTracks.firstOrNull()`。
 * 实测样本里 index 0 是外挂弹幕轨(3676 条)、index 4 才是真字幕(774 条)——歌词区被弹幕刷屏。
 *
 * 修复后的规则:弹幕轨([SubtitleTrackRef.isLikelyDanmaku])永远不进入可选字幕列表,默认选中
 * 走"匹配偏好语言"→"第一条"两级 fallback,只在剩下的候选里挑;候选为空(只有弹幕可选)时
 * 宁可不显示字幕,也不退回弹幕。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelSubtitleSelectionTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private val episode = MediaItem(id = "ep1", kind = MediaKind.EPISODE, name = "第 1 集", seriesName = "剧集")

    private val danmaku = SubtitleTrackRef(
        index = 0, language = "chi", displayName = "弹幕[BiliBili]", isTextBased = true, isExternal = true,
    )
    private val realSubtitleZh = SubtitleTrackRef(
        index = 4, language = "chi", displayName = "简体中文", isTextBased = true, isExternal = true,
    )
    private val realSubtitleEn = SubtitleTrackRef(
        index = 5, language = "eng", displayName = "English", isTextBased = true, isExternal = true,
    )

    private class FakeNowPlaying(
        override val mediaItem: MediaItem,
        override val subtitleTracks: List<SubtitleTrackRef>,
    ) : NowPlayingInfo {
        override val mediaSourceId = "ms1"
        override val audioTracks: List<AudioTrack> = emptyList()
    }

    private fun preferencesStore(preferredLanguage: String? = null): PreferencesStore = mockk<PreferencesStore>().also {
        every { it.rewindSeconds } returns flowOf(15)
        every { it.forwardSeconds } returns flowOf(30)
        every { it.preferredSubtitleLanguage } returns flowOf(preferredLanguage)
        every { it.lyricsEnabled } returns flowOf(true)
    }

    /** track == null(没有可用非弹幕轨)时 load 根本不会被调,所以这里只需要给"被调用"的情形打桩。 */
    private fun subtitleRepository(): SubtitleRepository = mockk<SubtitleRepository>().also { repo ->
        coEvery { repo.load(any(), any(), 4) } returns SubtitleTimeline(listOf(SubtitleLine(0, 1000, "真字幕内容")))
        coEvery { repo.load(any(), any(), 5) } returns SubtitleTimeline(listOf(SubtitleLine(0, 1000, "English content")))
    }

    private fun buildViewModel(
        subtitleTracks: List<SubtitleTrackRef>,
        preferredLanguage: String? = null,
        repo: SubtitleRepository = subtitleRepository(),
    ): PlayerViewModel {
        val connection = mockk<PlayerConnection>(relaxed = true)
        every { connection.player } returns MutableStateFlow<Player?>(null)
        every { connection.nowPlaying } returns MutableStateFlow(FakeNowPlaying(episode, subtitleTracks))
        every { connection.absolutePositionMs() } returns 0L
        return PlayerViewModel(connection, repo, preferencesStore(preferredLanguage))
    }

    @Test fun `同时存在弹幕轨与普通字幕轨时默认选中普通字幕`() = runTest(testDispatcher) {
        val vm = buildViewModel(listOf(danmaku, realSubtitleZh))

        runCurrent()

        assertEquals(4, vm.uiState.value.selectedSubtitleTrackIndex)
        assertEquals("真字幕内容", vm.uiState.value.subtitleTimeline.lines.first().text)
    }

    @Test fun `弹幕轨不出现在可选字幕列表里`() = runTest(testDispatcher) {
        val vm = buildViewModel(listOf(danmaku, realSubtitleZh))

        runCurrent()

        assertTrue(vm.uiState.value.subtitleTracks.none { it.index == danmaku.index })
        assertEquals(listOf(realSubtitleZh), vm.uiState.value.subtitleTracks)
    }

    @Test fun `只有弹幕轨可选时不显示字幕也不退回弹幕`() = runTest(testDispatcher) {
        val vm = buildViewModel(listOf(danmaku))

        runCurrent()

        assertNull(vm.uiState.value.selectedSubtitleTrackIndex)
        assertTrue(vm.uiState.value.subtitleTimeline.lines.isEmpty())
        assertTrue(vm.uiState.value.subtitleTracks.isEmpty())
    }

    @Test fun `偏好语言只在非弹幕候选里匹配`() = runTest(testDispatcher) {
        // 偏好中文,但中文轨是弹幕——不能因为语言匹配就选中弹幕,应退到唯一的非弹幕候选(英文)。
        val vm = buildViewModel(listOf(danmaku, realSubtitleEn), preferredLanguage = "chi")

        runCurrent()

        assertEquals(5, vm.uiState.value.selectedSubtitleTrackIndex)
    }

    @Test fun `偏好语言匹配到非弹幕轨时优先选它`() = runTest(testDispatcher) {
        val vm = buildViewModel(listOf(realSubtitleEn, realSubtitleZh), preferredLanguage = "chi")

        runCurrent()

        assertEquals(4, vm.uiState.value.selectedSubtitleTrackIndex)
    }
}
