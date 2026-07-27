package dev.insua.jellycast.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [HttpStreamProbe] 是 [PlaybackSourceResolver] 用来判断 L1 候选 URL"实测是否真的只吐音频"的
 * 生产实现——Spike 报告里 L1 候选返回 `audio/aac`,被误判为混流(如计划草案里猜测的
 * `stream?audioCodec=aac&static=false` 返回 `video/x-matroska`)的候选必须被识别为"不是纯音频"。
 * 只看响应头的 Content-Type,不下载完整流体——转码流可能有几十兆,不能为了探测就整条拉下来。
 */
class HttpStreamProbeTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `Content-Type 是 audio 前缀时判定为纯音频`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "audio/aac").setBody("x"))
        val probe = HttpStreamProbe(OkHttpClient())

        assertTrue(probe.isAudioOnly(server.url("/Audio/1/universal").toString()))
    }

    @Test fun `Content-Type 是 video 前缀时判定为不是纯音频`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "video/x-matroska").setBody("x"))
        val probe = HttpStreamProbe(OkHttpClient())

        assertFalse(probe.isAudioOnly(server.url("/Videos/1/stream").toString()))
    }

    @Test fun `HTTP 非 2xx 判定为不是纯音频`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val probe = HttpStreamProbe(OkHttpClient())

        assertFalse(probe.isAudioOnly(server.url("/Audio/1/universal").toString()))
    }

    @Test fun `网络异常时不向上抛出,判定为不是纯音频`() = runTest {
        server.shutdown()
        val probe = HttpStreamProbe(OkHttpClient())

        assertFalse(probe.isAudioOnly(server.url("/Audio/1/universal").toString()))
    }
}
