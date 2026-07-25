package dev.insua.jellycast.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 复审 Critical 1 在迷你条上的落地。旧实现是
 * `player.currentPosition / player.duration`:
 * - `currentPosition` 是转码流内的相对位置,从 8:00 续播时是 0 → 迷你条进度条归零;
 * - `duration` 在 chunked AAC 转码流上常是 `C.TIME_UNSET` → 整个进度条不显示。
 *
 * 现在位置来自 `AudioPlaybackEngine.absolutePositionMs`、时长来自元数据 `runTimeMs`,
 * 比例计算本身是这个纯函数,离线可测。
 */
class MiniPlayerProgressTest {

    @Test fun `按绝对位置与元数据时长算出比例`() {
        assertEquals(0.5f, miniPlayerProgress(positionMs = 750_000L, durationMs = 1_500_000L))
    }

    @Test fun `时长未知时返回 0 而不是除零`() {
        assertEquals(0f, miniPlayerProgress(positionMs = 500_000L, durationMs = null))
    }

    @Test fun `时长为 0 时返回 0`() {
        assertEquals(0f, miniPlayerProgress(positionMs = 500_000L, durationMs = 0L))
    }

    @Test fun `位置超过时长时钳制到 1,不画出溢出的进度条`() {
        assertEquals(1f, miniPlayerProgress(positionMs = 2_000_000L, durationMs = 1_500_000L))
    }

    @Test fun `负位置钳制到 0`() {
        assertEquals(0f, miniPlayerProgress(positionMs = -1_000L, durationMs = 1_500_000L))
    }
}
