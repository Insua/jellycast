package dev.insua.jellycast.network

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.insua.jellycast.model.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复现并守住真机上「测试连接」报 `NetworkOnMainThreadException` 的那条路径。
 *
 * ## 为什么这条用例必须是 instrumentation 测试
 *
 * `NetworkOnMainThreadException` 由 Android 的 StrictMode / BlockGuard 抛出,纯 JVM 单测里根本
 * 没有这套机制——所以 274 个 JVM 单测全绿,真机第一次跑就炸。
 *
 * ## 被守住的具体缺陷
 *
 * `HttpEndpointProbe.probe()` 只看状态码、从不读响应体,`use { }` 退出时 OkHttp 会把没读完的
 * 响应体 drain 掉以便复用连接:`Http1ExchangeCodec$FixedLengthSource.close()` → `discard()` →
 * `skipAll()` → `SocketInputStream.read()`,一次阻塞 socket 读。`suspendCancellableCoroutine`
 * 的续体按**调用方的调度器**恢复,调用方是 `viewModelScope.launch { }`(`Dispatchers.Main`),
 * 于是这次 socket 读落在主线程上。
 *
 * 测试里显式安装 `detectNetwork() + penaltyDeathOnNetwork()`,而不是依赖 ActivityThread 给主线程
 * 装的默认策略——真机上那条默认策略就是这么来的,显式安装只是让复现确定、不依赖运行环境。
 */
@RunWith(AndroidJUnit4::class)
class EndpointProbeMainThreadTest {

    private lateinit var server: MockWebServer
    private var savedPolicy: StrictMode.ThreadPolicy? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            savedPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyDeathOnNetwork()
                    .build(),
            )
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            savedPolicy?.let { StrictMode.setThreadPolicy(it) }
        }
        server.shutdown()
    }

    private fun endpoint() = Endpoint(server.url("/").toString().trimEnd('/'), "地址1", 0)

    private fun healthyBody() =
        MockResponse().setBody("""{"ServerName":"jellycast-test","Version":"10.10.7","Id":"abc"}""")

    /** 「测试连接」走的就是 probeAll();ViewModel 从 `Dispatchers.Main` 调它是正常用法。 */
    @Test
    fun probeAll_from_main_dispatcher_is_reachable() = runBlocking {
        server.enqueue(healthyBody())
        val selector = EndpointSelector(HttpEndpointProbe(OkHttpClient()))

        val results = withContext(Dispatchers.Main) { selector.probeAll(listOf(endpoint())) }

        assertEquals(1, results.size)
        assertEquals(null, results[0].failureReason)
        assertTrue("从 Dispatchers.Main 调用 probeAll 必须成功探测", results[0].reachable)
    }

    /** select() 是另一套并发实现(launch + Channel),同样必须能从主线程调用。 */
    @Test
    fun select_from_main_dispatcher_returns_winner() = runBlocking {
        server.enqueue(healthyBody())
        val selector = EndpointSelector(HttpEndpointProbe(OkHttpClient()))

        val winner = withContext(Dispatchers.Main) { selector.select(listOf(endpoint())) }

        assertTrue("从 Dispatchers.Main 调用 select 必须选出可用地址", winner?.reachable == true)
    }
}
