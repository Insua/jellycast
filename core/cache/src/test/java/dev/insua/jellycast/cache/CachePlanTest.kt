package dev.insua.jellycast.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `planCache` 的决策矩阵。每条用例对应设计文档 §7.1 的一条要求,注释里标出对应条目。
 *
 * 约定:`order` 全局唯一且递增、跨季连续 —— 测试里用 S{季}E{集} 命名 itemId,
 * 但断言只依赖 order,从不依赖季号/集号本身(它们在真实数据里可能为 null)。
 */
class CachePlanTest {

    private fun slot(itemId: String, order: Int) = SeriesSlot(itemId, order)

    private fun entry(
        itemId: String,
        order: Int,
        sizeBytes: Long = 100L,
        lastAccessAt: Long = 0L,
    ) = CachedEntry(itemId, order, sizeBytes, lastAccessAt)

    // 共 3 季,每季 3 集,order 0..8,跨季连续。S2E10 类比场景改为更小的规模以便手写。
    private val threeSeasons = listOf(
        slot("S1E1", 0), slot("S1E2", 1), slot("S1E3", 2),
        slot("S2E1", 3), slot("S2E2", 4), slot("S2E3", 5),
        slot("S3E1", 6), slot("S3E2", 7), slot("S3E3", 8),
    )

    // --- 锚点之前的进 toEvict;锚点及之后的不进 ---

    @Test
    fun `items before anchor are evicted, anchor and after are not`() {
        val cached = listOf(
            entry("S1E1", 0), entry("S1E2", 1), entry("S1E3", 2),
            entry("S2E1", 3), entry("S2E2", 4),
        )
        val decision = planCache(
            currentItemId = "S2E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 10,
            maxBytes = null,
        )

        assertEquals(setOf("S1E1", "S1E2", "S1E3"), decision.toEvict.toSet(), "锚点之前的三集应全部驱逐")
        assertFalse(decision.toEvict.contains("S2E1"), "锚点自己不应被驱逐")
        assertFalse(decision.toEvict.contains("S2E2"), "锚点之后已缓存的不应被驱逐")
    }

    // --- 跨季推进:S2 最后一集之后就是 S3E01,目标集合按 order 连续取 ---

    @Test
    fun `prefetch target crosses season boundary by order`() {
        val decision = planCache(
            currentItemId = "S2E2", // order 4
            seriesOrder = threeSeasons,
            cached = emptyList(),
            maxEpisodes = 5,
            maxBytes = null,
        )

        // order 4,5,6,7,8 = S2E2, S2E3, S3E1, S3E2, S3E3
        assertEquals(
            listOf("S2E2", "S2E3", "S3E1", "S3E2", "S3E3"),
            decision.toPrefetch,
            "跨季边界应按 order 连续取满 5 集",
        )
    }

    // --- 取满 maxEpisodes 就停,toPrefetch 不超过这个数(含当前这一集) ---

    @Test
    fun `prefetch stops at maxEpisodes including anchor`() {
        val decision = planCache(
            currentItemId = "S1E1", // order 0
            seriesOrder = threeSeasons,
            cached = emptyList(),
            maxEpisodes = 3,
            maxBytes = null,
        )

        assertEquals(listOf("S1E1", "S1E2", "S1E3"), decision.toPrefetch)
        assertEquals(3, decision.toPrefetch.size, "含锚点在内最多 3 集")
    }

    // --- 超 maxBytes 时按 lastAccessAt 从老到新删,用「写入晚但访问早」的样例与 completedAt 区分 ---

    @Test
    fun `over byte cap evicts by last access time oldest first, not download time`() {
        // order 在这里模拟"下载顺序"(order 越小,越早被这个函数处理/越早下载完成),
        // 与 lastAccessAt 故意反着来,这样"按 order(下载时间)排序"和"按 lastAccessAt 排序"
        // 会给出不同的驱逐目标,才能真正测出实现依据的是哪一个。
        // 锚点 S1E1 体积很小、不参与容量冲突,避免"锚点永不驱逐"这条规则掩盖了排序依据。
        val cached = listOf(
            entry("S1E1", 0, sizeBytes = 10L, lastAccessAt = 999_999L), // 锚点,体积可忽略
            entry("S1E2", 1, sizeBytes = 400L, lastAccessAt = 9_000L),  // 下载早(order 小),但访问是最近的
            entry("S1E3", 2, sizeBytes = 400L, lastAccessAt = 1_000L),  // 下载晚(order 大),但访问是最久远的
        )
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 10,
            // 三集共 810 字节,超出 500,删掉任意一个 400 字节的就能压到上限内 ——
            // 按 lastAccessAt 应该删 S1E3(最久未访问);按 order(下载时间)则会先删 S1E2。
            maxBytes = 500L,
        )

