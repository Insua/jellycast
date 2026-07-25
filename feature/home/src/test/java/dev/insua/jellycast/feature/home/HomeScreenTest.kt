package dev.insua.jellycast.feature.home

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// ---- 修正:继续收听卡片的进度条 —— 纯逻辑部分 ----
// resumeProgressFraction 是"听了多少"的换算,输入输出全是毫秒/Float,不接触 ticks、
// 不接触 Android,可以完全离线单测(CLAUDE.md 铁律 6)。

class HomeScreenTest {

    private fun item(resumePositionMs: Long, runTimeMs: Long?) = MediaItem(
        id = "1",
        kind = MediaKind.EPISODE,
        name = "第一集",
        runTimeMs = runTimeMs,
        resumePositionMs = resumePositionMs,
    )

    @Test
    fun `正常进度返回听完比例`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = 1_200_000).resumeProgressFraction()

        assertEquals(0.25f, fraction)
    }

    @Test
    fun `runTimeMs为null时不显示进度`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = null).resumeProgressFraction()

        assertNull(fraction, "没有总时长就无法算比例,不能除以 null")
    }

    @Test
    fun `runTimeMs为0时不显示进度`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = 0).resumeProgressFraction()

        assertNull(fraction, "总时长为 0 会除零,必须当作没有进度处理")
    }

    @Test
    fun `resumePositionMs为0时不显示进度`() {
        val fraction = item(resumePositionMs = 0, runTimeMs = 1_200_000).resumeProgressFraction()

        assertNull(fraction, "一秒都没听过的条目不算'进行中',不应该出现进度条")
    }

    @Test
    fun `resumePositionMs超过runTimeMs时钳制为1`() {
        val fraction = item(resumePositionMs = 1_500_000, runTimeMs = 1_200_000).resumeProgressFraction()

        assertEquals(1f, fraction, "服务端上报的位置理论上不应该超过总时长,但客户端必须钳制,不能画出超过 100% 的进度条")
    }
}
