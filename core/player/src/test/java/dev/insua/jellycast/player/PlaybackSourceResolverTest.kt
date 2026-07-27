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

    // ---- 稳定性根因 #3:L1 探测是"这个媒体源吐不吐纯音频",不是"从第几秒开始播" ----

    /**
     * seek 实现成"带新的 startTimeTicks 重新 resolve"(转码流 `Accept-Ranges: none`,别无选择),
     * 而每次 resolve 都会让 [HttpStreamProbe] 对 L1 候选 URL 发一次**完整 GET**。
     *
     * 后果:**用户每按一次快进,群晖 J4125 上就多起一个转码任务,而且随即被丢弃**
     * (探测只读响应头就 close)。连点几下快进就是几个并发的孤儿转码在 CPU 上打架 ——
     * 正在听的那一条也跟着卡。这正是用户报的"seek/快进卡死、播放中断"。
     *
     * 而这次探测的结论**和起始位置毫无关系**:「`/Audio/{item}/universal` 在这个 mediaSource 上
     * 吐不吐纯音频」是媒体源自身的属性。所以按 mediaSourceId 记一次就够。
     */
    @Test fun `同一个媒体源反复 resolve 只探测一次,seek 不再起新的孤儿转码`() = runTest {
        val probedUrls = mutableListOf<String>()
        val countingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean {
                probedUrls += url
                return true
            }
        }
        val resolver = newResolver(countingProbe)

        resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 0L)          // 开始播放
        resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)    // 快进一次
        resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 630_000L)    // 再快进一次
        resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 660_000L)    // 再快进一次

        assertEquals(
            1,
            probedUrls.size,
            "一次播放 + 三次 seek 只该探测一次;实际探测了 ${probedUrls.size} 次," +
                "等于在服务端起了 ${probedUrls.size} 个转码任务、丢弃 ${probedUrls.size - 1} 个",
        )
    }

    /** 判定为"不是纯音频"(要走 L3)同样要记住 —— 否则每次 seek 照样白跑一次探测 GET。 */
    @Test fun `L3 判定同样被记住,不会每次 seek 重新探测`() = runTest {
        var probeCount = 0
        val countingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean {
                probeCount++
                return false
            }
        }
        val resolver = newResolver(countingProbe)

        val first = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 0L)
        val second = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)

        assertEquals(1, probeCount)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, first.level)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, second.level, "缓存不得改变降级判定")
        assertTrue(second.streamUrl.contains("startTimeTicks=6000000000"), second.streamUrl)
    }

    /** 缓存的键是媒体源,不是"探测过一次就对所有条目生效"。换一个条目必须重新探测。 */
    @Test fun `换一个条目必须重新探测`() = runTest {
        val probedUrls = mutableListOf<String>()
        val countingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean {
                probedUrls += url
                return true
            }
        }
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo("ep1", any(), any()) } returns
            PlaybackInfoResponseDto(mediaSources = listOf(defaultMediaSource()), playSessionId = "s1")
        coEvery { api.playbackInfo("ep2", any(), any()) } returns
            PlaybackInfoResponseDto(
                mediaSources = listOf(defaultMediaSource().copy(id = "ms2")),
                playSessionId = "s2",
            )
        val resolver = newResolver(countingProbe, api = api)

        resolver.resolve("ep1", TEST_USER_ID)
        resolver.resolve("ep2", TEST_USER_ID)

        assertEquals(2, probedUrls.size)
    }

    @Test fun `playSessionId 透传自 PlaybackInfo 响应`() = runTest {
        val src = newResolver(probe(emptySet())).resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals("session-1", src.playSessionId)
    }
}
