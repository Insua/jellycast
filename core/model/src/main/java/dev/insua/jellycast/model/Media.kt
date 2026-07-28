package dev.insua.jellycast.model

import java.util.Locale

/** LIBRARY = 「我的媒体」库入口本身(GET /UserViews 返回的 CollectionFolder/UserView)。 */
enum class MediaKind { SERIES, SEASON, EPISODE, MOVIE, LIBRARY }

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
    // 追加在末尾且带默认值:已有的位置参数构造调用(散落在各模块的测试里)不受影响。
    // 未看集数(UserItemDataDto.UnplayedItemCount)——只对"最近添加"里的剧集条目有意义,
    // null 表示服务端没给这个字段,0 表示确实没有未看,两者都不该画角标(由 UI 层判断)。
    val unplayedItemCount: Int? = null,
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
