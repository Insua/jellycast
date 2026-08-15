package dev.insua.jellycast.player

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 根因回归(设计文档 §3.1)。
 *
 * 生产上那个 bug 的形状:`/Audio/{id}/universal` 对 4K `.ts` 片源要 6.3–46.1 秒才吐响应头,
 * 而 [HttpStreamProbe] 拿到的 client 用的是 OkHttp 默认的 10 秒读超时,于是探测超时、
 * [StreamProbe.isAudioOnly] 返回 `null`,`PlaybackSourceResolver` 按"没结论"降级到 L3 ——
 * L3 是 54.5 Mbps 的视频流拷贝,必然卡死。
 *
 * ⚠️ 这条用例故意慢(15 秒)。它复现的就是"等得够不够久",缩短延迟就等于删掉它的鉴别力。
 */
class HttpStreamProbeTimeoutTest {

    /**
     * 服务端 15 秒后才吐响应头 —— 超过 OkHttp 默认的 10 秒读超时,但在生产值 60 秒之内。
     * 探测必须**等到**并给出 `true`,而不是超时后返回 `null`。
     */
    @Test
    fun `响应头慢到超过OkHttp默认读超时时探测仍然给得出结论`() = runTest {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody("fake-adts")
                    .setHeadersDelay(15, TimeUnit.SECONDS),
            )
            start()
        }
        try {
            // 生产装配:注入一个没设过任何超时的 client(= TrustAwareHttpClientModule 的形态)
            val probe = HttpStreamProbe(OkHttpClient())
            val verdict = probe.isAudioOnly(server.url("/Audio/x/universal").toString())
            assertEquals(true, verdict, "慢响应头被当成了「没结论」,会错误降级到 L3")
        } finally {
            server.shutdown()
        }
    }

    /**
     * 读超时确实生效、且**没有**被连接超时压过去:注入 connect=200ms / read=1500ms,
     * 服务端延迟 800ms —— 只有当两个超时是分开的时候这条才会通过。
     */
    @Test
    fun `连接超时短读取超时长时慢响应头仍能读到`() = runTest {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody("fake-adts")
                    .setHeadersDelay(800, TimeUnit.MILLISECONDS),
            )
            start()
        }
        try {
            val probe = HttpStreamProbe(OkHttpClient(), connectTimeoutMs = 200L, readTimeoutMs = 1500L)
            assertEquals(true, probe.isAudioOnly(server.url("/x").toString()))
        } finally {
            server.shutdown()
        }
    }

    /**
     * 连接超时**没有**被拉长到读超时那么久:指向一个不可路由的地址,read=15s 而 connect=200ms,
     * 必须远早于 15 秒失败,并且按 [StreamProbe] 的三态约定返回 `null`(没结论,不是"不是纯音频")。
     *
     * ⚠️ 变异验证时若把 connectTimeoutMs 改成 15_000L 这条**仍然绿**,说明本机网络栈是瞬间回
     * unreachable 而不是真的挂起 —— 那样它就是一条空测试,**删掉它**并在报告里写明,不要留着。
     */
    @Test
    fun `连不上的地址快速失败而不是等满读超时`() = runTest {
        val probe = HttpStreamProbe(OkHttpClient(), connectTimeoutMs = 200L, readTimeoutMs = 15_000L)
        val start = System.nanoTime()
        val verdict = probe.isAudioOnly("http://10.255.255.1:8096/Audio/x/universal")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(verdict, "连不上必须是「没结论」(null),不能是「不是纯音频」(false)")
        assertTrue(elapsedMs < 5_000, "连接失败用了 ${elapsedMs}ms,连接超时被读超时压过去了")
    }
}
