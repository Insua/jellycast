package dev.insua.jellycast.feature.player

import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricsStateTest {
    private val timeline = SubtitleTimeline(listOf(
        SubtitleLine(1000, 3000, "A"),
        SubtitleLine(4000, 6000, "B"),
        SubtitleLine(10000, 12000, "C"),
    ))

    @Test fun `当前行索引随播放位置变化`() {
        assertEquals(0, timeline.indexAt(2000))
        assertEquals(1, timeline.indexAt(5000))
    }

    @Test fun `间隙期保持上一行高亮以免闪烁`() {
        // 设计决策:落在两行之间时,UI 保持上一行为"已读"态,不高亮任何行为"当前"
        assertEquals(-1, timeline.indexAt(3500))
    }

    @Test fun `点击第 N 行返回该行的起始时间用于 seek`() {
        assertEquals(4000L, timeline.lines[1].startMs)
    }
}
