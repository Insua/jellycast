package dev.insua.jellycast.player

import dev.insua.jellycast.datastore.LastPlayed
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle

/**
 * 「从当前条目 + 位置构造出一条 [LastPlayed]」的决策——纯 Kotlin,不依赖 `PlaybackService`
 * (它在 JVM 单测里造不出来,决策逻辑留在 Service 里等于没有测试)。
 *
 * [title] / [subtitle] 直接复用 `:core:model` 的 [MediaItem.displaySubtitle],不重新实现
 * "剧名 · SxxExx" 的拼接规则——和 [toPlaybackDisplayMetadata] 同一份权威,理由见该文件类注释。
 *
 * [runTimeMs] 原样透传 `item.runTimeMs`——为 `null` 时不伪造一个假时长,由 UI 层决定怎么显示
 * "总时长未知"。
 *
 * [positionMs] 为 0(用户刚点开还没走)也照常产出记录:这次打开本身就值得被记住,不该因为
 * "还没有进度"就被当成没有意义而漏记。
 *
 * [kind][LastPlayed.kind] 原样透传 `item.kind.name`(复审 Task 5 Important 2):不记这个字段的话,
 * 冷启动恢复出来的条目在导航层就只能硬编码成 `EPISODE`——电影播完之后
 * [AutoPlayNextController.onPlaybackEnded] 会把它误判成"未完结的剧集"去找下一集,
 * 连播结束该有的"回首页"永远不会触发。
 *
 * [seriesName]/[seasonNumber]/[episodeNumber]/[seriesId]/[seasonId](Task 1,2026-08-06)同样原样
 * 透传,理由见 [LastPlayed] 类内对这五个字段的 KDoc:电影条目这五项在 [item] 上本就是 `null`,
 * 这里不额外判断,直接抄一遍就是正确的"电影没有季集"结果。
 */
fun buildLastPlayedRecord(
    item: MediaItem,
    positionMs: Long,
    now: Long = System.currentTimeMillis(),
): LastPlayed = LastPlayed(
    itemId = item.id,
    positionMs = positionMs,
    title = item.name,
    subtitle = item.displaySubtitle,
    imageTag = item.imageTag,
    runTimeMs = item.runTimeMs,
    updatedAt = now,
    kind = item.kind.name,
    seriesName = item.seriesName,
    seasonNumber = item.seasonNumber,
    episodeNumber = item.episodeNumber,
    seriesId = item.seriesId,
    seasonId = item.seasonId,
)
