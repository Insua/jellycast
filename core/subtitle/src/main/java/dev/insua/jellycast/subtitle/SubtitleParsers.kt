package dev.insua.jellycast.subtitle

/** 按 Jellyfin 字幕流格式选择解析器;未知格式兜底为 SRT。 */
fun parserFor(format: String): SubtitleParser = when (format.lowercase()) {
    "srt", "subrip" -> SrtParser
    "vtt", "webvtt" -> VttParser
    "ass", "ssa" -> AssParser
    else -> SrtParser
}
