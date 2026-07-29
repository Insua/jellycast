package dev.insua.jellycast.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressReportDaoTest {

    private lateinit var db: JellyCastDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), JellyCastDatabase::class.java
        ).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `入队后pending按createdAt排序而非插入顺序`() = runBlocking {
        val dao = db.progressReportDao()
        // 故意让插入顺序(及自增 rowid 顺序)与 createdAt 顺序相反:
        // 先插入 createdAt 更大的 "b",再插入 createdAt 更小的 "a"。
        // 若查询退化为按插入顺序/rowid 排序,会返回 [b, a];只有真正按 createdAt
        // 升序排序,才会返回 [a, b]。
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "b", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 20
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "a", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )
        val pending = dao.pending(serverId = "s")
        assertEquals(listOf("a", "b"), pending.map { it.itemId })
    }

    @Test
    fun `delete后队列清空`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "a", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 1
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "b", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 2
            )
        )
        val pending = dao.pending(serverId = "s")
        dao.delete(pending.map { it.id })
        assertEquals(0, dao.pending(serverId = "s").size)
    }

    /**
     * 复审发现(Task 11/12 review Finding 2):[ProgressReportEntity] 带 serverId,但
     * `pending()`/`delete()` 都不按 serverId 过滤。多服务器是 v1 明确支持的场景——一旦配置了
     * 第二台服务器,`ProgressReporter.flushPending()` 就会读到*所有*服务器的补报记录,拿着自己
     * 手里那台服务器的 api 去重放,把 A 服务器的播放位置发到 B 服务器。这里用真实 Room 验证:
     * `pending(serverId)` 只返回该服务器自己的行,删除该服务器返回的 id 之后,另一台服务器的行
     * 原封不动。
     */
    @Test
    fun `pending按serverId过滤_delete只删除传入的id不影响其他server的记录`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "a1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "a2", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 20
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sB", itemId = "b1", playSessionId = null,
                positionMs = 3000, kind = "progress", createdAt = 30
            )
        )

        val pendingA = dao.pending(serverId = "sA")
        assertEquals(listOf("a1", "a2"), pendingA.map { it.itemId })

        dao.delete(pendingA.map { it.id })

        assertEquals(0, dao.pending(serverId = "sA").size)
        val pendingB = dao.pending(serverId = "sB")
        assertEquals(listOf("b1"), pendingB.map { it.itemId })
    }

    @Test
    fun `pending的limit按createdAt取最旧的N条`() = runBlocking {
        val dao = db.progressReportDao()
        // 插入顺序(及 rowid 顺序)是 c, b, a;createdAt 顺序则相反,是 a, b, c。
        // limit=2 若退化为按插入顺序/rowid 取前两条,会返回 [c, b];
        // 只有真正按 createdAt 升序排序后再取前两条,才会返回最旧的 [a, b]。
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "c", playSessionId = null,
                positionMs = 3000, kind = "progress", createdAt = 30
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "b", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 20
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "a", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )
        val pending = dao.pending(serverId = "s", limit = 2)
        assertEquals(listOf("a", "b"), pending.map { it.itemId })
    }

    /**
     * 设计文档 §2.3 规则 2:发出 `stop` 之前要把该条目在队列里的所有旧记录清掉,
     * 否则一条早期失败的心跳会在这一集被标记播完之后被重放回去,把进度写回服务端。
     *
     * 这里同时钉死两条边界:**同一服务器的其它条目**不受影响,**其它服务器的同名条目**也不受影响。
     */
    @Test
    fun `deleteForItem只删除该server该条目的记录`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep1", playSessionId = null,
                positionMs = 2000, kind = "start", createdAt = 20
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep2", playSessionId = null,
                positionMs = 3000, kind = "progress", createdAt = 30
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sB", itemId = "ep1", playSessionId = null,
                positionMs = 4000, kind = "progress", createdAt = 40
            )
        )

        dao.deleteForItem(serverId = "sA", itemId = "ep1")

        assertEquals(listOf("ep2"), dao.pending(serverId = "sA").map { it.itemId })
        assertEquals(listOf("ep1"), dao.pending(serverId = "sB").map { it.itemId })
    }

    @Test
    fun `deleteForItem对不存在的条目是无操作`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "ep1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )

        dao.deleteForItem(serverId = "s", itemId = "nobody")

        assertEquals(1, dao.pending(serverId = "s").size)
    }
}
