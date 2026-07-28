package dev.insua.jellycast.feature.library

import dev.insua.jellycast.model.Cached
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.repository.ItemPage
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.repository.staleWhileRevalidate
import kotlinx.coroutines.flow.Flow

/**
 * ViewModel 单测用的假仓储:缓存放在内存 map 里,不碰 Room。
 *
 * **发射顺序不是这里自己实现的** —— 它直接复用生产的 [staleWhileRevalidate] 编排,
 * 只把"读缓存 / 写缓存"换成内存 map。所以这个替身在四个分支上不可能和真仓储跑偏,
 * 不会出现"ViewModel 测试全绿、线上照样白屏"。真仓储对 Room 的读写由
 * `:core:network` 的 `MediaRepositoryTest` 覆盖。
 */
class FakeMediaRepository : MediaRepository {

    private val cache = mutableMapOf<String, List<MediaItem>>()

    /** 每一次写回缓存,用来断言"网络成功才写库"。 */
    val writes = mutableListOf<Pair<String, List<MediaItem>>>()

    /** 每一次 [patchItem] 调用改到的 itemId,用来断言"乐观更新确实写透了缓存"。 */
    val patchedItemIds = mutableListOf<String>()

    /** 预置"上次成功刷新留下的缓存"。 */
    fun seed(bucket: String, items: List<MediaItem>) {
        cache[bucket] = items
    }

    override fun bucket(bucket: String, fetch: suspend () -> List<MediaItem>): Flow<Cached<List<MediaItem>>> =
        staleWhileRevalidate(
            readCache = { cache[bucket]?.takeIf { it.isNotEmpty() } },
            fetch = fetch,
            writeThrough = { items ->
                cache[bucket] = items
                writes += bucket to items
            },
        )

    override fun pagedBucket(bucket: String, fetch: suspend () -> ItemPage): Flow<Cached<ItemPage>> =
        staleWhileRevalidate(
            // 与真仓储一致:缓存回来的页永远不带 total。
            readCache = { cache[bucket]?.takeIf { it.isNotEmpty() }?.let { ItemPage(it, total = null) } },
            fetch = fetch,
            writeThrough = { page ->
                cache[bucket] = page.items
                writes += bucket to page.items
            },
        )

    override suspend fun patchItem(itemId: String, transform: (MediaItem) -> MediaItem) {
        patchedItemIds += itemId
        for ((bucket, items) in cache) {
            cache[bucket] = items.map { if (it.id == itemId) transform(it) else it }
        }
    }
}
