package dev.insua.jellycast.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedItemDaoTest {

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
    fun `observeBucket按position升序返回_与插入顺序和itemId字典序都刻意相反`() = runBlocking {
        val dao = db.cachedItemDao()
        // 刻意构造:插入顺序 = a-high, b-mid, c-low(position 最小的最后插入,rowid 最大);
        // itemId 字典序同样是 a-high < b-mid < c-low —— 与期望的 position 升序结果完全相反。
        // 只有真的执行了 ORDER BY position ASC,才会返回 [c-low, b-mid, a-high]。
        dao.replaceBucket(
            serverId = "s1", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "a-high",
                    position = 2, payloadJson = "{}", updatedAt = 1L
                ),
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "b-mid",
                    position = 1, payloadJson = "{}", updatedAt = 1L
                ),
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "c-low",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )

        val result = dao.observeBucket(serverId = "s1", bucket = "home.resume").first()
        assertEquals(listOf("c-low", "b-mid", "a-high"), result.map { it.itemId })
    }

    @Test
    fun `replaceBucket全量替换_旧条目不残留`() = runBlocking {
        val dao = db.cachedItemDao()
        dao.replaceBucket(
            serverId = "s1", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "old-1",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "old-2",
                    position = 1, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )

        dao.replaceBucket(
            serverId = "s1", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "s1", bucket = "home.resume", itemId = "new-1",
                    position = 0, payloadJson = "{}", updatedAt = 2L
                ),
            )
        )

        val result = dao.observeBucket(serverId = "s1", bucket = "home.resume").first()
        assertEquals(listOf("new-1"), result.map { it.itemId })
    }

    @Test
    fun `不同serverId的同名bucket互不干扰`() = runBlocking {
        val dao = db.cachedItemDao()
        dao.replaceBucket(
            serverId = "sA", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "sA", bucket = "home.resume", itemId = "a1",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )
        dao.replaceBucket(
            serverId = "sB", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "sB", bucket = "home.resume", itemId = "b1",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )

        val resultA = dao.observeBucket(serverId = "sA", bucket = "home.resume").first()
        val resultB = dao.observeBucket(serverId = "sB", bucket = "home.resume").first()
        assertEquals(listOf("a1"), resultA.map { it.itemId })
        assertEquals(listOf("b1"), resultB.map { it.itemId })
    }

    @Test
    fun `clearServer只清该服务器_另一服务器的缓存原封不动`() = runBlocking {
        val dao = db.cachedItemDao()
        dao.replaceBucket(
            serverId = "sA", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "sA", bucket = "home.resume", itemId = "a1",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )
        dao.replaceBucket(
            serverId = "sB", bucket = "home.resume", items = listOf(
                CachedItemEntity(
                    serverId = "sB", bucket = "home.resume", itemId = "b1",
                    position = 0, payloadJson = "{}", updatedAt = 1L
                ),
            )
        )

        dao.clearServer("sA")

        assertTrue(dao.observeBucket(serverId = "sA", bucket = "home.resume").first().isEmpty())
        assertEquals(
            listOf("b1"),
            dao.observeBucket(serverId = "sB", bucket = "home.resume").first().map { it.itemId }
        )
    }

    @Test
    fun `空bucket返回空列表`() = runBlocking {
        val dao = db.cachedItemDao()
        val result = dao.observeBucket(serverId = "s1", bucket = "home.resume").first()
        assertTrue(result.isEmpty())
    }
}
