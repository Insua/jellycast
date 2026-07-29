package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test fun `最后一行之后不高亮`() {
        assertEquals(-1, timeline.indexAt(20000))
    }

    // 缺陷 2(设计文档 §3.4):indexAt 原实现假设"有序、不重叠",弹幕轨实测大量重叠——
    // 3676 条里只有 53 条被高亮。规则改为:覆盖当前位置的候选行里,取"最晚开始"的那一条。
    private val overlapping = SubtitleTimeline(listOf(
        SubtitleLine(0, 5000, "弹幕1"),      // 0
        SubtitleLine(1000, 2000, "弹幕2"),   // 1
        SubtitleLine(1500, 6000, "真字幕"),  // 2
    ))

    @Test fun `重叠区间取覆盖当前位置且最晚开始的一条`() {
        // 1800ms 同时被 0、1、2 三条覆盖,最晚开始的是 index 2(1500)
        assertEquals(2, overlapping.indexAt(1800))
        // 500ms 只被 index 0 覆盖
        assertEquals(0, overlapping.indexAt(500))
        // 1200ms 被 index 0、1 覆盖,最晚开始的是 index 1(1000)
        assertEquals(1, overlapping.indexAt(1200))
    }

    @Test fun `重叠区间里位置落在空隙仍不高亮`() {
        val timeline = SubtitleTimeline(listOf(
            SubtitleLine(0, 1000, "A"),
            SubtitleLine(0, 500, "B"),
            SubtitleLine(3000, 4000, "C"),
        ))
        assertEquals(-1, timeline.indexAt(2000))
    }

    @Test fun `重叠区间里早于第一条不高亮`() {
        assertEquals(-1, overlapping.indexAt(-1))
    }

    @Test fun `重叠区间里晚于最后一条不高亮`() {
        assertEquals(-1, overlapping.indexAt(9000))
    }

    @Test fun `多条覆盖同一时刻且开始时间相同时取靠后的一条`() {
        val timeline = SubtitleTimeline(listOf(
            SubtitleLine(1000, 1300, "先出现在列表里的"),
            SubtitleLine(1000, 4000, "后出现在列表里的"),
        ))
        assertEquals(1, timeline.indexAt(1200))
    }

    // 缺陷 3(设计文档 §3.5):自适应轮询需要知道"下一个可能改变高亮的时间点"是多久之后,
    // 才能把这次 delay 精确设成到那个点为止,而不是固定 500ms 碰运气。
    @Test fun `nextBoundaryAfter 返回严格大于当前位置的最近边界`() {
        // 边界集合:各行的 startMs 和 endMs+1 → {1000,3001,4000,6001,10000,12001}
        assertEquals(1000L, timeline.nextBoundaryAfter(0))
        assertEquals(3001L, timeline.nextBoundaryAfter(2000))
        assertEquals(4000L, timeline.nextBoundaryAfter(3001))
        assertEquals(12001L, timeline.nextBoundaryAfter(10000))
    }

    @Test fun `nextBoundaryAfter 晚于最后一条边界时返回 null`() {
        assertEquals(null, timeline.nextBoundaryAfter(12001))
        assertEquals(null, timeline.nextBoundaryAfter(999_999))
    }

    @Test fun `nextBoundaryAfter 空时间轴返回 null`() {
        assertEquals(null, SubtitleTimeline(emptyList()).nextBoundaryAfter(0))
    }

    // 全支线复审 Important:标题关键字(danmu/danmaku/弹幕)只是弹幕识别的信号之一,覆盖不了
    // 命名花样百出的场景(日文コメント、通用 Track 1、纯数字标签)。设计文档 §3.3 明确写了第二个
    // 独立信号:条目数异常多。这里用"密度"(条目数 / 分钟)而不是原始条目数——原始数字对短集数
    // 偏保守、对长片(90 分钟电影本来对白就该比 20 分钟番剧多)偏激进,密度把片长这个变量除掉了。
    // 实测样本:25 分钟集数,真字幕 774 条(≈31/分钟),外挂弹幕 3676 条(≈147/分钟)。
    private fun denseTimeline(count: Int): SubtitleTimeline =
        SubtitleTimeline((0 until count).map { SubtitleLine(it * 100L, it * 100L + 50L, "line$it") })

    @Test fun `密度异常判定为疑似弹幕`() {
        val danmakuLike = denseTimeline(3676)
        assertTrue(danmakuLike.isSuspiciouslyDense(runTimeMs = 25 * 60_000L))
    }

    @Test fun `正常对白密度不判定为疑似弹幕`() {
        val dialogue = denseTimeline(774)
        assertFalse(dialogue.isSuspiciouslyDense(runTimeMs = 25 * 60_000L))
    }

    @Test fun `更长片长的正常对白行数更多也不应被误判`() {
        // 密度而非原始条目数是关键:2000 条比 774 条多,但摊在 90 分钟电影里密度反而更低。
        val movieDialogue = denseTimeline(2000)
        assertFalse(movieDialogue.isSuspiciouslyDense(runTimeMs = 90 * 60_000L))
    }

    @Test fun `时长未知或非正数时不判定密度`() {
        val danmakuLike = denseTimeline(3676)
        assertFalse(danmakuLike.isSuspiciouslyDense(runTimeMs = 0L))
        assertFalse(danmakuLike.isSuspiciouslyDense(runTimeMs = -1L))
    }

    @Test fun `空时间轴不判定密度`() {
        assertFalse(SubtitleTimeline(emptyList()).isSuspiciouslyDense(runTimeMs = 25 * 60_000L))
    }
}
