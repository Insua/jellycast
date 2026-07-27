package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleTimelineTest {
    private val timeline = SubtitleTimeline(listOf(
        SubtitleLine(1000, 3000, "第一句"),
        SubtitleLine(4000, 6000, "第二句"),
        SubtitleLine(10000, 12000, "第三句"),
    ))

    @Test fun `位置落在某行区间内返回该行`() {
        assertEquals(0, timeline.indexAt(2000))
        assertEquals(1, timeline.indexAt(5000))
        assertEquals(2, timeline.indexAt(11000))
    }

    @Test fun `边界值包含在内`() {
        assertEquals(0, timeline.indexAt(1000))
        assertEquals(0, timeline.indexAt(3000))
    }

    @Test fun `落在两行之间不高亮`() {
        assertEquals(-1, timeline.indexAt(3500))
        assertEquals(-1, timeline.indexAt(8000))
    }

    @Test fun `第一行之前不高亮`() {
        assertEquals(-1, timeline.indexAt(0))
    }

    @Test fun `空时间轴不崩溃`() {
        assertEquals(-1, SubtitleTimeline(emptyList()).indexAt(1000))
    }
}
