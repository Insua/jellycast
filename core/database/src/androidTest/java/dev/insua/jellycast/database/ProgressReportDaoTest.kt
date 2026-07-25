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
    fun `入队后pending保持先进先出`() = runBlocking {
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
    fun `pending的limit真的生效`() = runBlocking {
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
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "c", playSessionId = null,
                positionMs = 3000, kind = "progress", createdAt = 3
            )
        )
        val pending = dao.pending(limit = 2)
        assertEquals(listOf("a", "b"), pending.map { it.itemId })
    }
}
