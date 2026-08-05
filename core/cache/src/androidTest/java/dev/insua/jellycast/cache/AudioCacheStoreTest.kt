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

    // ---------------------------------------------------------------------
    // clearServer(复审 I2:删服务器时的整套清理,此前 CachedAudioDao.clearServer 没有任何生产
    // 调用方,音频缓存的索引行和文件目录会在用户删服务器之后永久占用磁盘)
    // ---------------------------------------------------------------------

    @Test
    fun `clearServer清空索引与整个目录_同服务器下的多集全部被清掉`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
        assertTrue(store.store(SERVER_ID, "ep2", meta(), server.url("/audio").toString()))

        assertEquals("清空前索引应该有两条记录", 2, dao.findByServer(SERVER_ID).size)
        assertTrue("清空前该服务器的目录应该存在", cacheServerDir().exists())

        store.clearServer(SERVER_ID)

        assertTrue("索引应该被整体清空", dao.findByServer(SERVER_ID).isEmpty())
        assertFalse("整个服务器目录应该被递归删除,不留下任何文件", cacheServerDir().exists())
    }

    @Test
    fun `clearServer只清指定服务器_不影响其它服务器的缓存`() = runBlocking {
        val otherServerId = "other-server"
        val otherDir = File(cacheServerDir().parentFile, otherServerId)
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
            assertTrue(store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
            assertTrue(store.store(otherServerId, ITEM_ID, meta(), server.url("/audio").toString()))

            store.clearServer(SERVER_ID)

            assertTrue("被清空的服务器索引应该为空", dao.findByServer(SERVER_ID).isEmpty())
            assertEquals("另一台服务器的索引不应该被动", 1, dao.findByServer(otherServerId).size)
            assertTrue("另一台服务器的目录不应该被删除", otherDir.exists())
        } finally {
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun `clearServer对没有任何缓存的服务器安静地什么都不做`() = runBlocking {
        // 不应该抛异常——这条服务器从来没缓存过任何东西。
        store.clearServer("never-cached-server")
        assertTrue(dao.findByServer("never-cached-server").isEmpty())
    }

    // ---------------------------------------------------------------------
    // I4 复审:断网/切到蜂窝时立即中止当前下载(shouldContinue 按块检查)
    // ---------------------------------------------------------------------

    /**
     * 设计文档 §3「断网/切到蜂窝时:立即停止当前下载,丢弃未完成的部分」。用一个在读了几个缓冲块
     * 之后就翻转为 false 的 [shouldContinue],验证中止发生在**传输中途**(而不是等一整集传完),
     * 且中止之后的清理和普通下载失败完全一致:不留 `.part`、索引里没有记录。
     */
    @Test
    fun `shouldContinue变为false时中途中止下载_不留半成品_索引里没有记录`() = runBlocking {
        // 300KB、限速——确保不会一次 read() 就把整个响应体读完,中途中止才有意义。
        val body = ByteArray(300_000) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(body))
                .throttleBody(20_000, 200, TimeUnit.MILLISECONDS)
        )

        var checks = 0
        val stored = store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()) {
            checks++
            checks < 3 // 放行前两次按块检查,第三次判定"不该继续"——发生在传输中途。
        }

        assertFalse("shouldContinue 判定不该继续之后,这次下载必须失败", stored)
        assertTrue("必须真的中途检查过多次,不能一次就蒙对", checks >= 3)
        assertNull("中止的下载不应该落索引", dao.findByItemId(SERVER_ID, ITEM_ID))
        val remaining = cacheServerDir().listFiles()?.toList().orEmpty()
        assertTrue("中止后不应该留下任何文件(正式文件或 .part 文件都不该在):$remaining", remaining.isEmpty())
    }

    @Test
    fun `shouldContinue全程返回true时下载不受影响`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))

        val stored = store.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString()) { true }

        assertTrue("shouldContinue 全程放行时下载应该正常成功", stored)
        assertNotNull(dao.findByItemId(SERVER_ID, ITEM_ID))
    }

    // ---------------------------------------------------------------------
    // 外部存储不可用时的 filesDir 回退(复审「也一并处理」:这条分支此前完全没有测试覆盖)
    // ---------------------------------------------------------------------

    /** 强制 [Context.getExternalFilesDir] 返回 null,模拟外部存储不可用,走 [Context.filesDir] 回退。 */
    private class NoExternalFilesDirContext(base: Context) : android.content.ContextWrapper(base) {
        override fun getExternalFilesDir(type: String?): File? = null
    }

    @Test
    fun `外部存储不可用时回退到filesDir_下载与查询仍然一致工作`() = runBlocking {
        val fallbackDir = File(context.filesDir, "audio-cache")
        fallbackDir.deleteRecursively()
        val fallbackStore = AudioCacheStore(
            context = NoExternalFilesDirContext(context),
            dao = dao,
            downloader = AudioCacheDownloader(OkHttpClient()),
            clock = { clockMs },
        )

        try {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_000))))
            val stored = fallbackStore.store(SERVER_ID, ITEM_ID, meta(), server.url("/audio").toString())
            assertTrue("外部存储不可用时,回退到 filesDir 之后下载应该照常成功", stored)

            val entity = dao.findByItemId(SERVER_ID, ITEM_ID)
            assertNotNull(entity)
            assertTrue(
                "索引记录的路径应该落在 context.filesDir 下,证明真的走了回退分支,不是外部存储",
                entity!!.filePath.startsWith(context.filesDir.absolutePath),
            )
            assertEquals(
                "回退路径上 pathIfComplete 应该能查到同一份记录——写入和查询用的是同一个 rootDir",
                entity.filePath,
                fallbackStore.pathIfComplete(SERVER_ID, ITEM_ID),
            )
        } finally {
            fallbackDir.deleteRecursively()
        }
    }
}
