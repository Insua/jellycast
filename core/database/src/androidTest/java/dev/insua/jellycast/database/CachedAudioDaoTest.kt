package dev.insua.jellycast.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedAudioDaoTest {

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
    fun `插入后按serverId和itemId查回_字段逐一对得上`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "s1", itemId = "ep1", seriesId = "series1",
                seasonNumber = 2, episodeNumber = 5, filePath = "/cache/s1/ep1.m4a",
                sizeBytes = 12345L, completedAt = 1000L, lastAccessAt = 2000L,
            )
        )

        val found = dao.findByItemId(serverId = "s1", itemId = "ep1")
        assertEquals("s1", found?.serverId)
        assertEquals("ep1", found?.itemId)
        assertEquals("series1", found?.seriesId)
        assertEquals(2, found?.seasonNumber)
        assertEquals(5, found?.episodeNumber)
        assertEquals("/cache/s1/ep1.m4a", found?.filePath)
        assertEquals(12345L, found?.sizeBytes)
        assertEquals(1000L, found?.completedAt)
        assertEquals(2000L, found?.lastAccessAt)
    }

    @Test
    fun `不存在的条目查回null`() = runBlocking {
        val dao = db.cachedAudioDao()
        assertNull(dao.findByItemId(serverId = "s1", itemId = "nobody"))
    }

    @Test
    fun `同主键再插入是覆盖而不是重复`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "s1", itemId = "ep1", seriesId = "series1",
                seasonNumber = 1, episodeNumber = 1, filePath = "/cache/s1/ep1-v1.m4a",
                sizeBytes = 100L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "s1", itemId = "ep1", seriesId = "series1",
                seasonNumber = 1, episodeNumber = 1, filePath = "/cache/s1/ep1-v2.m4a",
                sizeBytes = 200L, completedAt = 2000L, lastAccessAt = 2000L,
            )
        )

        val all = dao.findByServer(serverId = "s1")
        assertEquals(1, all.size)
        assertEquals("/cache/s1/ep1-v2.m4a", all.single().filePath)
        assertEquals(200L, all.single().sizeBytes)
    }

    @Test
    fun `按serverId隔离_查A服务器查不到B服务器的行`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "sA", itemId = "shared-id", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sA/shared-id.m4a",
                sizeBytes = 111L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "sB", itemId = "shared-id", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sB/shared-id.m4a",
                sizeBytes = 222L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )

        val resultA = dao.findByServer(serverId = "sA")
        assertEquals(1, resultA.size)
        assertEquals("/cache/sA/shared-id.m4a", resultA.single().filePath)

        val foundInA = dao.findByItemId(serverId = "sA", itemId = "shared-id")
        assertEquals("/cache/sA/shared-id.m4a", foundInA?.filePath)

        val foundInB = dao.findByItemId(serverId = "sB", itemId = "shared-id")
        assertEquals("/cache/sB/shared-id.m4a", foundInB?.filePath)
    }

    @Test
    fun `SUM只统计指定serverId`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "sA", itemId = "ep1", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sA/ep1.m4a",
                sizeBytes = 1000L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "sA", itemId = "ep2", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sA/ep2.m4a",
                sizeBytes = 2000L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "sB", itemId = "ep1", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sB/ep1.m4a",
                sizeBytes = 999_999L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )

        assertEquals(3000L, dao.totalSizeBytes(serverId = "sA"))
        assertEquals(999_999L, dao.totalSizeBytes(serverId = "sB"))
    }

    /**
     * 表空(或该 serverId 一行都没有)时,DAO 必须把 SQLite 裸 SUM() 的 NULL 收敛成 0L——
     * 这里显式钉死这个决策,不留含糊:调用方(驱逐决策)拿到的永远是确定的 Long。
     */
    @Test
    fun `SUM表空时返回0而不是null`() = runBlocking {
        val dao = db.cachedAudioDao()
        assertEquals(0L, dao.totalSizeBytes(serverId = "s1"))
    }

    @Test
    fun `更新lastAccessAt只改那一行_不动其它字段也不动其它行`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "s1", itemId = "ep1", seriesId = "series1",
                seasonNumber = 1, episodeNumber = 1, filePath = "/cache/s1/ep1.m4a",
                sizeBytes = 100L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "s1", itemId = "ep2", seriesId = "series1",
                seasonNumber = 1, episodeNumber = 2, filePath = "/cache/s1/ep2.m4a",
                sizeBytes = 200L, completedAt = 1500L, lastAccessAt = 1500L,
            )
        )

        dao.touch(serverId = "s1", itemId = "ep1", lastAccessAt = 9999L)

        val touched = dao.findByItemId(serverId = "s1", itemId = "ep1")
        assertEquals(9999L, touched?.lastAccessAt)
        // 其它字段原封不动
        assertEquals("/cache/s1/ep1.m4a", touched?.filePath)
        assertEquals(100L, touched?.sizeBytes)
        assertEquals(1000L, touched?.completedAt)

        // 没被 touch 的另一行不受影响
        val untouched = dao.findByItemId(serverId = "s1", itemId = "ep2")
        assertEquals(1500L, untouched?.lastAccessAt)
    }

    @Test
    fun `按serverId清空只清该服务器`() = runBlocking {
        val dao = db.cachedAudioDao()
        dao.insert(
            CachedAudioEntity(
                serverId = "sA", itemId = "ep1", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sA/ep1.m4a",
                sizeBytes = 100L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )
        dao.insert(
            CachedAudioEntity(
                serverId = "sB", itemId = "ep1", seriesId = null,
                seasonNumber = null, episodeNumber = null, filePath = "/cache/sB/ep1.m4a",
                sizeBytes = 200L, completedAt = 1000L, lastAccessAt = 1000L,
            )
        )

        dao.clearServer("sA")

        assertTrue(dao.findByServer(serverId = "sA").isEmpty())
        assertEquals(listOf("ep1"), dao.findByServer(serverId = "sB").map { it.itemId })
    }
}
