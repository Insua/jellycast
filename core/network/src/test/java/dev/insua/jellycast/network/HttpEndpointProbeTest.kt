package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpEndpointProbeTest {

    private fun endpointFor(server: MockWebServer) =
        Endpoint(server.url("/").toString().trimEnd('/'), "test", 1)

    // Finding 2: the probe timeout used to be a bare `3, TimeUnit.SECONDS` literal baked into
    // HttpEndpointProbe, independent of EndpointSelector's timeoutMs. Prove the constructor
    // parameter actually reaches the underlying OkHttp connect/read timeouts: a probe configured
    // with a 100ms timeout must fail fast against a server that only responds after 2s, instead
    // of waiting out the old hardcoded 3s (or the mock's 2s delay).
    @Test fun `timeoutMs 构造参数真正传导到底层连接读超时`() {
        val server = MockWebServer().apply {
            // headersDelay (not bodyDelay!) holds the response headers back, so the probe — which
            // never reads the body, only the status code — actually has to wait on the socket
            // instead of getting an instant onResponse callback.
            enqueue(MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))
            start()
        }
        val probe = HttpEndpointProbe(OkHttpClient(), timeoutMs = 100)

        val startedAt = System.currentTimeMillis()
        val health = runBlocking { probe.probe(endpointFor(server)) }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertFalse(health.reachable)
        assertTrue(
            elapsedMs < 2000,
            "应当在远小于 mock 响应延迟(2s)的时间内因超时失败,实际耗时 ${elapsedMs}ms",
        )
        server.shutdown()
    }

    // Finding 1: HttpEndpointProbe.probe() must actually cancel the underlying OkHttp Call when
    // its coroutine is cancelled (as EndpointSelector.select() does to every losing probe), not
    // just abandon the coroutine while the HTTP call keeps running to its own timeout. We prove
    // this deterministically: point the probe at a MockWebServer response delayed by 10s (far
    // longer than anything in this test), start the probe, wait until OkHttp's dispatcher shows a
    // call actually in flight, cancel the coroutine, and assert the dispatcher's running-call
    // count drops back to zero well before the 10s mock delay could possibly have elapsed. If
    // cancellation were bookkeeping-only, runningCallsCount would stay at 1 for the full 10s and
    // this test would time out.
    @Test fun `协程取消会真正取消底层 HTTP 调用,而不是让它继续跑到自己的超时`() = runBlocking {
        val server = MockWebServer().apply {
            // headersDelay so the OkHttp call stays genuinely in flight (waiting on the socket for
            // headers) long enough for the test to observe it as "running" before cancelling.
            enqueue(MockResponse().setBody("{}").setHeadersDelay(10, TimeUnit.SECONDS))
            start()
        }
        val client = OkHttpClient()
        val probe = HttpEndpointProbe(client, timeoutMs = 30_000)
        val endpoint = endpointFor(server)

        val job = launch(Dispatchers.IO) { probe.probe(endpoint) }

        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() == 0) delay(10)
        }

        job.cancel()

        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }
        job.join()

        server.shutdown()
    }

    // 真机 bug:`probe()` 从 `Dispatchers.Main`(ViewModel 的 viewModelScope)调用时抛
    // NetworkOnMainThreadException。原因不是 enqueue —— 而是 `use { }` 退出时的
    // `Response.close()`:探测从不读响应体,OkHttp 为了复用连接会 drain 掉剩余的响应体
    // (FixedLengthSource.close → discard → skipAll → SocketInputStream.read),
    // 而 suspendCancellableCoroutine 的续体是按**调用方的调度器**恢复的,于是这次阻塞
    // socket 读落在调用方线程上。
    //
    // StrictMode 只有 Android 上才有,所以这条离线用例换个等价的可观测量:OkHttp 的
    // EventListener.callEnd 正是在关闭响应体的那个线程上回调的。断言它没有落在调用方线程上,
    // 就等价于断言"没有在调用方线程上做 socket I/O"。修复前这条会红(callEnd 跑在
    // jellycast-caller 上),修复后为绿。协程调试模式会给线程名追加 " @coroutine#N",
    // 所以按前缀匹配而不是全等。
    @Test fun `probe 的响应体收尾不落在调用方线程上`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"ServerName":"x","Version":"10.10.7","Id":"abc"}"""))
            start()
        }
        val callEndThreads = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callEnd(call: Call) {
                    callEndThreads += Thread.currentThread().name
                }
            })
            .build()
        val callerExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, CALLER_THREAD) }

        try {
            val health = runBlocking(callerExecutor.asCoroutineDispatcher()) {
                HttpEndpointProbe(client).probe(endpointFor(server))
            }

            assertTrue(health.reachable, "探测应当成功,实际失败原因:${health.failureReason}")
            assertTrue(callEndThreads.isNotEmpty(), "EventListener.callEnd 应当被回调过")
            assertTrue(
                callEndThreads.none { it.startsWith(CALLER_THREAD) },
                "响应体收尾(阻塞 socket 读)不得发生在调用方线程上,实际线程:$callEndThreads",
            )
        } finally {
            callerExecutor.shutdown()
            server.shutdown()
        }
    }

    private companion object {
        const val CALLER_THREAD = "jellycast-caller"
    }
}
