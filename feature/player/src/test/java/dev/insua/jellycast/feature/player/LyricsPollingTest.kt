package dev.insua.jellycast.feature.player

import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 缺陷 3(设计文档 §3.5):固定 500ms 轮询漏掉约 85% 的字幕行——很多行比半个轮询周期还短,
 * 采样点两次都落在行外。修复:不再固定 delay,而是用 [SubtitleTimeline.nextBoundaryAfter] 算出
 * "到下一次可能变化的高亮还有多久",把这次睡眠精确设成那个时长(按倍速折算),这样每条边界都
 * 恰好被采样命中一次。暂停播放或关闭歌词开关时退回基线间隔,不做无意义的高频轮询——
 * 这是本次要在报告里说明的功耗权衡。
 */
class LyricsPollingTest {

    private val timeline = SubtitleTimeline(listOf(
        SubtitleLine(1000, 1200, "很短的一行"),   // 只有 200ms,固定 500ms 轮询很容易跳过
        SubtitleLine(4000, 6000, "第二行"),
    ))

    @Test fun `暂停时退回基线间隔不做精确调度`() {
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 900L, lyricsEnabled = true)
        assertEquals(LYRICS_POLL_INTERVAL_MS, nextLyricsPollDelayMs(isPlaying = false, state = state))
    }

    @Test fun `关闭歌词开关时退回基线间隔`() {
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 900L, lyricsEnabled = false)
        assertEquals(LYRICS_POLL_INTERVAL_MS, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }

    @Test fun `没有更多字幕边界时退回基线间隔`() {
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 999_999L, lyricsEnabled = true)
        assertEquals(LYRICS_POLL_INTERVAL_MS, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }

    @Test fun `迫近的短行边界会把睡眠精确设成到边界为止`() {
        // 900ms 位置,下一个边界是这一行的 startMs=1000,只差 100ms —— 精确到 100ms,不是 500ms。
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 900L, lyricsEnabled = true, playbackSpeed = 1.0f)
        assertEquals(100L, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }

    @Test fun `远处的边界仍然被基线间隔封顶不会一次性睡太久`() {
        // 900ms 位置,下一个边界是 1000(100ms 后)。换一个远的场景:位置 3000,下一个边界是
        // 4000(第二行开始),距离 1000ms > 基线 500ms,必须封顶在 500ms,不能一口气睡 1000ms
        // (那样万一途中有更早的边界就会被错过 —— 但这里只有这一条边界,主要验证封顶行为)。
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 3000L, lyricsEnabled = true, playbackSpeed = 1.0f)
        assertEquals(LYRICS_POLL_INTERVAL_MS, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }

    @Test fun `倍速会按比例折算等待时长`() {
        // 边界在 1000,当前位置 700(内容时间差 300ms,故意避开地板值 80ms 的干扰);
        // 2 倍速播放时内容时间前进两倍快,真实等待时间只需要一半:150ms。
        val state = PlayerUiState(subtitleTimeline = timeline, positionMs = 700L, lyricsEnabled = true, playbackSpeed = 2.0f)
        assertEquals(150L, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }

    @Test fun `病态输入下有地板值防止忙轮询`() {
        val denseTimeline = SubtitleTimeline(listOf(
            SubtitleLine(1000, 1001, "A"),
            SubtitleLine(1002, 1003, "B"),
        ))
        val state = PlayerUiState(subtitleTimeline = denseTimeline, positionMs = 999L, lyricsEnabled = true, playbackSpeed = 1.0f)
        assertEquals(LYRICS_MIN_POLL_INTERVAL_MS, nextLyricsPollDelayMs(isPlaying = true, state = state))
    }
}
