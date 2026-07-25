package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
