package dev.insua.jellycast.network.repository

import dev.insua.jellycast.database.CachedItemDao
import dev.insua.jellycast.database.CachedItemEntity
import dev.insua.jellycast.model.Cached
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.session.JellyfinSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 一页列表:条目 + 服务端口径的总数。
 *
 * [total] 可空,且**缓存里一定是 null**:总数是服务端此刻的口径,不是可以离线复现的事实。
 * 如果拿"缓存里有几条"冒充总数,`PageState.endReached` 会立刻判成"已到底",用户翻不动了。
 */
data class ItemPage(val items: List<MediaItem>, val total: Int?)

/**
 * 列表类数据的唯一取数入口:**先发缓存,再后台刷新**(stale-while-revalidate)。
 *
 * ```
 * ViewModel → MediaRepository → ① 立刻发射缓存(可能是旧的)
 *                             → ② 后台请求网络
 *                             → ③ 成功:写库并发射新数据
 *                                失败:保留缓存并标记 refreshFailed
 * ```
 *
 * ## 为什么取数动作(`fetch`)由调用方传进来
 *
 * 仓储只负责"缓存 × 网络"的编排,不认识具体是哪个 Jellyfin 接口。哪个分区调
 * `UserItems/Resume`、哪个调 `Shows/NextUp`、参数大小写怎么写,是 ViewModel 和
 * `JellyfinApi` 之间的事(那些签名逐条核对自 `docs/jellyfin-openapi.json`)。
 * 这样接口签名变化不会波及缓存层,缓存策略变化也不会波及任何一个接口调用。
 *
 * ## 契约(由 `CachePolicy` 保证,`CachePolicyTest` 穷举)
 *
 * - 有缓存 → **第一次发射一定是缓存**,不等网络。
 * - 网络成功 → 追加一次 `isStale = false` 的发射,并整体替换该 bucket 的缓存。
 * - 网络失败 → 追加一次 `refreshFailed = true` 的发射,数据仍是缓存原样,**不写库**。
 * - 无缓存 + 网络失败 → **一次都不发射**,流正常结束。调用方据此进入
 *   "无法连接服务器 + 重试"的错误态。**本接口任何情况下都不会把异常抛给调用方。**
 */
interface MediaRepository {

    /** 整份列表(首页分区、季列表、集列表……)。 */
    fun bucket(bucket: String, fetch: suspend () -> List<MediaItem>): Flow<Cached<List<MediaItem>>>

    /** 分页列表的**第一页**。第二页起不走缓存(见 [ItemPage.total] 的说明)。 */
    fun pagedBucket(bucket: String, fetch: suspend () -> ItemPage): Flow<Cached<ItemPage>>
}

/**
 * [MediaRepository] 的生产实现:缓存落在 Room 的 `cached_item` 表,按
 * `serverId + bucket` 分区([JellyfinSession.serverId] 提供前者)。
 *
 * ## 几个刻意的取舍
 *
 * - **只读一次缓存,不持续观察表。** 需要的是"打开就有内容",不是"别处改了库这里跟着动"。
 *   持续观察会让一次写回触发一次额外发射,UI 白白重组一遍。
 * - **没有 TTL。** 缓存是"上次看到的样子",过期也比空白强(设计文档 §3.1)。
 *   只在成功刷新时整体覆盖。
 * - **"有缓存" ⟺ "至少有一条能解析出来的行"。** 因此一个被成功刷新写成空的 bucket,
 *   下次离线打开会被当作"没有缓存"而进入错误态,而不是显示空列表。这是已知取舍:
 *   表结构里没有"这个 bucket 刷新过且确实是空的"这一位信息,而在两种解释之间,
 *   "让用户看到可重试的错误"比"让用户看到一个无法解释的空列表"更诚实。
 * - **解析不出当前激活服务器时(未登录 / 服务器被删),既不读也不写缓存**,但流程照常走完,
 *   网络那一步会自然失败并收敛到错误态 —— 绝不抛异常。
 * - **一次 bucket()/pagedBucket() 调用只解析一次 serverId**,读缓存和写回缓存共用同一个值。
 *   两者之间隔着一次网络请求(可能耗时数秒),如果各自独立调 [JellyfinSession.serverId],
 *   用户中途切换了激活服务器时,读到的是服务器 A 的缓存、写回的却是服务器 B 的分区——
 *   一台服务器的列表被悄悄写进另一台服务器名下。
 */
class CachingMediaRepository(
    private val dao: CachedItemDao,
    private val session: JellyfinSession,
) : MediaRepository {

    override fun bucket(bucket: String, fetch: suspend () -> List<MediaItem>): Flow<Cached<List<MediaItem>>> {
        // 读缓存时解析出来的 serverId,写回缓存复用同一个值(见类 KDoc)。
        // 读缓存失败(没有激活服务器等)时保持 null,写回就跳过——不知道该写到哪个分区,宁可不写。
        var resolvedServerId: String? = null
        return staleWhileRevalidate(
            readCache = {
                val serverId = session.serverId()
                resolvedServerId = serverId
                readCache(serverId, bucket)
            },
            fetch = fetch,
            writeThrough = { items -> resolvedServerId?.let { writeThrough(it, bucket, items) } },
        )
    }

    override fun pagedBucket(bucket: String, fetch: suspend () -> ItemPage): Flow<Cached<ItemPage>> {
        var resolvedServerId: String? = null
        return staleWhileRevalidate(
            readCache = {
                val serverId = session.serverId()
                resolvedServerId = serverId
                // 缓存回来的页永远不带 total —— 见 ItemPage 的 KDoc。
                readCache(serverId, bucket)?.let { ItemPage(it, total = null) }
            },
            fetch = fetch,
            writeThrough = { page -> resolvedServerId?.let { writeThrough(it, bucket, page.items) } },
        )
    }

    /** 返回 null 表示没有可用缓存。任何异常都由 [staleWhileRevalidate] 兜成 null。 */
    private suspend fun readCache(serverId: String, bucket: String): List<MediaItem>? {
        return dao.observeBucket(serverId, bucket)
            .first()
            .mapNotNull { CachedItemPayload.decode(it.payloadJson) }
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun writeThrough(serverId: String, bucket: String, items: List<MediaItem>) {
        val now = System.currentTimeMillis()
        // 主键是 (serverId, bucket, itemId):同一 bucket 里重复的 id 会在插入时互相覆盖,
        // 导致写进去 N 条、读回来 N-1 条,position 也会出现空洞。先去重,让写入和读出严格对称。
        val rows = items.distinctBy { it.id }.mapIndexed { index, item ->
            CachedItemEntity(
                serverId = serverId,
                bucket = bucket,
                itemId = item.id,
                position = index,
                payloadJson = CachedItemPayload.encode(item),
                updatedAt = now,
            )
        }
        dao.replaceBucket(serverId, bucket, rows)
    }
}
