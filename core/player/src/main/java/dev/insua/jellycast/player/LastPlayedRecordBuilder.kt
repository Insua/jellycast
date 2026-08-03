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
)
