package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SrtParserTest {
    private val sample = """
        1
        00:00:01,000 --> 00:00:03,500
        你好，世界

        2
        00:01:02,250 --> 00:01:05,000
        第二行字幕
        跨两行显示
    """.trimIndent()

    @Test fun `解析出正确的条数`() {
        assertEquals(2, SrtParser.parse(sample).size)
    }

    @Test fun `时间戳换算为毫秒`() {
        val lines = SrtParser.parse(sample)
        assertEquals(1000L, lines[0].startMs)
        assertEquals(3500L, lines[0].endMs)
        assertEquals(62250L, lines[1].startMs)
        assertEquals(65000L, lines[1].endMs)
    }

    @Test fun `多行文本用换行合并`() {
        assertEquals("第二行字幕\n跨两行显示", SrtParser.parse(sample)[1].text)
    }

    @Test fun `剥离 HTML 样式标签`() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\n<i>斜体</i><b>粗体</b>"
        assertEquals("斜体粗体", SrtParser.parse(s)[0].text)
    }

    @Test fun `畸形输入不抛异常并跳过坏块`() {
        val bad = "1\n乱七八糟\n文本\n\n2\n00:00:05,000 --> 00:00:06,000\n正常"
        val lines = SrtParser.parse(bad)
        assertEquals(1, lines.size)
        assertEquals("正常", lines[0].text)
    }

    @Test fun `空输入返回空列表`() {
        assertTrue(SrtParser.parse("").isEmpty())
    }

    @Test fun `输出按开始时间升序`() {
        val out = "2\n00:00:10,000 --> 00:00:11,000\nB\n\n1\n00:00:01,000 --> 00:00:02,000\nA"
        assertEquals(listOf("A", "B"), SrtParser.parse(out).map { it.text })
    }

    // --- Spike-2 真实数据坑 ---

    @Test fun `剥离 ASS 特效花括号标签 - 真实样例1`() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\n" +
            "你{\\fn方正准圆_GBK}啰{\\r}里{\\fn方正准圆_GBK}啰{\\r}嗦个头 快放老子出去"
        assertEquals("你啰里啰嗦个头 快放老子出去", SrtParser.parse(s)[0].text)
    }

    @Test fun `剥离 ASS 特效花括号标签 - 真实样例2`() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\n" +
            "{\\pos(352,483)}（处女大碟播放禁止之后半年)"
        assertEquals("（处女大碟播放禁止之后半年)", SrtParser.parse(s)[0].text)
    }

    @Test fun `零时长条目被过滤`() {
        val s = "1\n00:00:00,000 --> 00:00:00,000\n=============正文=========\n\n" +
            "2\n00:00:05,000 --> 00:00:06,000\n正常字幕"
        val lines = SrtParser.parse(s)
        assertEquals(1, lines.size)
        assertEquals("正常字幕", lines[0].text)
    }

    @Test fun `UTF-8 BOM 不影响解析`() {
        val s = "﻿1\n00:00:01,000 --> 00:00:02,000\n带 BOM 的字幕"
        val lines = SrtParser.parse(s)
        assertEquals(1, lines.size)
        assertEquals("带 BOM 的字幕", lines[0].text)
        assertEquals(1000L, lines[0].startMs)
    }
}
