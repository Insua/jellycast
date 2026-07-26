package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageStateTest {

    @Test fun `初始状态为空且未到底`() {
        val s = PageState<String>()
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.loadedCount)
        assertNull(s.totalCount)
        assertFalse(s.isLoading)
        // totalCount 未知时不能判定到底,否则会漏加载
        assertFalse(s.endReached)
    }

    @Test fun `startLoading 置位且清除上次错误`() {
        val s = PageState<String>().onError("boom").startLoading()
        assertTrue(s.isLoading)
        assertNull(s.error)
    }

    @Test fun `第一页加载后记录条目与总数`() {
        val s = PageState<String>().startLoading()
            .onPageLoaded(listOf("a", "b"), startIndex = 0, total = 5)
        assertEquals(listOf("a", "b"), s.items)
        assertEquals(5, s.totalCount)
        assertFalse(s.isLoading)
        assertFalse(s.endReached)
    }

    @Test fun `第二页追加在第一页之后且顺序不乱`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .onPageLoaded(listOf("c", "d"), 2, 4)
        assertEquals(listOf("a", "b", "c", "d"), s.items)
        assertTrue(s.endReached)
    }

    @Test fun `startIndex 与已加载数不符的响应被丢弃`() {
        // 场景:重复请求或乱序响应。startIndex=0 的第二次响应不得把首页再追加一遍
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .startLoading()
            .onPageLoaded(listOf("a", "b"), 0, 4)
        assertEquals(listOf("a", "b"), s.items)
        assertFalse(s.isLoading)
    }

    @Test fun `超前的 startIndex 也被丢弃`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a"), 0, 10)
            .startLoading()
            .onPageLoaded(listOf("x"), 5, 10)   // 中间缺了一段,不能接
        assertEquals(listOf("a"), s.items)
        assertFalse(s.isLoading)
    }

    @Test fun `空结果立即视为到底`() {
        val s = PageState<String>().onPageLoaded(emptyList(), 0, 0)
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.totalCount)
        assertTrue(s.endReached)
    }

    @Test fun `onError 保留已加载内容`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 10)
            .startLoading()
            .onError("网络错误")
        assertEquals(listOf("a", "b"), s.items)   // 绝不清空用户已看到的内容
        assertEquals("网络错误", s.error)
        assertFalse(s.isLoading)
    }

    @Test fun `错误后可继续加载下一页`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .onError("网络错误")
            .startLoading()
            .onPageLoaded(listOf("c", "d"), 2, 4)
        assertEquals(listOf("a", "b", "c", "d"), s.items)
        assertNull(s.error)
    }

    @Test fun `错误后丢弃的响应清除错误信息`() {
        // 场景:onError 后收到过期响应,discard 会清除错误状态。这是可接受的行为,但需要显式测试。
        val s = PageState<String>()
            .onPageLoaded(listOf("a"), 0, 5)
            .onError("网络错误")
            .onPageLoaded(listOf("stale"), 0, 5)  // startIndex 不符,被丢弃
        assertEquals(listOf("a"), s.items)
        assertNull(s.error)
    }

    @Test fun `reset 回到初始态`() {
        val s = PageState<String>().onPageLoaded(listOf("a"), 0, 9).onError("x").reset()
        assertEquals(PageState<String>(), s)
    }
}
