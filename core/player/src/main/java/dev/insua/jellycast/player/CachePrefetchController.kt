package dev.insua.jellycast.player

import dev.insua.jellycast.cache.AudioCacheMeta
import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.cache.CachedEntry
import dev.insua.jellycast.cache.NetworkTypeMonitor
import dev.insua.jellycast.cache.SeriesSlot
import dev.insua.jellycast.cache.planCache
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.mapper.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 自动缓存的**编排层**:换集时算一次计划(Task 2 的 [planCache]),先驱逐再预取,仅 WiFi、
 * 串行一次一集。设计文档 §3/§5.3、`docs/superpowers/sdd/2026-08-03-audio-cache/task-6-brief.md`。
 *
 * ## 为什么全部决策都在这里,而不是 `PlaybackService`
 *
 * `PlaybackService` 在 JVM 单测里造不出来(仓库既有经验,见 [EngineSeekRouter] /
 * [ForegroundLifecycleController] 同样的取舍)。把"该缓存哪几集""先删哪个再下哪个""同一时刻
 * 只准一个下载在跑"这些决策放进 Service,就等于这些决策永远测不到——而这个子系统恰恰是
 * 用户明确要求"这个得做好测试"的地方。[PlaybackService] 因此只做一件事:换集时调一次
 * [onItemChanged]。
 *
 * ## 驱动时机:[PlayQueue.current],不是 [AudioPlaybackEngine.state]
 *
 * 必须挑一个"只在真的换了条目时才变"的信号。[AudioPlaybackEngine.state] 不满足——Task 6 carry-forward
 * 之二修好之后,本地缓存源的 `seekTo` 也会重新发一次 `Ready`(见 [AudioPlaybackEngineImpl.seekTo]
 * 的类注释),拖一次进度条就会被这里误判成"换集了",白白取消一次正在下载的预取、重新起播计划。
 * [PlayQueue.current] 只在 [PlayQueue.setQueue]/[PlayQueue.next]/[PlayQueue.previous] 时变化,
 * 精确对应"换集"这件事本身。
 *
 * [PlayQueue] 是 `@Singleton`,`PlaybackService` 重建后重新订阅 [PlayQueue.current] 会被
 * `StateFlow` 重放上一条命留下的当前条目——和 [lastPlannedItemId] 的去重判断天然兼容:同一个
 * itemId 不会触发第二次取消 + 重新计划,不需要再单独处理"这是不是重放"。
 *
 * ## 孤儿清扫的生产调用方(carry-forward 之一)
 *
 * [AudioCacheStore.sweepOrphans] 在 Task 3 里就写好了,但一直没有生产调用方——文档上"只在启动时
 * 调用"这句话本身挡不住任何代码,直到真的接一个调用方之前,它就是一段看着有用、实际上从没跑过的
 * 死代码(仓库已经因为 `ProgressReporter` 犯过同一个错误整个版本)。这里在**本控制器生命周期内的
 * 第一次** [onItemChanged] 落地时清一次(见 [hasSweptOrphans])——本控制器和 `PlaybackService`
 * 同生共死,由 `PlaybackService.onCreate` 现场 `new` 出来,所以"控制器生命周期内第一次"就是
 * "这次服务启动后第一次真的有内容要播"的那一刻,足够贴近"启动时"这个语义,且和 Task 3 已经加固
 * 过的 `inFlight` 并发保护(见 [AudioCacheStore.sweepOrphans] 类注释)配合:清扫和随后的预取下载
 * 完全可能撞在同一个 serverId 上,不会互相破坏。
 *
 * ## 下载源:注入的是裸解析器,不是缓存感知的那层
 *
 * [downloadSourceProvider] 生产环境接的是 `PlaybackSourceResolver.asProvider()`,**不是**
 * [CacheAwareSourceProvider]——预取要的是"这一集的可下载 URL 是什么",不是"该怎么播这一集"。
 * 两者恰好在缓存未命中时行为一致(委派给同一个 resolver),但语义上不应该经过一层"命中缓存就
 * 返回本地文件"的判断:预取的对象本来就是**还没缓存**的条目([planCache] 已经把已缓存的过滤掉
 * 了),经不经过那层装饰器结果一样,但直接注入裸解析器让这个依赖关系读起来更诚实,也避免
 * `CachePrefetchControllerTest` 里被迫拖一个装饰器进来。
 *
 * 只缓存 [AudioDeliveryLevel.SERVER_AUDIO_ONLY](L1)结果——L3 是整段视频兜底流,缓存它会让
 * "音频缓存"变成"视频缓存",既违背这个子系统本身的目的(设计文档 §2:体积从几十 MB 变成几百
 * MB~GB),也会在用户毫无预期的地方吃光刚设置的存储上限。落到 L3 的条目本次预取直接跳过、
 * 不计入任何"失败",下次换集重算计划时还会再试一次。
 *
 * ## `maxBytes`:Task 7 落地前的接线缝(carry-forward 之三)
 *
 * Task 7 才会新增"最大占用存储"这个偏好项。[maxBytesProvider] 是特意留的接缝——一个 `suspend
 * () -> Long?` 而不是构造时定死的 `Long?`,好让 Task 7 只需要在 `PlayerModule` 里把
 * `{ DEFAULT_MAX_BYTES }` 换成 `{ preferencesStore.maxCacheBytes.first() }`,不需要改动本类
 * 一行代码。默认值 1 GiB,和设计文档 §4.3"默认 1 GB"一致;读取失败(DataStore 异常)时退回
 * 同一个默认值,不是"不限制"——存储上限这种东西查不清楚时应该保守,不应该在读取失败时意外放开
 * 到无限制。`null` = 不限制,和 [planCache] 已经定好的契约一致,Task 7 必须原样沿用这个表示。
 *
 * ## 剧集顺序:只查够用的那一段,不查整部剧
 *
 * 参照 [AutoPlayNextController] 的做法问服务端要季/集列表,但取舍不同:[AutoPlayNextController]
 * 只要"下一集是谁",这里要的是"从锚点开始、够填满 [maxEpisodes] 集的那一段顺序"。[SeriesSlot.order]
 * 的绝对值不重要(见 [SeriesSlot] KDoc:只看相对顺序),所以只需要从**锚点所在季**开始往后查,
 * 凑够 [maxEpisodes] 集或没有更多季为止——不需要像 [planCache] 的输入描述那样查全剧。已缓存的
 * 更早季数的集数不需要出现在这次查到的顺序里也能被正确驱逐:[planCache] 判断"是否在窗口内"只看
 * `windowTargetIds` 成员关系,不要求 [CachedEntry.order] 对每一条已缓存记录都有意义(该字段目前
 * 确实只在窗口内的记录上被赋予真实值,窗口外的记录给一个哨兵值 [OUT_OF_WINDOW_ORDER] 也不影响
 * 驱逐结论——已核对 `planCache` 现有实现,`CachedEntry.order` 不参与任何驱逐/预取判断)。
 *
 * 单集下载失败(网络错误、resolve 抛异常、L3 兜底)不影响后续条目——`for` 循环里每一集单独
 * try/catch,一集失败只是这一集这次没缓存成,不中断循环、更不影响正在播放的那一集。
 *
 * @param serverId 装配时刻的服务器快照,和 `PlayerModule` 里 [CacheAwareSourceProvider] /
 *   `ProgressReporter` 的 `serverId` 同一类已知取舍——进程存活期间切换激活服务器不会更新这个值。
 * @param userIdProvider 调用方(`PlaybackService`)已经做好静默降级的 `suspend () -> String?`,
 *   直接调用、不再重复包一层——和 [EngineQueueNavigator] / [PlaybackEndedAdvancer] 对同一个
 *   契约的处理方式一致。
 * @param scope 驱动预取协程的作用域,生产环境是 `PlaybackService` 的 `serviceScope`——预取的
 *   生命周期必须和播放会话绑在一起,`Service` 销毁时这个 scope 被取消,飞行中的下载随之被取消
 *   (`AudioCacheStore.store` 的 `CancellationException` 分支会清掉半成品 `.part` 文件)。
 */