        assertTrue(
            decision.toEvict.contains("S1E3"),
            "S1E3 的 lastAccessAt 最早(最久未访问),按访问时间应最先被删",
        )
        assertFalse(
            decision.toEvict.contains("S1E2"),
            "S1E2 最近才被访问过,不应该因为它下载得早就被误删",
        )
    }

    // --- maxBytes = null 时:窗口规则与 maxEpisodes 照常生效,只是不因总量再删 ---

    @Test
    fun `null maxBytes still applies window and episode count rules`() {
        val cached = listOf(
            entry("S1E1", 0, sizeBytes = 10_000_000_000L), // 单集就巨大
            entry("S1E2", 1, sizeBytes = 10_000_000_000L),
            entry("S1E3", 2, sizeBytes = 10_000_000_000L),
        )
        val decision = planCache(
            currentItemId = "S1E2", // 锚点在 order 1,S1E1(order 0)在窗口外
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 10,
            maxBytes = null,
        )

        assertTrue(decision.toEvict.contains("S1E1"), "窗口规则不受 maxBytes=null 影响,锚点之前仍应驱逐")
        assertFalse(decision.toEvict.contains("S1E2"), "锚点不应被驱逐")
        assertFalse(decision.toEvict.contains("S1E3"), "不限制容量时,窗口内的不应因为体积被驱逐")
    }

    @Test
    fun `null maxBytes still applies episode count cap`() {
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = emptyList(),
            maxEpisodes = 2,
            maxBytes = null,
        )

        assertEquals(listOf("S1E1", "S1E2"), decision.toPrefetch, "不限制容量时,集数上限依然生效")
    }

    // --- 当前这一集在任何情况下都不在 toEvict 里,包括它自己就超了 maxBytes 的样例 ---

    @Test
    fun `anchor is never evicted even when it alone exceeds byte cap`() {
        val cached = listOf(
            entry("S1E1", 0, sizeBytes = 5_000L, lastAccessAt = 1L),
        )
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 10,
            maxBytes = 100L, // 锚点自己已经远超上限
        )

        assertFalse(decision.toEvict.contains("S1E1"), "锚点自己超容量也不能被驱逐 —— 会中断正在播放的文件")
    }

    // --- 电影(seriesOrder 只有自己):toPrefetch 最多就是它自己,没有「之后」 ---

    @Test
    fun `movie has no after, prefetch is at most itself`() {
        val decision = planCache(
            currentItemId = "movie-1",
            seriesOrder = listOf(slot("movie-1", 0)),
            cached = emptyList(),
            maxEpisodes = 10,
            maxBytes = null,
        )

        assertEquals(listOf("movie-1"), decision.toPrefetch)
        assertTrue(decision.toEvict.isEmpty())
    }

    // --- 优先级:同时触发三条规则,断言删除顺序是「窗口外 → 超集数 → 超容量」 ---

    @Test
    fun `eviction priority is window then episode count then byte cap`() {
        // 锚点 S1E1(order 0)。S1E1 之前没有条目,窗口规则这里换个角度验证:
        // 用一个更长的剧集列表,锚点靠后,前面有窗口外的条目;
        // 窗口内又超过 maxEpisodes;窗口+集数都满足后仍超 maxBytes。
        val longSeries = (0..14).map { slot("E$it", it) }
        val cached = listOf(
            entry("E0", 0, sizeBytes = 100L, lastAccessAt = 100L),  // 窗口外(在锚点 E5 之前)
            entry("E5", 5, sizeBytes = 100L, lastAccessAt = 500L),  // 锚点
            entry("E6", 6, sizeBytes = 100L, lastAccessAt = 600L),
            entry("E7", 7, sizeBytes = 100L, lastAccessAt = 700L),
            entry("E8", 8, sizeBytes = 100L, lastAccessAt = 200L),  // 窗口内,但会被集数上限挤掉(离锚点最远)
            entry("E9", 9, sizeBytes = 100L, lastAccessAt = 1L),    // 集数上限内,但 lastAccessAt 最早,容量超时最先删
        )
        // maxEpisodes = 4:窗口内保留 order 5,6,7,8(E5,E6,E7,E8),E9 超出集数上限被挤掉
        // maxBytes: 保留的 4 集共 400 字节,上限设 300,需要再按访问时间删一个 —— E8 的 lastAccessAt(200)
        // 在剩余的 E5/E6/E7/E8 中最早(E5=500,E6=600,E7=700,E8=200),应被优先删除
        val decision = planCache(
            currentItemId = "E5",
            seriesOrder = longSeries,
            cached = cached,
            maxEpisodes = 4,
            maxBytes = 300L,
        )

        assertTrue(decision.toEvict.contains("E0"), "窗口外的应被驱逐")
        assertTrue(decision.toEvict.contains("E9"), "超出集数上限、离锚点最远的应被驱逐")
        assertTrue(decision.toEvict.contains("E8"), "窗口和集数都满足后,仍超容量则按最后访问时间最老的删")
        assertFalse(decision.toEvict.contains("E5"), "锚点不应被驱逐")
        assertFalse(decision.toEvict.contains("E6"), "E6 访问时间较新,不应被容量规则命中")
        assertFalse(decision.toEvict.contains("E7"), "E7 访问时间最新,不应被容量规则命中")

        // 顺序性:窗口外的判定先发生,集数上限次之,容量上限最后 —— 通过分别验证各自的驱逐原因成立即可,
        // 因为如果顺序颠倒(比如先按容量删),E9(访问时间比 E8 还早)会在窗口/集数阶段之前被保留判断误判。
    }

    // --- 已经缓存好的不重复出现在 toPrefetch 里 ---

    @Test
    fun `already cached items are not repeated in toPrefetch`() {
        val cached = listOf(entry("S1E1", 0), entry("S1E2", 1))
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 3,
            maxBytes = null,
        )

        assertEquals(listOf("S1E3"), decision.toPrefetch, "已缓存的 S1E1/S1E2 不应再次出现在 toPrefetch 中")
    }

    // --- 复审 Finding 1:窗口步骤对锚点的保护是防御性的,maxEpisodes<=0 时才真正生效,
    // 必须有一个测试在它被去掉时真正变红,否则这行代码就是没人守护的死代码。 ---

    @Test
    fun `anchor is protected in the window step even when maxEpisodes is zero`() {
        val cached = listOf(entry("S1E1", 0))
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 0, // 窗口目标集合因此为空,只有窗口步骤的显式排除能保住锚点
            maxBytes = null,
        )

        assertFalse(
            decision.toEvict.contains("S1E1"),
            "maxEpisodes=0 时窗口目标集合为空,但锚点仍不应出现在 toEvict 里",
        )
        assertTrue(decision.toPrefetch.isEmpty(), "maxEpisodes=0 时锚点也不会被预取——结果是维持原状,不是清空")
    }

    // --- 复审 Finding 2:不属于当前剧序列的旧缓存(itemId 在 seriesOrder 里完全找不到)
    // 必须无条件驱逐(§4.4 规则 1 明确点名的「不属于当前剧的旧剧集」)。 ---

    // --- 复审 I5(Important):toPrefetch 也必须受 maxBytes 约束,不能只在已缓存条目上生效 ---
    // 修复前 toPrefetch 完全不看 maxBytes,规则 3 只在**下一次**换集重新算计划时才会看到"现在超了"
    // ——这条一直不收敛的下载/驱逐循环见 CachePlan.kt 类 KDoc「复审 I5」的详细分析。

    @Test
    fun `toPrefetch 受 maxBytes 约束,预算只够部分新条目时提前停止`() {
        // 窗口 E0..E4(含锚点最多 5 集),只有锚点 E0 已缓存(10 字节)——估算样本 = 10。
        // maxBytes=35,扣掉锚点占用的 10,预算剩 25,刚好够塞下两个"10 字节"的新条目(E1、E2),
        // 第三个(E3)预算不够,必须被挡在 toPrefetch 之外——不是"能塞多少塞多少"的旧行为
        // (旧行为会把 E1..E4 全部塞进 toPrefetch,完全不管 maxBytes)。
        val cached = listOf(entry("E0", 0, sizeBytes = 10L))
        val decision = planCache(
            currentItemId = "E0",
            seriesOrder = (0..4).map { slot("E$it", it) },
            cached = cached,
            maxEpisodes = 5,
            maxBytes = 35L,
        )

        assertEquals(
            listOf("E1", "E2"),
            decision.toPrefetch,
            "预算只够两个新条目时,toPrefetch 必须在预算耗尽处停止,不能把窗口里剩下的全部塞进去",
        )
    }

    @Test
    fun `toPrefetch 预算以驱逐后的剩余字节数为起点,不是驱逐前的总量`() {
        // 窗口 E0..E3(锚点 E0 + 最多 4 集)。E0(锚点,10 字节)+ E1(190 字节)= 200,超过
        // maxBytes=150,规则 3 会把 E1(不是锚点、lastAccessAt 更早)驱逐掉,驱逐后只剩 10 字节。
        //
        // 如果预取预算错误地用"驱逐前"的总量(200)起算:150-200 = 负数,E2 连一个字节的预算
        // 都分不到,toPrefetch 会是空的。只有用"驱逐后真正留下来"的字节数(10)起算,
        // 150-10=140 才够放下一个按 (10+190)/2=100 估算大小的新条目(E2)——这条用例直接
        // 区分这两种实现,唯有用驱逐后的余量才能通过。
        val cached = listOf(
            entry("E0", 0, sizeBytes = 10L, lastAccessAt = 999_999L), // 锚点,访问时间最新
            entry("E1", 1, sizeBytes = 190L, lastAccessAt = 1L),      // 访问时间最早,应被规则 3 驱逐
        )
        val decision = planCache(
            currentItemId = "E0",
            seriesOrder = (0..3).map { slot("E$it", it) },
            cached = cached,
            maxEpisodes = 4,
            maxBytes = 150L,
        )

        assertTrue(decision.toEvict.contains("E1"), "E1 应该先被规则 3 驱逐,腾出空间")
        assertEquals(
            listOf("E2"),
            decision.toPrefetch,
            "预取预算必须以驱逐后真正剩下的字节数起算,不是驱逐前的总量——否则这里会算出负预算," +
                "E2 连一个都塞不进去",
        )
    }

    @Test
    fun `这部剧一集都没缓存过时_预取预算不做限制`() {
        // 没有任何该剧已缓存的样本可供估算大小——查不清楚一集大概多大时,宁可不限制也不要错误地
        // 把预算算成 0、直接不预取任何东西(那样违背"下一集不卡"这个核心价值)。maxBytes 故意设成
        // 荒谬地小(1 字节),如果预算被应用会导致 toPrefetch 立刻清空;结果应该是维持"不限制"时
        // 的行为,三集全部进 toPrefetch。
        val decision = planCache(
            currentItemId = "E0",
            seriesOrder = (0..2).map { slot("E$it", it) },
            cached = emptyList(),
            maxEpisodes = 3,
            maxBytes = 1L,
        )

        assertEquals(
            listOf("E0", "E1", "E2"),
            decision.toPrefetch,
            "没有该剧的已缓存样本可供估算大小时,不应该把预取预算错误地约束成 0",
        )
    }

    @Test
    fun `cached item absent from seriesOrder is evicted as a cross-series leftover`() {
        val cached = listOf(
            entry("S1E1", 0),
            // 这一条不出现在 threeSeasons 里的任何一个 SeriesSlot 中(换过剧集库之类的残留),
            // 它自己的 order 字段刻意设得比 anchorOrder 大,这样"order < anchorOrder"式的
            // 误判无法命中它,只有"根本不在 seriesOrder 里"这条判断才能抓住它。
            entry("leftover-from-other-series", order = 100),
        )
        val decision = planCache(
            currentItemId = "S1E1",
            seriesOrder = threeSeasons,
            cached = cached,
            maxEpisodes = 10,
            maxBytes = null,
        )

        assertTrue(
            decision.toEvict.contains("leftover-from-other-series"),
            "不属于当前剧序列的旧缓存应无条件驱逐,即使它自己的 order 字段看起来在锚点之后",
        )
    }
}
