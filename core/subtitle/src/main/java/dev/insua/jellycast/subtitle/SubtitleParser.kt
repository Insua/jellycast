package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

interface SubtitleParser {
    fun parse(content: String): List<SubtitleLine>
}