class CachePrefetchController(
    private val cacheDao: CachedAudioDao,
    private val cacheStore: AudioCacheStore,
    private val networkTypeMonitor: NetworkTypeMonitor,
    private val downloadSourceProvider: PlaybackSourceProvider,
    private val api: JellyfinApi,
    private val serverId: String,
    private val userIdProvider: suspend () -> String?,
    private val scope: CoroutineScope,
    private val maxEpisodes: Int = DEFAULT_MAX_EPISODES,
    private val maxBytesProvider: suspend () -> Long? = { DEFAULT_MAX_BYTES },
) {
    /** 见类注释「驱动时机」:同一个 itemId 不重复触发,天然吸收 `PlayQueue.current` 的重放。 */
    @Volatile
    private var lastPlannedItemId: String? = null

    /** 见类注释「孤儿清扫」:只在本控制器生命周期内清一次。 */
    @Volatile
    private var hasSweptOrphans = false

    /** 上一次尚未完成的预取计划;换集时取消它,腾出下载名额给新计划(见类注释)。 */
    @Volatile
    private var activeJob: Job? = null

    /**
     * `PlaybackService` 换集时调一次。非 suspend——调用方(`PlayQueue.current` 的收集协程)
     * 不应该被"算计划 + 驱逐 + 下载"这一整条链路阻塞,这里只负责取消旧计划、起一个新协程。
     */
    fun onItemChanged(item: MediaItem) {
        if (item.id == lastPlannedItemId) return
        lastPlannedItemId = item.id
        activeJob?.cancel()
        activeJob = scope.launch { runCycle(item) }
    }

    private suspend fun runCycle(item: MediaItem) {
        if (!hasSweptOrphans) {
            hasSweptOrphans = true
            sweepOrphansQuietly()
        }

        val userId = userIdProvider() ?: return
        val (seriesOrder, metaByItemId) = seriesOrderAndMeta(item, userId) ?: return
        val cached = listCachedEntries(seriesOrder)
        val maxBytes = safeMaxBytes()

        val decision = planCache(item.id, seriesOrder, cached, maxEpisodes, maxBytes)

        // 驱逐必须在预取之前:先腾地方,再下载(设计文档 §5.3 / brief 全局约束)。
        for (evictItemId in decision.toEvict) evictQuietly(evictItemId)

        // 仅 WiFi 才发起下载;驱逐已经在上面无条件做完了,不受网络类型影响——它只是本地文件清理。
        if (!networkTypeMonitor.isOnWifi()) return

        // 串行:一个朴素的 for 循环里逐条 await,结构上就不可能有第二个下载在第一个完成前开始。
        for (prefetchItemId in decision.toPrefetch) {
            val meta = metaByItemId[prefetchItemId] ?: AudioCacheMeta(seriesId = null, seasonNumber = null, episodeNumber = null)
            downloadQuietly(prefetchItemId, userId, meta)
        }
    }

    private suspend fun sweepOrphansQuietly() {
        try {
            cacheStore.sweepOrphans(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:AudioCacheStore.sweepOrphans 内部已经是尽力而为(查询失败整个跳过),
            // 这里只是双重保险,不影响随后的驱逐/预取。
        }
    }

    private suspend fun safeMaxBytes(): Long? = try {
        maxBytesProvider()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 读取失败退回保守默认值,不是"不限制"——见类注释「maxBytes」。
        DEFAULT_MAX_BYTES
    }

    private suspend fun evictQuietly(itemId: String) {
        try {
            cacheStore.delete(serverId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:单条驱逐失败不影响其余条目,也不影响随后的预取(AudioCacheStore.delete 本身
            // 已经不抛非取消异常,这里是防御性的双重保险)。
        }
    }

    private suspend fun downloadQuietly(itemId: String, userId: String, meta: AudioCacheMeta) {
        try {
            // 防御性复查:cached 快照在算计划和真正下载之间可能已经过期(比如同一集在别处被标记
            // 完成),已经缓存完成的不重复下载。
            if (cacheStore.pathIfComplete(serverId, itemId) != null) return

            val source = downloadSourceProvider.resolve(itemId, userId, 0L)
            // 只缓存纯音频结果——见类注释「下载源」。
            if (source.level != AudioDeliveryLevel.SERVER_AUDIO_ONLY) return

            cacheStore.store(serverId, itemId, meta, source.streamUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:单集下载失败不影响后续条目(brief 全局约束 + 设计文档验收标准)。
        }
    }

    /**
     * 把 [item] 所在剧集的顺序(从锚点所在季开始,够填满 [maxEpisodes] 集为止)转成
     * [planCache] 要的 [SeriesSlot] 列表,顺带收集每一集的 [AudioCacheMeta](供 [downloadQuietly]
     * 写索引用,避免为每一集单独再查一次详情)。
     *
     * 电影(及其它非剧集条目):只有自己一个"槽位",`order = 0`。
     *
     * 任何一步失败(缺归属 id、网络异常、锚点在返回的季列表里找不到)都返回 `null`——
     * 调用方据此整轮跳过,不驱逐也不预取。这是刻意保守的默认值,和 [planCache] 自己在锚点缺失时
     * 返回空 [dev.insua.jellycast.cache.CacheDecision] 是同一种"查不清楚就什么都不做"的精神。
     */
    private suspend fun seriesOrderAndMeta(
        item: MediaItem,
        userId: String,
    ): Pair<List<SeriesSlot>, Map<String, AudioCacheMeta>>? {
        if (item.kind != MediaKind.EPISODE) {
            val slots = listOf(SeriesSlot(item.id, 0))
            val meta = mapOf(item.id to AudioCacheMeta(seriesId = null, seasonNumber = null, episodeNumber = null))
            return slots to meta
        }

        return try {
            var cachedDetail: BaseItemDto? = null
            suspend fun detail(): BaseItemDto {
                val known = cachedDetail
                if (known != null) return known
                return api.itemDetail(item.id, userId).also { cachedDetail = it }
            }

            val seriesId = item.seriesId ?: detail().seriesId ?: return null
            val seasons = api.seasons(seriesId, userId).items.mapNotNull { it.toMediaItem() }
            val seasonId = item.seasonId
                ?: detail().seasonId
                ?: seasons.firstOrNull { it.seasonNumber != null && it.seasonNumber == item.seasonNumber }?.id
                ?: return null

            val startIndex = seasons.indexOfFirst { it.id == seasonId }
            if (startIndex < 0) return null

            val slots = mutableListOf<SeriesSlot>()
            val meta = mutableMapOf<String, AudioCacheMeta>()
            var order = 0
            var anchorFound = false
            var collectedFromAnchor = 0

            for (seasonIndex in startIndex until seasons.size) {
                if (anchorFound && collectedFromAnchor >= maxEpisodes) break

                val season = seasons[seasonIndex]
                val episodes = api.episodes(seriesId, season.id, userId).items.mapNotNull { it.toMediaItem() }
                for (episode in episodes) {
                    slots += SeriesSlot(episode.id, order)
                    meta[episode.id] = AudioCacheMeta(
                        seriesId = seriesId,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                    )
                    if (episode.id == item.id) anchorFound = true
                    if (anchorFound) collectedFromAnchor++
                    order++
                }
            }

            if (!anchorFound) null else slots to meta
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把索引里这台服务器已缓存的全部记录转成 [planCache] 要的 [CachedEntry] 列表。
     *
     * [CachedEntry.order] 只在这条记录也出现在这次查到的 [seriesOrder] 里(即窗口内,或至少
     * "查询范围内")时才有真实值,否则给哨兵值 [OUT_OF_WINDOW_ORDER]——已核对 `planCache`
     * 现有实现不读这个字段做任何判断,给哨兵值不影响驱逐/预取结论,只是不让这个字段看起来像是
     * 一个自己编出来的、有意义的序号。
     */
    private suspend fun listCachedEntries(seriesOrder: List<SeriesSlot>): List<CachedEntry> {
        val orderByItemId = seriesOrder.associate { it.itemId to it.order }
        return try {
            cacheDao.findByServer(serverId).map { entity ->
                CachedEntry(
                    itemId = entity.itemId,
                    order = orderByItemId[entity.itemId] ?: OUT_OF_WINDOW_ORDER,
                    sizeBytes = entity.sizeBytes,
                    lastAccessAt = entity.lastAccessAt,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        /** 设计文档 §4.2:一部剧最多缓存 10 集,含锚点本身。 */
        const val DEFAULT_MAX_EPISODES = 10

        /** 设计文档 §4.3:默认 1 GB(取 GiB)。Task 7 落地前的默认值,见类注释「maxBytes」。 */
        const val DEFAULT_MAX_BYTES = 1024L * 1024L * 1024L

        /** 见 [listCachedEntries]:窗口/查询范围之外的已缓存记录,order 无意义时的哨兵值。 */
        const val OUT_OF_WINDOW_ORDER = -1
    }
}
