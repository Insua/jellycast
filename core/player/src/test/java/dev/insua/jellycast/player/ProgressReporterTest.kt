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
}
