package dev.insua.jellycast.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.database.CachedAudioEntity
import dev.insua.jellycast.database.JellyCastDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val SERVER_ID = "s1"
private const val ITEM_ID = "ep1"

/**
 * 覆盖 [AudioCacheStore] 的核心保证:不完整的文件绝不能被 [AudioCacheStore.pathIfComplete]
 * 当成可播放的。用 [MockWebServer] 在设备本机起一个假服务器,而不是打真实的 Jellyfin 服务器——
 * 这里需要的是"中途取消"这种真实网络传输才有的、可控且确定的时序,`throttleBody` 让传输被拉长
 * 到肉眼可控的秒级,测试才能在传输过程中精确取消。
 */
@RunWith(AndroidJUnit4::class)
class AudioCacheStoreTest {

    private lateinit var db: JellyCastDatabase
    private lateinit var dao: CachedAudioDao
    private lateinit var server: MockWebServer
    private lateinit var store: AudioCacheStore
    private lateinit var context: Context
    private var clockMs = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, JellyCastDatabase::class.java).build()
        dao = db.cachedAudioDao()
        server = MockWebServer().apply { start() }
        store = AudioCacheStore(
            context = context,
            dao = dao,
            downloader = AudioCacheDownloader(OkHttpClient()),
            clock = { clockMs },
        )
        // 每个用例独立的目录状态:上一个用例(或上一次跑残留的进程)留下的文件不能串到这一个用例里。
        cacheServerDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        cacheServerDir().deleteRecursively()
    }

    private fun cacheServerDir(): File {
        val root = context.getExternalFilesDir("audio-cache") ?: File(context.filesDir, "audio-cache")
        return File(root, SERVER_ID)
    }

    private fun meta() = AudioCacheMeta(seriesId = "series1", seasonNumber = 1, episodeNumber = 1)

    // ---------------------------------------------------------------------
    // 下载完成
    // ---------------------------------------------------------------------

    @Test
    fun `下载完成后_文件存在_大小与索引一致_pathIfComplete返回该路径`() = runBlocking {
        val body = ByteArray(50_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val stored = store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString())
        assertTrue("下载应当成功", stored)

        val entity = dao.findByItemId(SERVER_ID, ITEM_ID)
        assertNotNull("索引里应该有这一行", entity)
        val file = File(entity!!.filePath)
        assertTrue("正式文件应该存在", file.exists())
        assertEquals("文件大小应该和实际字节数一致", body.size.toLong(), file.length())
        assertEquals("索引里记录的大小应该和文件大小一致", body.size.toLong(), entity.sizeBytes)

        assertEquals(entity.filePath, store.pathIfComplete(SERVER_ID, ITEM_ID))

        // .part 临时文件不应该遗留下来。
        val leftoverParts = cacheServerDir().listFiles { f -> f.name.endsWith(".part") }
        assertTrue("成功后不应该留下 .part 文件", leftoverParts.isNullOrEmpty())
    }

    // ---------------------------------------------------------------------
    // 下载中途取消——本任务的核心保证
    // ---------------------------------------------------------------------

    @Test
    fun `下载中途取消_临时文件被清掉_索引里没有记录_pathIfComplete返回null`() = runBlocking {
        // 300KB,每 200ms 最多发 20KB —— 完整传完大约需要 3 秒,留出充足的窗口在中途取消。
        val body = ByteArray(300_000) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(body))
                .throttleBody(20_000, 200, TimeUnit.MILLISECONDS)
        )

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString())
        }
        kotlinx.coroutines.delay(300)
        job.cancelAndJoin()

        // 核心断言:索引里绝不能残留这一行——这正是"先写索引再下载"这种错误实现会破坏的地方。
        assertNull("取消后索引里不应该有这条记录", dao.findByItemId(SERVER_ID, ITEM_ID))

        val remaining = cacheServerDir().listFiles()?.toList().orEmpty()
        assertTrue(
            "取消后目录里不应该留下任何文件(正式文件或 .part 文件都不该在):$remaining",
            remaining.isEmpty(),
        )

        assertNull("不完整的文件不能被当成可播放的", store.pathIfComplete(SERVER_ID, ITEM_ID))
    }

    // ---------------------------------------------------------------------
    // 索引脏了(文件被外部删除)
    // ---------------------------------------------------------------------

    @Test
    fun `索引里有记录但文件被外部删除_pathIfComplete返回null并清掉脏记录`() = runBlocking {
        dao.insert(
            CachedAudioEntity(
                serverId = SERVER_ID,
                itemId = ITEM_ID,
                seriesId = "series1",
                seasonNumber = 1,
                episodeNumber = 1,
                filePath = File(cacheServerDir(), "ghost-file").absolutePath,
                sizeBytes = 12_345L,
                completedAt = 1_000L,
                lastAccessAt = 1_000L,
            )
        )
        // 特意不创建这个文件:模拟"索引说有,实际没有"。

        val result = store.pathIfComplete(SERVER_ID, ITEM_ID)

        assertNull("文件不存在时必须返回 null,不能抛异常", result)
        assertNull("脏记录应该被顺手清掉", dao.findByItemId(SERVER_ID, ITEM_ID))
    }

    // ---------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------

    @Test
    fun `delete同时删文件与索引`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))
        val filePath = dao.findByItemId(SERVER_ID, ITEM_ID)!!.filePath

        store.delete(SERVER_ID, ITEM_ID)

        assertNull("索引应该被删掉", dao.findByItemId(SERVER_ID, ITEM_ID))
        assertFalse("文件应该被删掉", File(filePath).exists())
    }

    @Test
    fun `delete对不存在的条目安静地什么都不做`() = runBlocking {
        // 不应该抛异常。
        store.delete(SERVER_ID, "nobody")
        assertNull(dao.findByItemId(SERVER_ID, "nobody"))
    }

    // ---------------------------------------------------------------------
    // touch
    // ---------------------------------------------------------------------

    @Test
    fun `touch更新最后访问时间`() = runBlocking {
        clockMs = 1_000L
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))
        assertEquals(1_000L, dao.findByItemId(SERVER_ID, ITEM_ID)!!.lastAccessAt)

        clockMs = 9_999L
        store.touch(SERVER_ID, ITEM_ID)

        assertEquals(9_999L, dao.findByItemId(SERVER_ID, ITEM_ID)!!.lastAccessAt)
    }

    // ---------------------------------------------------------------------
    // totalBytes
    // ---------------------------------------------------------------------

    @Test
    fun `totalBytes反映已缓存条目的总大小`() = runBlocking {
        assertEquals(0L, store.totalBytes(SERVER_ID))

        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))

        assertEquals(1_000L, store.totalBytes(SERVER_ID))
    }

    // ---------------------------------------------------------------------
    // 启动孤儿清扫
    // ---------------------------------------------------------------------

    @Test
    fun `sweepOrphans清掉索引之外的文件和所有part文件_保留索引里的文件`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))
        val indexedPath = dao.findByItemId(SERVER_ID, ITEM_ID)!!.filePath

        val orphanFile = File(cacheServerDir(), "orphan-item").apply { writeBytes(ByteArray(10)) }
        val strayPart = File(cacheServerDir(), "half-downloaded.part").apply { writeBytes(ByteArray(10)) }

        store.sweepOrphans(SERVER_ID)

        assertTrue("索引里有记录的文件应该保留", File(indexedPath).exists())
        assertFalse("索引之外的文件应该被清掉", orphanFile.exists())
        assertFalse(".part 文件应该被无条件清掉", strayPart.exists())
    }

    /**
     * 复审 Important:[AudioCacheStore.sweepOrphans] 原来只信"文档写了只在启动时调用",没有任何
     * 代码层面的保护。这条用例钉死"和正在进行的 [AudioCacheStore.store] 撞在同一个 serverId 上
     * 也必须安全"——下载中途被并发调用的 sweepOrphans 不能把它正在写的 `.part` 文件删掉,
     * 否则 `renameTo` 之后按路径找不到源文件,下载会莫名其妙失败。
     */
    @Test
    fun `sweepOrphans与进行中的下载并发_不影响下载完成_文件保留`() = runBlocking {
        val body = ByteArray(300_000) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(body))
                .throttleBody(20_000, 200, TimeUnit.MILLISECONDS)
        )

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString())
        }
        kotlinx.coroutines.delay(300)

        // 下载还没完成的时候扫一次孤儿——不该把正在写的 .part 文件当成孤儿删掉。
        store.sweepOrphans(SERVER_ID)

        job.join()

        val entity = dao.findByItemId(SERVER_ID, ITEM_ID)
        assertNotNull("in-flight 期间跑一次 sweepOrphans 不该打断这次下载", entity)
        val file = File(entity!!.filePath)
        assertTrue("下载完成后文件应该还在", file.exists())
        assertEquals("文件应该完整——没有被 unlink 后又在半路断掉", body.size.toLong(), file.length())
    }
}
