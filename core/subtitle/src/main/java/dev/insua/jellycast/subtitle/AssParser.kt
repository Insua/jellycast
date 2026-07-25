package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

object AssParser : SubtitleParser {

    override fun parse(content: String): List<SubtitleLine> =
        SubtitleTags.stripBom(content).replace("\r\n", "\n").lines()
            .filter { it.startsWith("Dialogue:") }
            .mapNotNull { line ->
                // Dialogue 前 9 个字段固定,第 10 个字段(Text)之后的逗号属于文本本身
                val body = line.removePrefix("Dialogue:").trim()
                val parts = body.split(",", limit = 10)
                if (parts.size < 10) return@mapNotNull null
                val start = parseAssTime(parts[1].trim()) ?: return@mapNotNull null
                val end = parseAssTime(parts[2].trim()) ?: return@mapNotNull null
                if (end <= start) return@mapNotNull null
                val text = SubtitleTags.stripTags(parts[9].replace("\\N", "\n"))
                if (text.isEmpty()) null else SubtitleLine(start, end, text)
            }
            .sortedBy { it.startMs }

    /** ASS 时间格式:H:MM:SS.cc(厘秒) */
    private fun parseAssTime(t: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""").find(t) ?: return null
        val g = m.groupValues
        return g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 +
            g[3].toLong() * 1_000 + g[4].toLong() * 10
    }
}
