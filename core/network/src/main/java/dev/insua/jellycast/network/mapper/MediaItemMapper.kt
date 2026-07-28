package dev.insua.jellycast.network.mapper

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.dto.BaseItemDto

private const val TICKS_PER_MS = 10_000L

/**
 * DTO → 领域模型的唯一换算点。项目铁律:ticks(1 tick = 100ns)只在这里换算一次,
 * ViewModel / UI 层禁止再出现 ticks。
 *
 * 返回 null 表示 [BaseItemDto.type] 不是浏览页认识的五种(Series/Season/Episode/Movie/BoxSet,
 * 精确大小写核对自 docs/jellyfin-openapi.json 的 BaseItemKind 枚举)——调用方应该用
 * `mapNotNull` 跳过这类条目,不能让整页因为一条陌生类型的数据而崩溃。
 */
fun BaseItemDto.toMediaItem(): MediaItem? {
    val kind = type.toMediaKindOrNull() ?: return null

    // Jellyfin 对"季号"的字段选择因类型而异:Season 条目自己的季号在 IndexNumber
    // (本 DTO 反序列化进 episodeNumber 字段);Episode 条目所属的季号在 ParentIndexNumber
    // (反序列化进 seasonNumber 字段)。两者语义不同,必须按 kind 分流,不能照抄字段名。
    val resolvedSeasonNumber = if (kind == MediaKind.SEASON) episodeNumber else seasonNumber
    val resolvedEpisodeNumber = if (kind == MediaKind.SEASON) null else episodeNumber

    return MediaItem(
        id = id,
        kind = kind,
        name = name,
        seriesName = seriesName,
        seasonNumber = resolvedSeasonNumber,
        episodeNumber = resolvedEpisodeNumber,
        runTimeMs = runTimeTicks?.let { it / TICKS_PER_MS },
        resumePositionMs = (userData?.positionTicks ?: 0L) / TICKS_PER_MS,
        imageTag = imageTags?.get("Primary"),
        unplayedItemCount = userData?.unplayedItemCount,
    )
}

// "CollectionFolder" / "UserView" 是 GET /UserViews(「我的媒体」库入口)返回条目的
// BaseItemKind,核对自 docs/jellyfin-openapi.json 的 BaseItemKind 枚举(两者都在列)。
private fun String.toMediaKindOrNull(): MediaKind? = when (this) {
    "Series" -> MediaKind.SERIES
    "Season" -> MediaKind.SEASON
    "Episode" -> MediaKind.EPISODE
    "Movie" -> MediaKind.MOVIE
    // 合集(设计文档 §3.7)。BaseItemKind 枚举里的精确拼写 "BoxSet" 已用
    // jq '.components.schemas.BaseItemKind.enum | index("BoxSet")' docs/jellyfin-openapi.json 核对。
    "BoxSet" -> MediaKind.COLLECTION
    // 「我的媒体」库入口:/UserViews 返回的类型。
    "CollectionFolder", "UserView" -> MediaKind.LIBRARY
    else -> null
}

/**
 * 封面 URL(设计文档 §7):`GET /Items/{itemId}/Images/Primary?maxWidth=…`。
 * 参数大小写核对自 docs/jellyfin-openapi.json 的 GetItemImage(`/Items/{itemId}/Images/{imageType}`):
 * 查询参数是 `maxWidth` / `tag`,均为小写开头驼峰,不是 `MaxWidth`/`Tag`。
 *
 * 没有 imageTag 时返回 null,交给调用方(PosterCard 的占位背景)兜底,而不是拼一个大概率
 * 404 的 URL 丢给 Coil。
 */
fun MediaItem.posterUrl(baseUrl: String, maxWidth: Int = 400): String? {
    val tag = imageTag ?: return null
    val base = baseUrl.trimEnd('/')
    return "$base/Items/$id/Images/Primary?maxWidth=$maxWidth&tag=$tag"
}
