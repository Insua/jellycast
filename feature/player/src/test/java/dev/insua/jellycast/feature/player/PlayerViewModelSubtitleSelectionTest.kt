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
import org.junit.jupiter.api.Assertions.assertFalse
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

    /** 25 分钟集数——密度判定([SubtitleTimeline.isSuspiciouslyDense])需要知道片长才能算。 */
    private val episodeWithRuntime = episode.copy(runTimeMs = 25 * 60_000L)

    private val danmaku = SubtitleTrackRef(
        index = 0, language = "chi", displayName = "弹幕[BiliBili]", isTextBased = true, isExternal = true,
    )

    /**
     * 全支线复审 Important:标题不含 danmu/danmaku/弹幕任何一个关键字的弹幕轨——对应真实场景里
     * 命名花样百出的弹幕文件(日文コメント、通用 Track 1、纯数字标签)。这条轨道在选轨阶段(标题
     * 关键字判定)会被当成"真字幕"放行,必须靠解析后的密度信号([SubtitleTimeline.isSuspiciouslyDense])
     * 二次判定才能被降级。
     */
    private val danmakuNoKeyword = SubtitleTrackRef(
        index = 0, language = "chi", displayName = "Track 1", isTextBased = true, isExternal = true,
    )

    /**
     * 全支线复审 Important 的另一半:合法字幕标题恰好包含弹幕关键字(例如片头/片尾特效滚动字幕
     * 标注了"弹幕"字样)。标题信号会把它误判成弹幕轨丢弃——这是已知的、有意接受的窄口径假阳性
     * (design spec 明确写的信号就是标题关键字),这里验证的是"丢弃后至少让用户知道发生了什么"
     * ([PlayerUiState.subtitleSkippedAsDanmaku]),而不是试图让标题信号变得更聪明。
     */
    private val karaokeFalsePositive = SubtitleTrackRef(
        index = 6, language = "chi", displayName = "特效弹幕滚动字幕", isTextBased = true, isExternal = true,
    )

    private val realSubtitleZh = SubtitleTrackRef(
        index = 4, language = "chi", displayName = "简体中文", isTextBased = true, isExternal = true,
    )
    private val realSubtitleEn = SubtitleTrackRef(
        index = 5, language = "eng", displayName = "English", isTextBased = true, isExternal = true,
    )

    /** 25 分钟集数,3676 条——实测弹幕样本的密度(设计文档 §3.3)。 */
    private fun denseTimeline(count: Int = 3676): SubtitleTimeline =
        SubtitleTimeline((0 until count).map { SubtitleLine(it * 100L, it * 100L + 50L, "line$it") })

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
        coEvery { it.setPreferredSubtitleLanguage(any()) } returns Unit
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
        mediaItem: MediaItem = episode,
    ): PlayerViewModel {
        val connection = mockk<PlayerConnection>(relaxed = true)
        every { connection.player } returns MutableStateFlow<Player?>(null)
        every { connection.nowPlaying } returns MutableStateFlow(FakeNowPlaying(mediaItem, subtitleTracks))
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
        // 全支线复审 Important:候选被弹幕信号吃光了这件事现在要能被 UI 感知到,而不是悄无声息
        // 地什么都不显示——用户没法区分"这一集真的没有字幕"和"有字幕但被误判丢弃了"。
        assertTrue(vm.uiState.value.subtitleSkippedAsDanmaku)
    }

    @Test fun `合法字幕标题误含弹幕关键字被丢弃时也标记已跳过`() = runTest(testDispatcher) {
        // 已知的窄口径假阳性(见 karaokeFalsePositive 的类注释):标题信号会把它当弹幕丢弃,
        // 这里只验证"丢弃"这件事被暴露给了 UI,不改变标题信号本身的判定结果。
        val vm = buildViewModel(listOf(karaokeFalsePositive))

        runCurrent()

        assertNull(vm.uiState.value.selectedSubtitleTrackIndex)
        assertTrue(vm.uiState.value.subtitleTracks.isEmpty())
        assertTrue(vm.uiState.value.subtitleSkippedAsDanmaku)
    }

    @Test fun `标题不含弹幕关键字但解析后密度异常的轨道被降级选中真字幕`() = runTest(testDispatcher) {
        val repo = mockk<SubtitleRepository>().also { repo ->
            coEvery { repo.load(any(), any(), 0) } returns denseTimeline()
            coEvery { repo.load(any(), any(), 4) } returns SubtitleTimeline(listOf(SubtitleLine(0, 1000, "真字幕内容")))
        }
        val vm = buildViewModel(listOf(danmakuNoKeyword, realSubtitleZh), repo = repo, mediaItem = episodeWithRuntime)

        runCurrent()

        assertEquals(4, vm.uiState.value.selectedSubtitleTrackIndex)
        assertEquals("真字幕内容", vm.uiState.value.subtitleTimeline.lines.first().text)
        // 被密度判定降级的轨道要从可选列表里永久剔除——否则用户手动切换字幕时又会点回同一个坑。
        assertTrue(vm.uiState.value.subtitleTracks.none { it.index == 0 })
        assertFalse(vm.uiState.value.subtitleSkippedAsDanmaku)
    }

    @Test fun `唯一候选标题不含关键字但密度异常时不显示字幕且标记已跳过`() = runTest(testDispatcher) {
        val repo = mockk<SubtitleRepository>().also { repo ->
            coEvery { repo.load(any(), any(), 0) } returns denseTimeline()
        }
        val vm = buildViewModel(listOf(danmakuNoKeyword), repo = repo, mediaItem = episodeWithRuntime)

        runCurrent()

        assertNull(vm.uiState.value.selectedSubtitleTrackIndex)
        assertTrue(vm.uiState.value.subtitleTimeline.lines.isEmpty())
        assertTrue(vm.uiState.value.subtitleTracks.isEmpty())
        assertTrue(vm.uiState.value.subtitleSkippedAsDanmaku)
    }

    @Test fun `手动切换到密度异常的轨道时自动跳到下一个候选`() = runTest(testDispatcher) {
        // 初始默认选中 realSubtitleZh(index4,列表第一条,偏好语言不匹配 null 时的 fallback)。
        // 手动切换的"下一个"是 danmakuNoKeyword(index0)——标题信号放过了它,但解析后密度异常,
        // 应该继续跳到再下一个候选 realSubtitleEn(index5),而不是把弹幕留在屏幕上。
        val repo = mockk<SubtitleRepository>().also { repo ->
            coEvery { repo.load(any(), any(), 4) } returns SubtitleTimeline(listOf(SubtitleLine(0, 1000, "真字幕内容")))
            coEvery { repo.load(any(), any(), 0) } returns denseTimeline()
            coEvery { repo.load(any(), any(), 5) } returns SubtitleTimeline(listOf(SubtitleLine(0, 1000, "English content")))
        }
        val vm = buildViewModel(
            listOf(realSubtitleZh, danmakuNoKeyword, realSubtitleEn),
            repo = repo,
            mediaItem = episodeWithRuntime,
        )
        runCurrent()
        assertEquals(4, vm.uiState.value.selectedSubtitleTrackIndex)

        vm.onCycleSubtitleTrack()
        runCurrent()

        assertEquals(5, vm.uiState.value.selectedSubtitleTrackIndex)
        assertTrue(vm.uiState.value.subtitleTracks.none { it.index == 0 })
        assertFalse(vm.uiState.value.subtitleSkippedAsDanmaku)
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
