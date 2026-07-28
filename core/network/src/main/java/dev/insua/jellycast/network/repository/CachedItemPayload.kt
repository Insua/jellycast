package dev.insua.jellycast.network.repository

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `cached_item.payloadJson` 的磁盘格式。
 *
 * ## 为什么不直接给 [MediaItem] 打 `@Serializable`
 *
 * [MediaItem] 是 `:core:model` 里的领域模型,那个模块刻意没有任何依赖(纯 JVM、可离线单测)。
 * 更重要的是:**磁盘格式和领域模型的生命周期不一样**。领域模型会随需求改字段名、拆枚举;
 * 磁盘上却躺着上一个版本写下的数据。把两者绑成同一个类,一次无害的重命名就会让老缓存
 * 静默解析失败 —— 而缓存解析失败的表现恰恰是"打开 App 一片空白",跟这次要修的缺陷一模一样。
 *
 * 所以这里是一份显式的、只增不改的快照格式:
 * - 除主键三件套外所有字段都有默认值 → 新增字段读老数据不炸;
 * - [json] 开 `ignoreUnknownKeys` → 删字段后读新数据也不炸;
 * - [kind] 存枚举名的字符串而不是序号 → 枚举重排不会让"电影"变成"剧集"。
 *
 * 单位:**毫秒**。ticks 只在 `MediaItemMapper` 换算一次,缓存里绝不出现 ticks。
 */
@Serializable
internal data class CachedItemPayloadV1(
    val id: String,
    val kind: String,
    val name: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val runTimeMs: Long? = null,
    val resumePositionMs: Long = 0L,
    val imageTag: String? = null,
    val unplayedItemCount: Int? = null,
    // 收藏 / 已看:同样只增不改,默认值让老缓存(没有这两个字段的行)照常解码成"未收藏/未看",
    // 而不是解析失败。
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
)

internal object CachedItemPayload {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(item: MediaItem): String = json.encodeToString(
        CachedItemPayloadV1(
            id = item.id,
            kind = item.kind.name,
            name = item.name,
            seriesName = item.seriesName,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            runTimeMs = item.runTimeMs,
            resumePositionMs = item.resumePositionMs,
            imageTag = item.imageTag,
            unplayedItemCount = item.unplayedItemCount,
            isFavorite = item.isFavorite,
            isPlayed = item.isPlayed,
        ),
    )

    /**
     * 解码单条缓存。**任何失败都返回 null,绝不抛异常** —— 一行被截断的 JSON 不该让整个
     * 首页崩掉(铁律:网络/缓存的失败路径必须收敛成"少显示一点",不是崩溃)。
     * 调用方用 `mapNotNull` 跳过坏行,与 `BaseItemDto.toMediaItem()` 对陌生类型的处理一致。
     */
    fun decode(payloadJson: String): MediaItem? = runCatching {
        val payload = json.decodeFromString<CachedItemPayloadV1>(payloadJson)
        val kind = MediaKind.entries.firstOrNull { it.name == payload.kind } ?: return null
        MediaItem(
            id = payload.id,
            kind = kind,
            name = payload.name,
            seriesName = payload.seriesName,
            seasonNumber = payload.seasonNumber,
            episodeNumber = payload.episodeNumber,
            runTimeMs = payload.runTimeMs,
            resumePositionMs = payload.resumePositionMs,
            imageTag = payload.imageTag,
            unplayedItemCount = payload.unplayedItemCount,
            isFavorite = payload.isFavorite,
            isPlayed = payload.isPlayed,
        )
    }.getOrNull()
}
