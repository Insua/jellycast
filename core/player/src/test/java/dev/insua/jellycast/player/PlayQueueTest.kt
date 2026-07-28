package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

class PlayQueueTest {
    @Test fun `next 按顺序推进`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2"), ep("3")), 0) }
        assertEquals("1", q.current.value?.id)
        assertEquals("2", q.next()?.id)
        assertEquals("3", q.next()?.id)
        assertNull(q.next())
        assertEquals("3", q.current.value?.id)   // 到底后 current 不变
    }

    @Test fun `previous 回退且不越界`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 1) }
        assertEquals("1", q.previous()?.id)
        assertNull(q.previous())
    }

    @Test fun `hasNext 在最后一项返回 false`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        assertTrue(q.hasNext())
        q.next()
        assertFalse(q.hasNext())
    }

    @Test fun `hasPrevious 在第一项返回 false,推进后返回 true`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        assertFalse(q.hasPrevious())
        q.next()
        assertTrue(q.hasPrevious())
    }

    @Test fun `空队列不崩溃`() {
        val q = PlayQueue().apply { setQueue(emptyList(), 0) }
        assertNull(q.current.value)
        assertNull(q.next())
        assertFalse(q.hasNext())
    }
}
