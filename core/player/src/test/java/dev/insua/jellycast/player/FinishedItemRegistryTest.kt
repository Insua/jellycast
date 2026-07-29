package dev.insua.jellycast.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「哪些条目的上报生命周期已经终结」。见设计文档 §2.3 规则 1 / §2.4。
 */
class FinishedItemRegistryTest {

    @Test fun `没标记过的条目不是已终结`() {
        assertFalse(FinishedItemRegistry().isFinished("ep1"))
    }

    @Test fun `标记之后是已终结`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        assertTrue(registry.isFinished("ep1"))
    }

    @Test fun `标记只影响被标记的那个条目`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        assertFalse(registry.isFinished("ep2"))
    }

    /** 重看语义:`start` 会清除标记,和服务端一致(实测:已 Played 的条目收到 start 会被取消 Played)。 */
    @Test fun `清除之后不再是已终结`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        registry.clearFinished("ep1")
        assertFalse(registry.isFinished("ep1"))
    }

    /**
     * 有界:标记只需覆盖「刚 stop 的条目仍可能有在飞/排队的上报」这个秒级窗口,
     * 不能无限增长。超出容量时淘汰最久未被访问的。
     */
    @Test fun `超出容量时淘汰最旧的标记`() {
        val registry = FinishedItemRegistry(capacity = 2)
        registry.markFinished("ep1")
        registry.markFinished("ep2")
        registry.markFinished("ep3")

        assertFalse(registry.isFinished("ep1"), "ep1 是最旧的,应该被淘汰")
        assertTrue(registry.isFinished("ep2"))
        assertTrue(registry.isFinished("ep3"))
    }

    /** LRU 而不是 FIFO:被查询过的标记算「新鲜」,不该先于没被碰过的被淘汰。 */
    @Test fun `被访问过的标记比未访问的更晚被淘汰`() {
        val registry = FinishedItemRegistry(capacity = 2)
        registry.markFinished("ep1")
        registry.markFinished("ep2")
        registry.isFinished("ep1")      // 让 ep1 变成最近访问
        registry.markFinished("ep3")    // 触发淘汰

        assertTrue(registry.isFinished("ep1"), "ep1 刚被访问过,不该被淘汰")
        assertFalse(registry.isFinished("ep2"), "ep2 是最久未访问的")
        assertTrue(registry.isFinished("ep3"))
    }
}
