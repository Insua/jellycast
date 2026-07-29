package dev.insua.jellycast.subtitle

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机(ICU 正则引擎)冒烟测试:`:core:subtitle` 的解析逻辑是纯 Kotlin,历来只有 JVM 单测覆盖。
 * 但它最终跑在 Android 上,而 Android 从 14 起把 `java.util.regex` 换成 ICU 后端,和 JVM 单测
 * 实际执行所在的 OpenJDK 不是同一个正则实现——`SubtitleTags` 里一个未转义的 `}` 正是在这道缝隙里
 * 逃过了 421 个 JVM 单测,详见
 * docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2。
 *
 * 覆盖每一个解析入口:[SrtParser]、[VttParser]、[AssParser]、[parserFor]、以及三者共用的
 * [SubtitleTags] 标签剥离逻辑。样本形态取自真实 Jellyfin 转出数据里踩过的坑:ASS 特效覆盖标签
 * `{\pos(...)}`、UTF-8 BOM、零时长条目、`\N` 换行。
 *
 * 断言纪律:断"解析结果正确",不是"没有抛异常"——一个把所有输入都解析成空列表的解析器,
 * 也能让"没抛异常"这条断言通过。
 */
@RunWith(AndroidJUnit4::class)
class SubtitleParsingSmokeTest {

    // ------------------------------------------------------------------ SrtParser

    @Test
    fun srtParser_剥离效果标签_过滤零时长_保留多行文本() {
        val content = "﻿1\n" +
            "00:00:01,000 --> 00:00:03,500\n" +
            "Hello <i>world</i> {\\pos(352,483)}\n" +
            "\n" +
            "2\n" +
            "00:00:03,500 --> 00:00:03,500\n" +
            "zero duration entry\n" +
            "\n" +
            "3\n" +
            "00:00:05,000 --> 00:00:07,000\n" +
            "Line one\n" +
            "Line two\n"

        val lines = SrtParser.parse(content)

        assertEquals("零时长条目必须被过滤,只剩 2 行", 2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(3_500L, lines[0].endMs)
        assertEquals("HTML 标签与 ASS 特效标签都要剥离", "Hello world", lines[0].text)
        assertEquals(5_000L, lines[1].startMs)
        assertEquals(7_000L, lines[1].endMs)
        assertEquals("多行文本保留换行", "Line one\nLine two", lines[1].text)
    }

    // ------------------------------------------------------------------ VttParser

    @Test
    fun vttParser_跳过头块与注释_剥离标签_过滤零时长() {
        val content = "﻿WEBVTT\n\n" +
            "NOTE 这一块应被跳过\n\n" +
            "Region: id=fred width=40% lines=3 regionanchor=0%,100% viewportanchor=10%,90% scroll=up\n\n" +
            "1\n" +
            "00:00:01.000 --> 00:00:03.500\n" +
            "Hello <b>world</b> {\\pos(352,483)}\n\n" +
            "2\n" +
            "00:00:03.500 --> 00:00:03.500\n" +
            "zero duration\n\n" +
            "3\n" +
            "00:00:05.000 --> 00:00:07.000\n" +
            "Line one\nLine two\n"

        val lines = VttParser.parse(content)

        assertEquals("WEBVTT/NOTE/Region 头块与零时长条目都不计入结果", 2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(3_500L, lines[0].endMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(5_000L, lines[1].startMs)
        assertEquals(7_000L, lines[1].endMs)
        assertEquals("Line one\nLine two", lines[1].text)
    }

    // ------------------------------------------------------------------ AssParser

    @Test
    fun assParser_剥离效果标签_替换换行符_过滤零时长() {
        val content = "﻿[Script Info]\n" +
            "Title: smoke\n" +
            "\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\\pos(100,200)}Line one\\NLine two\n" +
            "Dialogue: 0,0:00:00.00,0:00:00.00,Default,,0,0,0,,zero duration entry\n" +
            "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,normal line\n"

        val lines = AssParser.parse(content)

        assertEquals("零时长条目必须被过滤,只剩 2 行", 2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(3_500L, lines[0].endMs)
        assertEquals("效果标签剥离,\\N 换成真实换行", "Line one\nLine two", lines[0].text)
        assertEquals(5_000L, lines[1].startMs)
        assertEquals(6_000L, lines[1].endMs)
        assertEquals("normal line", lines[1].text)
    }

    // ------------------------------------------------------------------ parserFor

    @Test
    fun parserFor_按格式选择解析器_未知格式兜底为SRT() {
        assertTrue(parserFor("srt") === SrtParser)
        assertTrue(parserFor("SRT") === SrtParser)
        assertTrue(parserFor("subrip") === SrtParser)
        assertTrue(parserFor("vtt") === VttParser)
        assertTrue(parserFor("webvtt") === VttParser)
        assertTrue(parserFor("ass") === AssParser)
        assertTrue(parserFor("ssa") === AssParser)
        assertTrue("未知格式兜底为 SRT", parserFor("unknown-format") === SrtParser)
    }

    // ------------------------------------------------------------------ SubtitleTags

    @Test
    fun subtitleTags_剥离BOM() {
        assertEquals("hello", SubtitleTags.stripBom("﻿hello"))
        assertEquals("no bom here", SubtitleTags.stripBom("no bom here"))
    }

    @Test
    fun subtitleTags_同时剥离HTML标签与ASS特效标签() {
        val stripped = SubtitleTags.stripTags("<i>Hello world</i>{\\pos(100,200)}")
        assertEquals("Hello world", stripped)
    }

    @Test
    fun subtitleTags_效果标签正则可以编译并匹配真实样本() {
        // 这就是根因所在的那一行:{\pos(352,483)} 这类 ASS 覆盖标签必须被完整剥离,
        // 而不是让正则编译本身就失败(ICU 对未转义的裸 `}` 是语法错误)。
        val stripped = SubtitleTags.stripTags("前缀{\\fn方正准圆_GBK}{\\pos(352,483)}后缀")
        assertEquals("前缀后缀", stripped)
    }
}
