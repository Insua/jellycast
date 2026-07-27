package dev.insua.jellycast.network.repository

import app.cash.turbine.test
import dev.insua.jellycast.database.CacheBucketMetaEntity
import dev.insua.jellycast.database.CachedItemDao
import dev.insua.jellycast.database.CachedItemEntity
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.session.JellyfinSession
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 仓储层的 stale-while-revalidate 行为。用**内存假 DAO**,不拉真 Room —— JVM 单测里
 * 起一个 Room 实例既慢又需要 Android runtime,而这里要验的全部是"什么时候发什么、写不写库",
 * 跟 SQLite 的实现细节无关。真 Room 的行为由 `:core:database` 的 androidTest 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryTest {

    // ---- 内存假 DAO ----

    private class FakeCachedItemDao : CachedItemDao {
        /** serverId + bucket -> 行,模拟真表的分区语义。 */
        val rows = MutableStateFlow<Map<Pair<String, String>, List<CachedItemEntity>>>(emptyMap())

        /** serverId + bucket -> 有没有被成功刷新过,模拟 `cache_bucket_meta` 表。 */
        val refreshedBuckets = mutableSetOf<Pair<String, String>>()

        /** 记录每一次整体替换,用来断言"网络成功才写回、失败绝不碰库"。 */
        val replaceCalls = mutableListOf<Pair<String, List<CachedItemEntity>>>()

        override fun observeBucket(serverId: String, bucket: String): Flow<List<CachedItemEntity>> =
            rows.map { it[serverId to bucket].orEmpty().sortedBy { row -> row.position } }

        override suspend fun hasRefreshedBucket(serverId: String, bucket: String): Boolean =
            (serverId to bucket) in refreshedBuckets

        override suspend fun replaceBucket(
            serverId: String,
            bucket: String,
            items: List<CachedItemEntity>,
            refreshedAt: Long,
        ) {
            replaceCalls += bucket to items
            rows.value = rows.value + ((serverId to bucket) to items)
            refreshedBuckets += serverId to bucket
        }

        override suspend fun deleteBucket(serverId: String, bucket: String) {
            rows.value = rows.value - (serverId to bucket)
        }

        override suspend fun insertAll(items: List<CachedItemEntity>) = Unit

        override suspend fun upsertBucketMeta(meta: CacheBucketMetaEntity) {
            refreshedBuckets += meta.serverId to meta.bucket
        }

        override suspend fun clearServer(serverId: String) {
            rows.value = rows.value.filterKeys { it.first != serverId }
            refreshedBuckets.removeAll { it.first == serverId }
        }

        override suspend fun deleteItemsForServer(serverId: String) {
            rows.value = rows.value.filterKeys { it.first != serverId }
        }

        override suspend fun deleteMetaForServer(serverId: String) {
            refreshedBuckets.removeAll { it.first == serverId }
        }

        override suspend fun clearAll() {
            rows.value = emptyMap()
            refreshedBuckets.clear()
        }

        override suspend fun deleteAllItems() {
            rows.value = emptyMap()
        }

        override suspend fun deleteAllMeta() {
            refreshedBuckets.clear()
        }
    }

    private val serverId = "server-1"
    private val bucket = CacheBuckets.HOME_RESUME

    private fun episode(id: String) = MediaItem(
        id = id,
        kind = MediaKind.EPISODE,
        name = "第 $id 集",
        seriesName = "某剧",
        seasonNumber = 1,
        episodeNumber = 2,
        runTimeMs = 1_200_000,
        resumePositionMs = 300_000,
        imageTag = "tag-$id",
    )

    private fun session(id: String? = serverId): JellyfinSession {
        val s = mockk<JellyfinSession>()
        if (id == null) coEvery { s.serverId() } throws IllegalStateException("没有已激活的服务器")
        else coEvery { s.serverId() } returns id
        return s
    }

    private fun repository(dao: CachedItemDao, session: JellyfinSession = session()): MediaRepository =
        CachingMediaRepository(dao, session)

    /** 直接把一批条目塞进假 DAO,模拟"上次成功刷新留下的缓存"。 */
    private suspend fun FakeCachedItemDao.seed(bucket: String, items: List<MediaItem>) {
        replaceBucket(
            serverId,
            bucket,
            items.mapIndexed { index, item ->
                CachedItemEntity(serverId, bucket, item.id, index, encodeForTest(item), 0L)
            },
        )
        replaceCalls.clear()
    }

    // ---- 有缓存:第一次发射就是缓存,不等网络 ----

    @Test
    fun `有缓存时第一次发射就是缓存,不等网络返回`() = runTest {
        val dao = FakeCachedItemDao()
        val cached = listOf(episode("c1"), episode("c2"))
        dao.seed(bucket, cached)

        var fetchStarted = false
        val flow = repository(dao).bucket(bucket) {
            fetchStarted = true
            delay(10_000) // 网络故意很慢
            listOf(episode("n1"))
        }

        flow.test {
            // 网络还挂在 delay 上,缓存必须已经到手 —— 这一条就是"打开就有内容"的全部意义。
            val first = withTimeout(1_000) { awaitItem() }
            assertEquals(cached, first.data)
            assertTrue(first.isStale, "缓存必须被标记为旧数据")
            assertFalse(first.refreshFailed, "刷新还没结束,不能提前说失败")
            assertTrue(fetchStarted, "缓存发完之后网络请求要照常发出,不能只吃缓存不刷新")

            advanceUntilIdle()
            assertEquals(listOf(episode("n1")), awaitItem().data)
            awaitComplete()
        }
    }

    @Test
    fun `网络成功后发射新数据并写回缓存`() = runTest {
        val dao = FakeCachedItemDao()
        dao.seed(bucket, listOf(episode("c1")))
        val fresh = listOf(episode("n1"), episode("n2"))

        val emissions = repository(dao).bucket(bucket) { fresh }.collectToList()

        assertEquals(
            listOf(listOf(episode("c1")), fresh),
            emissions.map { it.data },
            "发射序列必须是 [缓存, 新数据]",
        )
        assertEquals(listOf(true, false), emissions.map { it.isStale })

        assertEquals(1, dao.replaceCalls.size, "成功刷新必须整体替换 bucket,恰好写一次")
        assertEquals(listOf("n1", "n2"), dao.replaceCalls.single().second.map { it.itemId })
        assertEquals(listOf(0, 1), dao.replaceCalls.single().second.map { it.position }, "服务端顺序必须落库")
    }

    @Test
    fun `写回的缓存能被下一次读取原样还原`() = runTest {
        val dao = FakeCachedItemDao()
        val fresh = listOf(episode("n1"), episode("n2"))

        repository(dao).bucket(bucket) { fresh }.collectToList()

        // 第二次:网络直接炸,只能靠上一轮写回的缓存 —— 这就是断网启动的真实路径。
        val offline = repository(dao).bucket(bucket) { throw java.io.IOException("offline") }.collectToList()

        assertEquals(fresh, offline.first().data, "序列化往返必须无损,包括时长/进度/封面 tag")
        assertEquals(2, offline.size)
        assertTrue(offline.last().refreshFailed)
    }

    // ---- 网络失败:保留缓存 + refreshFailed,绝不抛异常 ----

    @Test
    fun `网络失败时保留缓存并标记刷新失败且不抛异常`() = runTest {
        val dao = FakeCachedItemDao()
        val cached = listOf(episode("c1"))
        dao.seed(bucket, cached)

        val emissions = repository(dao)
            .bucket(bucket) { throw java.io.IOException("Unable to resolve host") }
            .collectToList()

        assertEquals(listOf(cached, cached), emissions.map { it.data })
        assertEquals(listOf(false, true), emissions.map { it.refreshFailed })
        assertTrue(dao.replaceCalls.isEmpty(), "刷新失败绝不能写库 —— 一次抖动不许抹掉用户的缓存")
    }

    // ---- 无缓存 + 网络失败:一次都不发,交给 ViewModel 转错误态,不崩溃 ----

    @Test
    fun `无缓存且网络失败时不发射也不崩溃`() = runTest {
        val dao = FakeCachedItemDao()

        val emissions = repository(dao)
            .bucket(bucket) { throw java.io.IOException("offline") }
            .collectToList()

        assertTrue(emissions.isEmpty(), "没有任何可显示的数据,由调用方转成可重试的错误态")
        assertTrue(dao.replaceCalls.isEmpty())
    }

    @Test
    fun `会话没有激活服务器时也不崩溃`() = runTest {
        val dao = FakeCachedItemDao()

        // serverId() 抛异常(还没登录 / 服务器被删掉)—— 缓存无从谈起,但绝不允许把异常抛给 UI。
        val emissions = repository(dao, session(id = null))
            .bucket(bucket) { listOf(episode("n1")) }
            .collectToList()

        assertEquals(listOf(listOf(episode("n1"))), emissions.map { it.data })
        assertTrue(dao.replaceCalls.isEmpty(), "不知道该写到哪台服务器名下时,宁可不写")
    }

    // ---- "服务器确实没有" ≠ "请求失败" ----

    @Test
    fun `网络成功返回空列表会如实发射空并清掉缓存`() = runTest {
        val dao = FakeCachedItemDao()
        dao.seed(bucket, listOf(episode("c1")))

        val emissions = repository(dao).bucket(bucket) { emptyList<MediaItem>() }.collectToList()

        assertEquals(listOf(listOf(episode("c1")), emptyList<MediaItem>()), emissions.map { it.data })
        assertFalse(emissions.last().isStale, "服务端说没有,就是一个新鲜的结论,不是旧数据")
        assertFalse(emissions.last().refreshFailed, "成功不能被标记成失败")
        assertEquals(1, dao.replaceCalls.size)
        assertTrue(dao.replaceCalls.single().second.isEmpty(), "确知服务端没有内容时缓存要跟着清空")
    }

    // ---- 读缓存和写回缓存必须用同一个 serverId:两者之间隔着一次网络请求,
    // 如果各自独立解析,用户中途切换了激活服务器,读到的是服务器 A、写回的却是服务器 B ----

    @Test
    fun `一次取数只解析一次serverId_写回缓存复用读缓存时解析出来的值`() = runTest {
        val dao = FakeCachedItemDao()
        val session = mockk<JellyfinSession>()
        // 模拟"读缓存时还是 server-1,写回缓存那一刻(网络请求之后)已经切到 server-2"——
        // 如果读和写各自独立调 serverId(),写回会用上第二个值,数据就串到别的服务器名下了。
        coEvery { session.serverId() } returnsMany listOf("server-1", "server-2", "server-3")

        CachingMediaRepository(dao, session).bucket(bucket) { listOf(episode("n1")) }.collectToList()

        assertTrue(
            dao.rows.value.containsKey("server-1" to bucket),
            "写回缓存必须用读缓存那一刻解析出来的 serverId,不能中途切换服务器时串到别的分区",
        )
        assertTrue(
            dao.rows.value.keys.none { it.first != "server-1" },
            "serverId 只应该被解析一次(读缓存那一次),写回不该再问一次拿到不同的值",
        )
    }

    @Test
    fun `分页bucket同样只解析一次serverId`() = runTest {
        val dao = FakeCachedItemDao()
        val session = mockk<JellyfinSession>()
        coEvery { session.serverId() } returnsMany listOf("server-1", "server-2", "server-3")
        val page = ItemPage(listOf(episode("n1")), total = 10)

        CachingMediaRepository(dao, session).pagedBucket(bucket) { page }.collectToList()

        assertTrue(dao.rows.value.containsKey("server-1" to bucket))
        assertTrue(dao.rows.value.keys.none { it.first != "server-1" })
    }

    @Test
    fun `请求失败不会被当成空结果处理`() = runTest {
        val dao = FakeCachedItemDao()
        dao.seed(bucket, listOf(episode("c1")))

        val emissions = repository(dao).bucket(bucket) { throw RuntimeException("500") }.collectToList()

        assertTrue(
            emissions.none { it.data.isEmpty() },
            "一次 500 被当成'服务器没有内容'就会清空用户整个缓存库,屏幕变白 —— 这正是要防的",
        )
        assertTrue(dao.replaceCalls.isEmpty())
    }

    // ---- 不同服务器的缓存互不串号 ----

    @Test
    fun `不同服务器的同名 bucket 互不串号`() = runTest {
        val dao = FakeCachedItemDao()
        dao.seed(bucket, listOf(episode("server1-item")))

        val other = repository(dao, session(id = "server-2"))
            .bucket(bucket) { throw java.io.IOException("offline") }
            .collectToList()

        assertTrue(other.isEmpty(), "另一台服务器不该读到这台服务器的缓存")
    }

    // ---- 分页 bucket:总数不入缓存,缓存回来时 total 为 null ----

    @Test
    fun `分页 bucket 缓存不带总数,网络回来才有总数`() = runTest {
        val dao = FakeCachedItemDao()
        val page = ItemPage(listOf(episode("a"), episode("b")), total = 120)

        repository(dao).pagedBucket(CacheBuckets.LIBRARY_SERIES) { page }.collectToList()

        val offline = repository(dao)
            .pagedBucket(CacheBuckets.LIBRARY_SERIES) { throw java.io.IOException("offline") }
            .collectToList()

        assertEquals(page.items, offline.first().data.items)
        assertEquals(
            null,
            offline.first().data.total,
            "总数是服务端的实时口径,不进缓存 —— 缓存里凑出来的假总数会让分页误判'已到底'",
        )
    }

    // ---- 取消必须继续向上传播,不能被 runCatching 吞掉当成"网络失败" ----

    @Test
    fun `收集被取消时不会被当成网络失败`() = runTest {
        val dao = FakeCachedItemDao()
        dao.seed(bucket, listOf(episode("c1")))
        var sawCancellation = false

        val job = launch {
            repository(dao).bucket(bucket) {
                try {
                    delay(10_000)
                    listOf(episode("n1"))
                } catch (e: CancellationException) {
                    sawCancellation = true
                    throw e
                }
            }.collect { }
        }

        // 只跑到"请求已发出、正挂在 delay 上"为止 —— 不能 advanceUntilIdle,
        // 那会把虚拟时间推过 10 秒让请求正常完成,取消就无从谈起了。
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertTrue(sawCancellation, "取消必须以 CancellationException 的形式穿过仓储,不能被吞成一次'刷新失败'")
        assertTrue(dao.replaceCalls.isEmpty())
    }

    // ---- 损坏的缓存行不得让整个列表崩掉 ----

    @Test
    fun `缓存里有损坏的行时跳过它而不是崩溃`() = runTest {
        val dao = FakeCachedItemDao()
        dao.replaceBucket(
            serverId,
            bucket,
            listOf(
                CachedItemEntity(serverId, bucket, "good", 0, encodeForTest(episode("good")), 0L),
                CachedItemEntity(serverId, bucket, "broken", 1, "{ 这不是 JSON", 0L),
            ),
        )
        dao.replaceCalls.clear()

        val emissions = repository(dao).bucket(bucket) { throw java.io.IOException("offline") }.collectToList()

        assertEquals(listOf("good"), emissions.first().data.map { it.id }, "坏行跳过,好行照常显示")
    }
}

/** 测试里造缓存行用的编码器 —— 走的是生产同一套序列化,不是另写一份 JSON 字面量。 */
private fun encodeForTest(item: MediaItem): String = CachedItemPayload.encode(item)

private suspend fun <T> Flow<T>.collectToList(): List<T> = buildList { collect { add(it) } }
