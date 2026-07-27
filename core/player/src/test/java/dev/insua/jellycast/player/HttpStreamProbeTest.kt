package dev.insua.jellycast.player

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

        assertTrue(probe.isAudioOnly(server.url("/Audio/1/universal").toString()) ?: false)
    }

    @Test fun `Content-Type 是 video 前缀时判定为不是纯音频`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "video/x-matroska").setBody("x"))
        val probe = HttpStreamProbe(OkHttpClient())

        assertFalse(probe.isAudioOnly(server.url("/Videos/1/stream").toString()) ?: true)
    }

    /**
     * 非 2xx 是**没问出结论**,不是"不是纯音频"。区分二者是必须的:调用方按媒体源缓存判定,
     * 只有有结论的判定才可以被记住(见 [StreamProbe] 与 `PlaybackSourceResolver.isAudioOnly`)。
     * J4125 上并发转码打架回个 500 是常态,记成 false 就是一次抽风钉死整个会话在 L3。
     */
    @Test fun `HTTP 非 2xx 是没有结论,不是判定为不是纯音频`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val probe = HttpStreamProbe(OkHttpClient())

        assertNull(probe.isAudioOnly(server.url("/Audio/1/universal").toString()))
    }

    @Test fun `网络异常时不向上抛出,同样是没有结论`() = runTest {
        server.shutdown()
        val probe = HttpStreamProbe(OkHttpClient())

        assertNull(probe.isAudioOnly(server.url("/Audio/1/universal").toString()))
    }
}
