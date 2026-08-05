package dev.insua.jellycast.cache

/**
 * 策略的输入:一个已缓存条目的最小描述。
 *
 * 故意不复用 `:core:database` 的 `CachedAudioEntity` —— 策略函数不应该认识存储 schema,
 * 二者之间的映射由调用方(Task 6)负责。
 *
 * @param order 该条目在 [SeriesSlot.order] 序列里的位置,决定它相对锚点是"之前"还是"之后"。
 * @param sizeBytes 文件大小,用于判断是否超出 `maxBytes`。
 * @param lastAccessAt 最后一次被访问(播放)的时间戳。用于超容量时决定先删谁 —— **不是**下载完成时间。
 */
data class CachedEntry(
    val itemId: String,
    val order: Int,
    val sizeBytes: Long,
    val lastAccessAt: Long,
)

/**
 * 策略的输入:剧集顺序里的一个位置。
 *
 * `order` 是全剧唯一且递增的序号,季号 → 集号拍平之后的结果(跨季连续,例如 S2 最后一集之后
 * 紧接着就是 S3E01 的 order)。这是序列顺序的**唯一**依据 —— 上游 DAO 不保证
 * `seasonNumber` / `episodeNumber` 为 null 时的排序,所以这个函数绝不直接看季号/集号,
 * 只看 `order`。电影只传一个只含自己的单元素列表。
 */
data class SeriesSlot(val itemId: String, val order: Int)

/** `planCache` 的输出:接下来要下载的条目(按顺序)与要立即删除的条目。 */
data class CacheDecision(val toPrefetch: List<String>, val toEvict: List<String>)

/**
 * 计算"该缓存哪几集、该删哪几个"。纯函数:不读文件、不发请求、不碰 Android、不看时钟——
 * 调用方如果需要"现在几点",必须自己算好通过参数传进来(这里目前没有用到,因为驱逐只依赖
 * `lastAccessAt` 的相对顺序,不依赖绝对的"现在")。
 *
 * ### 驱逐优先级(§4.4)
 * 1. **窗口外**:排在锚点([currentItemId])之前的一律驱逐,无条件。
 * 2. **超出集数上限**:窗口内(锚点及之后)按 `order` 从近到远排列,超出 [maxEpisodes]
 *    (含锚点本身)的部分驱逐,离锚点越远越先删。
 * 3. **超出存储上限**:上面两条筛过之后如果总大小仍超过 [maxBytes],按 [CachedEntry.lastAccessAt]
 *    从老到新继续驱逐,直到落在上限内或只剩锚点。这里刻意用"最后访问时间"而不是下载时间——
 *    一集下载得早但反复回听,不该比一集下载得晚但再也没碰过的更早被删。
 *
 * [maxBytes] 为 `null` 表示不设容量上限,但规则 1、2(窗口、集数)照常生效,只是跳过规则 3。
 *
 * ### 锚点永不驱逐
 * [currentItemId] 代表正在播放的条目,在任何规则下都不会出现在 [CacheDecision.toEvict] 里,
 * 哪怕它自己的大小就已经超过 [maxBytes]。删掉正在播放的文件会直接打断播放——这个体验代价
 * 远大于暂时超一点容量上限。
 *
 * @param currentItemId 锚点:当前正在播放的条目 id。
 * @param seriesOrder 该剧(或电影)完整的顺序列表;电影传只含自己的单元素列表。
 * @param cached 当前已缓存的条目列表。
 * @param maxEpisodes 一部剧最多保留的集数,含锚点本身。
 * @param maxBytes 存储上限;`null` 表示不限制总量。
 */
fun planCache(
    currentItemId: String,
    seriesOrder: List<SeriesSlot>,
    cached: List<CachedEntry>,
    maxEpisodes: Int,
    maxBytes: Long?,
): CacheDecision {
    val anchorOrder = seriesOrder.firstOrNull { it.itemId == currentItemId }?.order
        ?: return CacheDecision(toPrefetch = emptyList(), toEvict = emptyList())

    // 窗口内的目标集合:锚点及之后,按 order 排序,最多 maxEpisodes 个(含锚点)。
    val windowTargets = seriesOrder
        .filter { it.order >= anchorOrder }
        .sortedBy { it.order }
        .take(maxEpisodes)
    val windowTargetIds = windowTargets.map { it.itemId }.toSet()

    val cachedById = cached.associateBy { it.itemId }

    // toPrefetch:窗口目标里还没缓存的,按 order 顺序。
    val toPrefetch = windowTargets
        .filter { it.itemId !in cachedById }
        .map { it.itemId }

    // 规则 1:窗口外(不在"锚点及之后、且未超集数上限"的目标集合里)的一律驱逐。
    // 注:窗口目标本身已经把"超出集数上限的窗口内条目"排除在外了,所以这里同时覆盖了
    // 规则 1(锚点之前)和规则 2(超出集数上限、离锚点最远的部分)。
    val outsideWindow = cached.filter { it.itemId !in windowTargetIds && it.itemId != currentItemId }

    val evicted = mutableListOf<String>()
    evicted += outsideWindow.map { it.itemId }

    // 规则 3:窗口内保留的部分如果仍超容量,按 lastAccessAt 从老到新继续删,锚点永不驱逐。
    if (maxBytes != null) {
        val survivors = cached
            .filter { it.itemId in windowTargetIds }
            .sortedBy { it.lastAccessAt }

        var totalBytes = survivors.sumOf { it.sizeBytes }
        for (entry in survivors) {
            if (totalBytes <= maxBytes) break
            if (entry.itemId == currentItemId) continue // 锚点永不驱逐,即使它自己就超了上限
            evicted += entry.itemId
            totalBytes -= entry.sizeBytes
        }
    }

    return CacheDecision(toPrefetch = toPrefetch, toEvict = evicted)
}
