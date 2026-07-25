package dev.insua.jellycast.player

import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 自动连播:由调用方(播放会话)在收到 `Player.STATE_ENDED` 时调用 [onPlaybackEnded]。
 *
 * 决策顺序(计划 Task 11 Step 5 + 控制者修正 §2):
 * 1. `autoPlayNext` 为 false → 不连播。
 * 2. [PlayQueue.hasNext] → [PlayQueue.next],解析新的 [PlaybackSource]。
 * 3. 队列已耗尽 → 调 [JellyfinApi.nextUp] 补充队列,取补充后的第一项解析;NextUp 也空(或调用
 *    失败)则不连播,不向上抛错——和字幕/进度上报同样的"静默降级,绝不打断播放"原则。
 *
 * 只依赖 [PlayQueue](纯 Kotlin,已单测)、[PlaybackSourceProvider](fun interface,单测可造假)、
 * [JellyfinApi](MockK 可造假)、`autoPlayNext: Flow<Boolean>`——不依赖真实播放器,离线可单测。
 * 次构造函数直接接 [PreferencesStore],生产环境用 `PreferencesStore.autoPlayNext` 构造。
 */
class AutoPlayNextController(
    private val queue: PlayQueue,
    private val sourceProvider: PlaybackSourceProvider,
    private val api: JellyfinApi,
    private val autoPlayNext: Flow<Boolean>,
) {
    constructor(
        queue: PlayQueue,
        sourceProvider: PlaybackSourceProvider,
        api: JellyfinApi,
        preferencesStore: PreferencesStore,
    ) : this(queue, sourceProvider, api, preferencesStore.autoPlayNext)

    suspend fun onPlaybackEnded(userId: String): PlaybackSource? {
        if (!autoPlayNext.first()) return null

        val next = queue.next() ?: refillFromNextUp(userId) ?: return null
        return runCatching { sourceProvider.resolve(next.id, userId, 0L) }
            .getOrNull()
    }

    private suspend fun refillFromNextUp(userId: String): MediaItem? {
        val fetched = runCatching { api.nextUp(userId).items.map { it.toMediaItem() } }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
        if (fetched.isEmpty()) return null
        queue.setQueue(fetched, 0)
        return queue.current.value
    }

    private fun BaseItemDto.toMediaItem() = MediaItem(
        id = id,
        // Shows/NextUp 恒返回 Episode 条目;其余类型值兜底映射,保持健壮。
        kind = when (type) {
            "Movie" -> MediaKind.MOVIE
            "Series" -> MediaKind.SERIES
            "Season" -> MediaKind.SEASON
            else -> MediaKind.EPISODE
        },
        name = name,
        seriesName = seriesName,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        runTimeMs = runTimeTicks?.let { it / TICKS_PER_MS },
        resumePositionMs = (userData?.positionTicks ?: 0L) / TICKS_PER_MS,
    )

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}
