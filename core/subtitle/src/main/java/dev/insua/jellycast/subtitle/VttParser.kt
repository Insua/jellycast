package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

object VttParser : SubtitleParser {
    private val TIME = Regex(
        """(\d{2,}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2,}):(\d{2}):(\d{2})\.(\d{3})"""
    )

    override fun parse(content: String): List<SubtitleLine> =
        SubtitleTags.stripBom(content).replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            // 跳过 WEBVTT 头块与 NOTE 注释块;实测文件还带 Region: 头块
            // (区域样式定义,不含时间戳),按同样逻辑天然跳过。
            .filterNot {
                val t = it.trimStart()
                t.startsWith("WEBVTT") || t.startsWith("NOTE") || t.startsWith("Region:")
            }
            .mapNotNull { block ->
                val lines = block.trim().lines()
                val idx = lines.indexOfFirst { TIME.containsMatchIn(it) }
                if (idx < 0) return@mapNotNull null
                val g = (TIME.find(lines[idx]) ?: return@mapNotNull null).groupValues
                val start = toMs(g[1], g[2], g[3], g[4])
                val end = toMs(g[5], g[6], g[7], g[8])
                if (end <= start) return@mapNotNull null
                val text = lines.drop(idx + 1).joinToString("\n")
                    .let { SubtitleTags.stripTags(it) }
                if (text.isEmpty()) null else SubtitleLine(start, end, text)
            }
            .sortedBy { it.startMs }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()
}
