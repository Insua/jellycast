package dev.insua.jellycast.player

import dev.insua.jellycast.database.ProgressReportDao
import dev.insua.jellycast.database.ProgressReportEntity
import dev.insua.jellycast.network.JellyfinApi
import io.mockk.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressReporterTest {
    @Test fun `上报失败时入队等待补报`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportProgress(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 1) { dao.enqueue(match { it.itemId == "ep1" && it.positionMs == 5000L }) }
    }

    @Test fun `上报成功时不入队`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 0) { dao.enqueue(any()) }
    }

    @Test fun `flushPending 成功后删除已补报记录`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { dao.pending(any(), any()) } returns listOf(
            ProgressReportEntity(1, "s1", "ep1", null, 1000, "progress", 1)
        )
        ProgressReporter(api, dao, "s1").flushPending()
        coVerify { dao.delete(listOf(1L)) }
    }

    // ---- 铁律:start / stop 也不得向上抛错,失败必须入队(不只是 progress) ----

    @Test fun `start 上报失败时入队且不抛出`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportStart(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").start("ep1", "sess", 1000)   // 不应抛出
        coVerify(exactly = 1) {
            dao.enqueue(match { it.itemId == "ep1" && it.kind == "start" && it.positionMs == 1000L })
        }
    }

    @Test fun `stop 上报失败时入队且不抛出`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportStop(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").stop("ep1", "sess", 9000)   // 不应抛出
        coVerify(exactly = 1) {
            dao.enqueue(match { it.itemId == "ep1" && it.kind == "stop" && it.positionMs == 9000L })
        }
    }

    @Test fun `progress 上报体的 PositionTicks 是毫秒乘以 10000`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5_000)
        coVerify { api.reportProgress(match { it.positionTicks == 50_000_000L }) }
    }

    // ---- Task 7 实现者提出的并发隐患:flushPending 必须串行化,不能重复上报 ----

    @Test fun `并发调用 flushPending 不会重复上报`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>()
        var remaining = listOf(ProgressReportEntity(1, "s1", "ep1", null, 1000, "progress", 1))

        // 用一个真实的 delay 制造挂起点:如果 flushPending 没有被串行化,两次并发调用都会在
        // delay 期间读到同一份"未被删除"的 remaining,导致重复上报。
        coEvery { dao.pending(any(), any()) } coAnswers { delay(10); remaining }
        coEvery { dao.delete(any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            remaining = remaining.filterNot { it.id in ids }
        }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            launch { reporter.flushPending() }
            launch { reporter.flushPending() }
        }

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "ep1" }) }
    }

    // ---- 复审 Finding 2:多服务器场景下,补报队列必须按 serverId 隔离 ----

    /**
     * 内存版 [ProgressReportDao]:故意模仿修复前的 bug——`pending()` 不按 serverId 过滤,
     * 直接返回所有服务器的行。先用这个版本证明新测试真的会失败(TDD 的"确认失败"步骤),
     * 再改成真实语义(按 serverId 过滤)使测试转绿。
     */
    private class FakeProgressReportDao : ProgressReportDao {
        val store = mutableListOf<ProgressReportEntity>()
        private var nextId = 1L

        override suspend fun enqueue(e: ProgressReportEntity) {
            store += e.copy(id = nextId++)
        }

        override suspend fun pending(serverId: String, limit: Int): List<ProgressReportEntity> =
            store.filter { it.serverId == serverId }.sortedBy { it.createdAt }.take(limit)

        override suspend fun delete(ids: List<Long>) {
            store.removeAll { it.id in ids }
        }
    }

    @Test fun `flushPending 只重放和删除属于自己 serverId 的记录,不影响其他 server`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = FakeProgressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "sA", itemId = "a1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 1
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "sB", itemId = "b1", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 2
            )
        )

        ProgressReporter(api, dao, "sA").flushPending()

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "a1" }) }
        coVerify(exactly = 0) { api.reportProgress(match { it.itemId == "b1" }) }
        assertEquals(listOf("b1"), dao.store.map { it.itemId })
    }

    // ---- 复审 Minor:补报必须有失败上限,不能让一行毒记录永远占着队列 ----

    /**
     * 缺陷现场:`flushPending` 只删除**成功**的行。一条永远失败的记录(最典型的是条目已在服务端
     * 被删除 → 404)会永久留在队列里,而 `flushPending` 是"每次源就绪"都调的 —— 也就是**每次 seek
     * 都重试一遍**,而且顺带拖慢每一次 seek。
     *
     * 修正:补报是"尽力而为"。连续失败达到上限就丢弃这一行。为什么这个上限是安全的:
     * `flushPending` 只在一次 resolve **成功**之后被调用,也就是"服务器此刻可达"已经被证明过了 ——
     * 在服务器可达的前提下连着失败 3 次,基本只能是这条记录本身有问题,不是网络问题。
     */
    @Test fun `连续失败达到上限后丢弃该补报记录,不再无限重试`() = runTest {
        val api = mockk<JellyfinApi>()
        coEvery { api.reportProgress(any()) } throws java.io.IOException("item deleted")
        val dao = FakeProgressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "s1", itemId = "gone", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 1
            )
        )
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.flushPending()
        assertEquals(1, dao.store.size)     // 第 1 次失败:留着,可能只是一次网络抖动
        reporter.flushPending()
        assertEquals(1, dao.store.size)     // 第 2 次失败:还留着
        reporter.flushPending()
        assertEquals(0, dao.store.size)     // 第 3 次失败:放弃,别再拖累后面每一次 seek

        coVerify(exactly = 3) { api.reportProgress(any()) }
        reporter.flushPending()
        coVerify(exactly = 3) { api.reportProgress(any()) }   // 已经不在队列里了,不会再重试
    }

    /** 铁律:进度上报失败绝不打断播放。`dao` 本身抛异常(数据库损坏/迁移失败)也不得向上抛。 */
    @Test fun `flushPending 在 dao 抛异常时不向上抛错`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>()
        coEvery { dao.pending(any(), any()) } throws IllegalStateException("database corrupted")

        ProgressReporter(api, dao, "s1").flushPending()   // 不应抛出
    }
}
