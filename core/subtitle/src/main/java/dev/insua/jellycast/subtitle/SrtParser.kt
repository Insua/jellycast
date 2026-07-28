package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

object SrtParser : SubtitleParser {

    // by lazy 而非属性初始化器:见 SubtitleTags 的说明——避免正则编译失败以不可捕获的
    // ExceptionInInitializerError 形式从 object 的静态初始化里逃逸。
    private val TIME by lazy {
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    }

    override fun parse(content: String): List<SubtitleLine> =
        SubtitleTags.stripBom(content).replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .mapNotNull { parseBlock(it.trim()) }
            .sortedBy { it.startMs }

    private fun parseBlock(block: String): SubtitleLine? {
        if (block.isBlank()) return null
        val lines = block.lines()
        val timeIdx = lines.indexOfFirst { TIME.containsMatchIn(it) }
        if (timeIdx < 0) return null
        val m = TIME.find(lines[timeIdx]) ?: return null
        val g = m.groupValues
        val start = toMs(g[1], g[2], g[3], g[4])
        val end = toMs(g[5], g[6], g[7], g[8])
        // Jellyfin 转出的字幕里存在零时长的制作组标记条目,过滤掉,
        // 否则 SubtitleTimeline.indexAt(0) 会命中并高亮这类垃圾行。
        if (end <= start) return null
        val text = lines.drop(timeIdx + 1)
            .joinToString("\n").let { SubtitleTags.stripTags(it) }
        if (text.isEmpty()) return null
        return SubtitleLine(start, end, text)
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 +
            ms.padEnd(3, '0').toLong()
}
