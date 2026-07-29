package dev.insua.jellycast.network.repository

import dev.insua.jellycast.model.Cached
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 断网崩溃的根源就在"缓存有无 × 网络成败"这四个分支上,所以它们被抽成一个不做任何 IO 的纯函数,
 * 在这里穷举。**断言的是发射序列而不是最终值** —— 只看最后一次发射的测试,在一个"根本不先发
 * 缓存、干等网络"的实现下同样会通过,而那正是本次要修的缺陷。
 */
class CachePolicyTest {

    private val cache = listOf("cached-1", "cached-2")
    private val fresh = listOf("fresh-1")
    private val boom = RuntimeException("offline")

    // ---- 四种组合 ----

    @Test
    fun `有缓存 + 网络成功：先发缓存(旧),再发新数据(不旧)`() {
        val emissions = CachePolicy.resolve(cache, Result.success(fresh))

        assertEquals(
            listOf(
                Cached(cache, isStale = true),
                Cached(fresh, isStale = false),
            ),
            emissions,
            "必须先把缓存发出去让用户立刻看到内容,新数据是第二次发射,而不是唯一一次",
        )
    }

    @Test
    fun `有缓存 + 网络失败：保留缓存并标记刷新失败,不抛异常`() {
        val emissions = CachePolicy.resolve(cache, Result.failure<List<String>>(boom))

        assertEquals(
            listOf(
                Cached(cache, isStale = true),
                Cached(cache, isStale = true, refreshFailed = true),
            ),
            emissions,
            "刷新失败不能清空用户已经看到的内容,只能追加一次带 refreshFailed 的同数据发射",
        )
    }

    @Test
    fun `无缓存 + 网络成功：只发一次新数据`() {
        val emissions = CachePolicy.resolve(null, Result.success(fresh))

        assertEquals(listOf(Cached(fresh, isStale = false)), emissions)
    }

    @Test
    fun `无缓存 + 网络失败：一次都不发,由调用方转成错误态`() {
        val emissions = CachePolicy.resolve(null, Result.failure<List<String>>(boom))

        assertTrue(emissions.isEmpty(), "没有任何可显示的数据时不得凭空造一个空列表冒充成功结果")
    }

    // ---- 网络尚未有结论(第一阶段:只发缓存,不等网络) ----
    // 仓储实现分两步调用本函数:先用 network = null 拿到"能立刻发什么",发完再去请求网络。
    // 这条约定保证了 resolve(cache, 结论) 的序列一定以 resolve(cache, null) 为前缀,
    // 仓储只需把多出来的部分补发,两阶段不会自相矛盾。

    @Test
    fun `网络尚无结论时：有缓存就先发缓存`() {
        assertEquals(listOf(Cached(cache, isStale = true)), CachePolicy.resolve(cache, network = null))
    }

    @Test
    fun `网络尚无结论时：没有缓存就什么都不发`() {
        assertTrue(CachePolicy.resolve<List<String>>(null, network = null).isEmpty())
    }

    @Test
    fun `有结论的序列以无结论的序列为前缀`() {
        listOf(Result.success(fresh), Result.failure(boom)).forEach { network ->
            listOf(cache, null).forEach { c ->
                val prefix = CachePolicy.resolve(c, network = null)
                val full = CachePolicy.resolve(c, network)
                assertEquals(prefix, full.take(prefix.size), "两阶段发射必须首尾相接,不能重发或漏发")
            }
        }
    }

    // ---- "服务器确实没有" ≠ "请求失败" ----
    // 这一条是本文件的核心:把失败当成空结果,会让一次服务端抖动抹掉用户整个缓存的库,
    // 屏幕上只剩空白 —— 比显示旧数据糟糕得多。

    @Test
    fun `网络成功返回空列表：如实发射空,且明确标记为不旧`() {
        val emissions = CachePolicy.resolve(cache, Result.success(emptyList<String>()))

        assertEquals(
            listOf(
                Cached(cache, isStale = true),
                Cached(emptyList<String>(), isStale = false),
            ),
            emissions,
            "服务端确实返回空是一个合法结论(比如'继续收听'已全部听完),要如实呈现",
        )
    }

    @Test
    fun `空结果的成功与失败必须产生不同的发射序列`() {
        val emptySuccess = CachePolicy.resolve(cache, Result.success(emptyList<String>()))
        val failure = CachePolicy.resolve(cache, Result.failure<List<String>>(boom))

        assertTrue(emptySuccess != failure, "无法区分'服务器确实没有'和'请求失败'就是这次要修的缺陷本身")

        // 失败路径的具体保证:发出去的永远是缓存原样,绝不会出现一次"不旧的空列表"。
        assertTrue(
            failure.none { it.data.isEmpty() && !it.isStale },
            "请求失败时绝不能发射一个看起来像'服务器说没有'的空结果",
        )
        assertTrue(failure.all { it.data == cache }, "失败时发出的数据必须始终是缓存本身")
        assertTrue(failure.last().refreshFailed, "失败必须以 refreshFailed 收尾,UI 才能提示离线")
        assertTrue(emptySuccess.none { it.refreshFailed }, "成功就是成功,不得被标记为刷新失败")
    }

    @Test
    fun `无缓存 + 网络成功返回空列表,发一次空结果而不是什么都不发`() {
        val emissions = CachePolicy.resolve(null, Result.success(emptyList<String>()))

        assertEquals(
            listOf(Cached(emptyList<String>(), isStale = false)),
            emissions,
            "'库是空的'和'连不上服务器'在 UI 上是两个完全不同的界面,不能都退化成没有发射",
        )
    }

    // ---- 纯函数:不做 IO、不抛异常 ----

    @Test
    fun `失败的 Result 不会被解包,异常不向上抛`() {
        // Result.failure 里的异常如果被 getOrThrow 解包就会在这里炸掉。
        val emissions = CachePolicy.resolve(cache, Result.failure<List<String>>(Error("致命错误也不许抛出去")))

        assertEquals(2, emissions.size)
    }

    @Test
    fun `同样的输入永远得到同样的输出`() {
        assertEquals(
            CachePolicy.resolve(cache, Result.success(fresh)),
            CachePolicy.resolve(cache, Result.success(fresh)),
        )
    }
}
