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
        val pending = dao.pending()
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
        val pending = dao.pending()
        dao.delete(pending.map { it.id })
        assertEquals(0, dao.pending().size)
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
        val pending = dao.pending(limit = 2)
        assertEquals(listOf("a", "b"), pending.map { it.itemId })
    }
}
