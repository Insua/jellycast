package dev.insua.jellycast.model

import java.util.Locale

enum class MediaKind { SERIES, SEASON, EPISODE, MOVIE }

data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val name: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val runTimeMs: Long? = null,
    val resumePositionMs: Long = 0L,
    val imageTag: String? = null,
)

/** 播放页展示用的标题组合 */
val MediaItem.displaySubtitle: String
    get() = when (kind) {
        MediaKind.EPISODE -> buildString {
            seriesName?.let { append(it) }
            if (seasonNumber != null && episodeNumber != null) {
                append(" · S%02dE%02d".format(Locale.ROOT, seasonNumber, episodeNumber))
            }
        }
        else -> seriesName.orEmpty()
    }
