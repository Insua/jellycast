package dev.insua.jellycast.network.repository

import dev.insua.jellycast.model.Cached

/**
 * stale-while-revalidate 的决策核心:**纯函数,不做任何 IO**。
 *
 * 之所以把这几行抽出来单独成一个 object,是因为"没开 VPN 进 App 就闪退"这个缺陷的根源正是
 * 「缓存有无 × 网络成败」这四个分支中的某一条没有被处理;它们必须能在没有服务器、没有 Room、
 * 没有 Android 的环境下穷举验证(CLAUDE.md 铁律 6)。
 *
 * ## 契约
 *
 * | 缓存 | 网络 | 发射序列 |
 * |---|---|---|
 * | 有 | 成功 | `[Cached(缓存, stale)]` → `[Cached(新数据, fresh)]` |
 * | 有 | 失败 | `[Cached(缓存, stale)]` → `[Cached(缓存, stale, refreshFailed)]` |
 * | 无 | 成功 | `[Cached(新数据, fresh)]` |
 * | 无 | 失败 | `[]` —— 调用方据此进入"无法连接服务器 + 重试"的错误态,**本函数不抛异常** |
 *
 * [network] 为 `null` 表示"网络还没有结论"(仓储在真正发请求之前先调一次)。这时只发缓存。
 * 由此得到一条被 `CachePolicyTest` 钉死的不变量:**`resolve(cache, 结论)` 一定以
 * `resolve(cache, null)` 为前缀**,所以仓储可以两阶段调用同一个函数,先发前缀、再补发剩下的,
 * 而不需要在仓储里再写一份分支逻辑(那份副本迟早会和这里跑偏)。
 *
 * ## 为什么"成功但为空"必须区别于"失败"
 *
 * 把失败当成空结果,只需要服务端抖一下,用户整个缓存的媒体库就被一次 `replaceBucket(空)` 抹掉,
 * 屏幕上只剩空白 —— 比继续显示旧数据糟糕得多,而且不可逆。所以:
 * - 成功返回空 → 如实发射 `Cached(空, isStale = false)`,这是"服务端确实没有"的表态
 *   (例如"继续收听"里的内容已经全部听完),缓存也应该跟着被覆盖成空。
 * - 失败 → 发出去的数据**永远是缓存原样**,绝不会出现"不旧的空列表",仓储也绝不写库。
 */
object CachePolicy {

    fun <T> resolve(cache: T?, network: Result<T>?): List<Cached<T>> = buildList {
        if (cache != null) add(Cached(cache, isStale = true))

        when {
            network == null -> Unit // 还没有结论,先让用户看到缓存

            // 成功就是成功,哪怕数据是空的 —— 这是服务端的表态,不是失败。
            network.isSuccess -> add(Cached(network.getOrThrow(), isStale = false))

            // 失败:有缓存就原样留着并标记刷新失败;没缓存就一次都不发,交给调用方转错误态。
            // 注意这里不碰 network 里的异常(不 getOrThrow、不 rethrow):断网绝不允许变成崩溃。
            cache != null -> add(Cached(cache, isStale = true, refreshFailed = true))
        }
    }
}
