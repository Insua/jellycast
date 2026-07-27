package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VttParserTest {
    private val sample = """
        WEBVTT

        00:00:01.000 --> 00:00:03.500
        你好，世界

        00:01:02.250 --> 00:01:05.000 align:start position:10%
        带样式参数的一行
    """.trimIndent()

    @Test fun `跳过 WEBVTT 头`() = assertEquals(2, VttParser.parse(sample).size)

    @Test fun `点号毫秒分隔符正确解析`() {
        assertEquals(3500L, VttParser.parse(sample)[0].endMs)
    }

    @Test fun `忽略时间行后的样式参数`() {
        assertEquals("带样式参数的一行", VttParser.parse(sample)[1].text)
    }

    // --- Spike-2 真实数据坑 ---

    @Test fun `跳过 Region 头块并忽略 cue 设置`() {
        val real = """
            WEBVTT

            Region: id:subtitle width:80% lines:3 regionanchor:50%,100% viewportanchor:50%,90%

            00:00:01.700 --> 00:00:02.590 region:subtitle line:90%
            你好
        """.trimIndent()
        val lines = VttParser.parse(real)
        assertEquals(1, lines.size)
        assertEquals("你好", lines[0].text)
        assertEquals(1700L, lines[0].startMs)
        assertEquals(2590L, lines[0].endMs)
    }

    @Test fun `剥离花括号特效标签`() {
        val s = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n{\\pos(100,200)}带特效标签"
        assertEquals("带特效标签", VttParser.parse(s)[0].text)
    }

    @Test fun `零时长条目被过滤`() {
        val s = "WEBVTT\n\n00:00:00.000 --> 00:00:00.000\n垃圾标记\n\n" +
            "00:00:05.000 --> 00:00:06.000\n正常字幕"
        val lines = VttParser.parse(s)
        assertEquals(1, lines.size)
        assertEquals("正常字幕", lines[0].text)
    }

    @Test fun `UTF-8 BOM 不影响解析`() {
        val s = "﻿WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n带 BOM 的字幕"
        val lines = VttParser.parse(s)
        assertEquals(1, lines.size)
        assertEquals("带 BOM 的字幕", lines[0].text)
    }
}
