package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.MediaSourceDto
import dev.insua.jellycast.network.dto.MediaStreamDto
import dev.insua.jellycast.network.dto.PlaybackInfoResponseDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TEST_ITEM_ID = "ep1"
private const val TEST_USER_ID = "u1"
private const val TEST_MEDIA_SOURCE_ID = "ms1"
private const val TEST_TOKEN = "tok-secret"
private const val TEST_BASE_URL = "https://nas.example.com:8096"

class PlaybackSourceResolverTest {

    /** 探测器:按 URL 关键字决定是否"纯音频" */
    private fun probe(audioOnlyUrlFragments: Set<String>) = object : StreamProbe {
        override suspend fun isAudioOnly(url: String) = audioOnlyUrlFragments.any { url.contains(it) }
    }

    /** 含 1 条音频轨、1 条文本字幕轨、1 条 PGS 位图字幕轨的响应 */
    private fun fakeApi(mediaSources: List<MediaSourceDto> = listOf(defaultMediaSource())): JellyfinApi {
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns
            PlaybackInfoResponseDto(mediaSources = mediaSources, playSessionId = "session-1")
        return api
    }

    private fun defaultMediaSource() = MediaSourceDto(
        id = TEST_MEDIA_SOURCE_ID,
        mediaStreams = listOf(
            MediaStreamDto(type = "Audio", index = 1, codec = "aac", language = "jpn", displayTitle = "日语 AAC"),
            MediaStreamDto(
                type = "Subtitle", index = 2, codec = "ass", language = "chi",
                displayTitle = "简体中文", isTextSubtitle = true,
            ),
            MediaStreamDto(
                type = "Subtitle", index = 3, codec = "pgssub", language = "eng",
                displayTitle = "English (PGS)", isTextSubtitle = false,
            ),
        ),
    )

    private fun newResolver(
        streamProbe: StreamProbe,
        api: JellyfinApi = fakeApi(),
        audioBitRateBps: Int = 128_000,
    ) = PlaybackSourceResolver(
        api = api,
        streamProbe = streamProbe,
        baseUrlProvider = { TEST_BASE_URL },
        tokenProvider = { TEST_TOKEN },
        audioBitRateBps = audioBitRateBps,
    )

    @Test fun `L1 可用时选择服务端纯音频`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/$TEST_ITEM_ID/universal")))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY, src.level)
        assertTrue(src.streamUrl.startsWith("$TEST_BASE_URL/Audio/$TEST_ITEM_ID/universal"))
    }

    @Test fun `L1 的 URL 携带 spike 验证过的参数`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/$TEST_ITEM_ID/universal")), audioBitRateBps = 64_000)
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
        val url = src.streamUrl
        assertTrue(url.contains("api_key=$TEST_TOKEN"), url)
        assertTrue(url.contains("mediaSourceId=$TEST_MEDIA_SOURCE_ID"), url)
        assertTrue(url.contains("audioCodec=aac"), url)
        assertTrue(url.contains("audioBitRate=64000"), url)
        assertTrue(url.contains("maxAudioChannels=2"), url)
    }

    @Test fun `L1 不可用时降级到 L3 客户端禁用视频轨`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, src.level)
        assertTrue(src.streamUrl.isNotBlank())   // L3 必须永远给出可播 URL
        assertTrue(src.streamUrl.startsWith("$TEST_BASE_URL/Videos/$TEST_ITEM_ID/stream"))
        assertTrue(src.streamUrl.contains("static=true"))
    }

    @Test fun `StreamProbe 抛异常时静默降级到 L3 不向上抛错`() = runTest {
        val throwingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean = error("网络炸了")
        }
        val resolver = newResolver(throwingProbe)
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, src.level)
        assertTrue(src.streamUrl.isNotBlank())
    }

    @Test fun `位图字幕被过滤掉不出现在可选字幕中`() = runTest {
        val src = newResolver(probe(emptySet())).resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertTrue(src.textSubtitles.isNotEmpty())
        assertTrue(src.textSubtitles.all { it.isTextBased })
        assertFalse(src.textSubtitles.any { it.displayName.contains("PGS") })
    }

    @Test fun `音轨列表来自 MediaStreams 的 Audio 类型`() = runTest {
        val src = newResolver(probe(setOf("/Audio/$TEST_ITEM_ID/universal"))).resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals(1, src.audioTracks.size)
        assertEquals(1, src.audioTracks.first().index)
    }

    @Test fun `startPositionMs 非零时换算为 ticks 拼进 URL`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/$TEST_ITEM_ID/universal")))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 5_000L)
        assertTrue(src.streamUrl.contains("startTimeTicks=50000000"), src.streamUrl)
    }

    @Test fun `startPositionMs 为 0 时 URL 中不出现 startTimeTicks`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/$TEST_ITEM_ID/universal")))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 0L)
        assertFalse(src.streamUrl.contains("startTimeTicks"), src.streamUrl)
    }

    @Test fun `L3 也携带换算后的 startTimeTicks`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)
        assertTrue(src.streamUrl.contains("startTimeTicks=6000000000"), src.streamUrl)
    }

    @Test fun `PlaybackInfo 返回空 mediaSources 时抛出异常而不是产出空 URL`() = runTest {
        val resolver = newResolver(probe(emptySet()), api = fakeApi(mediaSources = emptyList()))
        try {
            resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
            throw AssertionError("应当抛出异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains(TEST_ITEM_ID) == true)
        }
    }

    @Test fun `playSessionId 透传自 PlaybackInfo 响应`() = runTest {
        val src = newResolver(probe(emptySet())).resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals("session-1", src.playSessionId)
    }
}
