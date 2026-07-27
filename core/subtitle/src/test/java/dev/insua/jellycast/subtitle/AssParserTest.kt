package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssParserTest {
    private val sample = """
        [Script Info]
        Title: test

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,你好，世界
        Dialogue: 0,0:01:02.25,0:01:05.00,Default,,0,0,0,,{\pos(100,200)}带特效标签
    """.trimIndent()

    @Test fun `只解析 Dialogue 行`() = assertEquals(2, AssParser.parse(sample).size)

    @Test fun `厘秒换算为毫秒`() {
        val l = AssParser.parse(sample)[0]
        assertEquals(1000L, l.startMs)
        assertEquals(3500L, l.endMs)
    }

    @Test fun `剥离花括号特效标签`() {
        assertEquals("带特效标签", AssParser.parse(sample)[1].text)
    }

    @Test fun `文本中的逗号不被截断`() {
        val s = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,前半句，后半句"
        assertEquals("前半句，后半句", AssParser.parse(s)[0].text)
    }

    // --- Spike-2 真实数据坑 ---

    @Test fun `零时长条目被过滤`() {
        val s = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:00.00,0:00:00.00,Default,,0,0,0,,=============正文=========\n" +
            "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,正常字幕"
        val lines = AssParser.parse(s)
        assertEquals(1, lines.size)
        assertEquals("正常字幕", lines[0].text)
    }
}
