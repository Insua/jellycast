package dev.insua.jellycast.model

import java.util.Locale

/**
 * COLLECTION = 合集(BoxSet)本身;LIBRARY = 「我的媒体」库入口本身
 * (GET /UserViews 返回的 CollectionFolder/UserView)。两者都是"容器",点进去才是内容。
 */
enum class MediaKind { SERIES, SEASON, EPISODE, MOVIE, COLLECTION, LIBRARY }

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
    // 收藏 / 已看状态(UserItemDataDto.IsFavorite / Played)。默认 false——服务端没给
    // UserData 时(理论上不该发生,但防御性地)不冒充"已收藏"/"已看"。
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
    // 剧集归属(BaseItemDto.SeriesId / SeasonId)。自动连播「跟着剧走」只认这两个字段:
    // 有了它们才能问服务端"本季还有没有下一集""下一季的第一集是哪个",而不必依赖播放队列
    // 恰好装的是本剧的集序(首页分区把整行当队列,那一行里可能是别的剧)。
    // 非剧集条目(电影/合集/库入口)为 null。
    val seriesId: String? = null,
    val seasonId: String? = null,
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
