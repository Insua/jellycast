package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.MediaSourceDto
import dev.insua.jellycast.network.dto.MediaStreamDto
import dev.insua.jellycast.network.dto.PlaybackInfoResponseDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertTrue(src.streamUrl.startsWith("$TEST_BASE_URL/Videos/$TEST_ITEM_ID/stream."))
        assertTrue(src.streamUrl.contains("mediaSourceId=$TEST_MEDIA_SOURCE_ID"), src.streamUrl)
    }

    @Test fun `StreamProbe 抛异常时静默降级到 L3 不向上抛错`() = runTest {
        val throwingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean? = error("网络炸了")
        }
        val resolver = newResolver(throwingProbe)
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, src.level)
        assertTrue(src.streamUrl.isNotBlank())
    }

    /**
     * `runCatching` 连 [CancellationException] 一起吞 —— 而 [HttpStreamProbe] 特意把它重新抛出来了,
     * 到这一层又被吞回去,那份小心白费。
     *
     * 具体后果:`PlaybackService.onDestroy` 取消 `serviceScope` 时,一次正在飞的 seek 的探测被取消,
     * 这里把它当成"不是纯音频"、若无其事地继续走完 L3 分支,回一个播放源给一个正在被拆掉的播放器。
     * 协程取消**必须**一路传播到顶,这是项目既定不变量。
     *
     * 注意这和"探测失败静默降级到 L3"(上一条用例)是两件不同的事:取消不是失败。
     */
    @Test fun `探测被取消时 CancellationException 向上传播,不被当成不是纯音频`() = runTest {
        val cancellingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean? = throw CancellationException("会话被取消")
        }
        val resolver = newResolver(cancellingProbe)

        try {
            resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
            throw AssertionError("CancellationException 必须向上传播,不得被 runCatching 吞掉")
        } catch (expected: CancellationException) {
            // 期望路径
        }
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

    /**
     * 缺陷 1(设计文档 §3.3):`PlayerViewModel` 要在默认选轨时降级弹幕,前提是它能拿到
     * `IsExternal` 信号 —— 这条断言确保 [MediaStreamDto.isExternal] 被原样传进
     * [dev.insua.jellycast.model.SubtitleTrackRef.isExternal],而不是在这一层丢失。
     */
    @Test fun `字幕轨的 isExternal 从 MediaStreamDto 原样传递`() = runTest {
        val mediaSource = MediaSourceDto(
            id = TEST_MEDIA_SOURCE_ID,
            mediaStreams = listOf(
                MediaStreamDto(
                    type = "Subtitle", index = 0, codec = "ass", language = "chi",
                    displayTitle = "弹幕[BiliBili]", isTextSubtitle = true, isExternal = true,
                ),
                MediaStreamDto(
                    type = "Subtitle", index = 4, codec = "ass", language = "chi",
                    displayTitle = "简体中文", isTextSubtitle = true, isExternal = true,
                ),
                MediaStreamDto(
                    type = "Subtitle", index = 5, codec = "ass", language = "chi",
                    displayTitle = "内嵌字幕", isTextSubtitle = true, isExternal = false,
                ),
            ),
        )
        val src = newResolver(probe(emptySet()), api = fakeApi(listOf(mediaSource))).resolve(TEST_ITEM_ID, TEST_USER_ID)

        val danmaku = src.textSubtitles.first { it.index == 0 }
        val realExternalSubtitle = src.textSubtitles.first { it.index == 4 }
        val embedded = src.textSubtitles.first { it.index == 5 }

        assertTrue(danmaku.isExternal)
        assertTrue(danmaku.isLikelyDanmaku)
        assertTrue(realExternalSubtitle.isExternal)
        assertFalse(realExternalSubtitle.isLikelyDanmaku)
        assertFalse(embedded.isExternal)
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

    /**
     * 🔴 **L3 上 seek 是空操作** —— 本项目最不诚实的一个缺陷,因为它比报错更糟:进度条跳到目标、
     * 继续往前走,而耳机里在从头重播,用户完全看不出来。
     *
     * 根因不在客户端:`static=true` 让服务端走"直出原始文件"那条路,`startTimeTicks` 被**静默忽略**。
     *
     * ## 对真实服务器的实测(2026-07-28,群晖 DS920+ / J4125)
     *
     * 同一个条目(时长 1 496 037 ms),`startTimeTicks` 一律取"距结尾 30s"= 14 660 370 000,
     * 每次请求都带**唯一的 `deviceId`**(否则 Jellyfin 会把上一次转码任务的输出原样递回来,
     * 测出来的是缓存不是行为):
     *
     * | 请求 | 响应头 | `ffprobe` 出来的时长 | 结论 |
     * |---|---|---|---|
     * | `…/stream?…&static=true&startTimeTicks=…` | 200,`Content-Length: 1002873182`,`Accept-Ranges: bytes` | **1496.038 s(整集)** | 偏移被忽略 |
     * | `…/stream?…&startTimeTicks=…`(无 static) | 200,`Accept-Ranges: none`,`Transfer-Encoding: chunked` | **32.017 s** | 偏移生效 |
     * | `…/stream?…&startTimeTicks=`(距结尾 300s) | 同上 | **301.494 s** | 偏移生效,且按请求值线性对应 |
     *
     * 所以走**非 static** 的那条路:seek 是真的生效,而不是"看起来生效"。
     *
     * 代价已知并接受:非 static 由服务端 remux/转码,原容器里的**多条音轨只剩服务端选中的那一条**
     * (实测一个 3 条 DTS 音轨的条目,非 static 输出只有 1 条 AAC)。用 seek 的正确性换 L3 上的
     * 多音轨切换是划算的 —— L3 本来就是罕见兜底,而 seek 是每次收听都要用的基本功能。
     */
    @Test fun `L3 不带 static=true —— 带上服务端就会静默忽略 startTimeTicks`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, src.level)
        assertFalse(
            src.streamUrl.contains("static=true"),
            "L3 URL 仍然带着 static=true —— 服务端会因此忽略 startTimeTicks,seek 变成空操作:${src.streamUrl}",
        )
        assertTrue(src.streamUrl.contains("startTimeTicks=6000000000"), src.streamUrl)
    }

    /** 起始位置为 0 时同样不带 static:一条流只有一种形态,免得 seek 前后在两种语义之间来回切。 */
    /** 起始位置为 0 时形态完全一样 —— 一条 L3 流只有一种形态,免得 seek 前后语义来回切。 */
    @Test fun `L3 从头播放时也不带 static=true`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 0L)
        assertFalse(src.streamUrl.contains("static"), src.streamUrl)
    }

    /**
     * 🔴 去掉 `static=true` 还是不够 —— 端到端里 seek 依旧从头重播,而 URL 明明带着正确的
     * `startTimeTicks`。根因在服务端的转码产物复用上:
     *
     * **Jellyfin 的转码输出文件路径不随 `startTimeTicks` 变化。** 同一个设备/会话第二次请求同一条流时,
     * 服务端直接把**上一次那个任务已经落盘的输出**原样递回来,于是偏移形同虚设。
     *
     * ## 实测(2026-07-28,同一条目,先请求 offset 0 再请求"距结尾 30s")
     *
     * | 场景 | 第二次请求的 `ffprobe` 时长 | 结论 |
     * |---|---|---|
     * | 不带 `deviceId`/`playSessionId`(修复前的 App) | **1826.581 s(整集)** | 偏移无效 |
     * | 固定 `deviceId` + 固定 `playSessionId` | **1826.581 s** | 偏移无效 |
     * | 先 `DELETE /Videos/ActiveEncodings` 再请求 | **1826.581 s** | 删任务不删产物,仍然无效 |
     * | **每次请求带各自的 `playSessionId`** | **33.280 s** | ✅ 偏移生效 |
     *
     * 而 `POST /Items/{id}/PlaybackInfo` **每次调用都回一个新的 32 位 `PlaySessionId`**(实测三次全不同),
     * 而 `resolve()` 每次都会调它 —— 也就是说这个"每次唯一"的值本来就在手上,只是没拼进 URL。
     *
     * 这同时也是正确的语义:服务端能把转码任务和播放会话对应起来。
     *
     * (实测 L1 `/Audio/{id}/universal` **不受此影响** —— 带不带 `playSessionId`,"距结尾 30s"
     * 都稳定给出 29.27 s。所以只改 L3,不动那条已经验证过的路径。)
     */
    @Test fun `L3 URL 带上本次 resolve 的 playSessionId —— 否则服务端复用上一次的转码产物`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)
        assertEquals("session-1", src.playSessionId)
        assertTrue(
            src.streamUrl.contains("playSessionId=session-1"),
            "L3 URL 少了 playSessionId —— 服务端会把上一次转码任务的产物原样递回来,startTimeTicks 形同虚设:" +
                src.streamUrl,
        )
    }

    /**
     * 🔴 去掉 `static=true` 只解决了一半。服务端随后按**源容器**决定输出容器,MP4/MOV 源 remux 出来的
     * MP4 把 `moov` atom 写在文件末尾,而响应是 `chunked` + `Accept-Ranges: none` —— 流式播放器在整条流
     * 下完之前拿不到 `moov`。端到端里 ExoPlayer 直接 `Source error`,一秒都没播出来
     * (实测:下了 441 MB 后 `ffprobe` 仍然 `moov atom not found`)。
     *
     * 所以必须显式要 Matroska(头部自带时长,天生可流式)。走**路径后缀**而不是 `container=` 查询参数:
     * 后者输出的 mkv 头里没有时长。详见 [PlaybackSourceResolver] 里 `buildVideoStreamUrl` 的 KDoc。
     */
    @Test fun `L3 强制 mkv 容器 —— MP4 源 remux 的 moov 在末尾,chunked 流根本没法播`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)
        assertTrue(
            src.streamUrl.startsWith("$TEST_BASE_URL/Videos/$TEST_ITEM_ID/stream.mkv?"),
            "L3 必须显式要 mkv 容器:${src.streamUrl}",
        )
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
            override suspend fun isAudioOnly(url: String): Boolean? {
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
            override suspend fun isAudioOnly(url: String): Boolean? {
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

    /**
     * ## 缓存只许记**有结论**的探测,绝不许记一次服务端抽风
     *
     * 端到端第 2 轮暴露的真实回归:整整一轮八个用例,那台群晖上的同一个条目**全程走 L3**
     * (`level=CLIENT_VIDEO_DISABLED`,起播 34s、连点快进 40s,都是拉全量 1080p 流的耗时特征),
     * 而第 1 轮和第 3 轮同一个条目全程 `200 audio/aac` 走 L1。
     *
     * 代码里能把一个进程**整体**钉死在 L3 的路径只有一条:某一次探测返回了 false 并被记进缓存。
     * 而 [HttpStreamProbe] 对 **HTTP 非 2xx 也返回 false** —— J4125 上并发转码打架时
     * `/Audio/{item}/universal` 回一个 500 完全正常。于是一次瞬时抽风 = 整个会话 ~42 倍带宽,
     * 而且用户毫无感知。**这正是本产品定义的最坏静默降级**,比不缓存严重得多。
     *
     * 判据:200 + 非 audio 的 Content-Type 是**有结论**的("这个源就是混流");
     * 非 2xx / 网络异常是**没有结论**的("这次没问过")。只有前者可以记住。
     *
     * 这条用例用 MockWebServer 按服务端行为表述,不依赖探测器内部返回值的形状。
     */
    @Test fun `服务端一次 500 之后必须重新探测,不得把整个会话钉死在 L3`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(500))                       // 第一次:服务端抽风
            server.enqueue(
                MockResponse().setResponseCode(200).addHeader("Content-Type", "audio/aac").setBody("x"),
            )
            val resolver = PlaybackSourceResolver(
                api = fakeApi(),
                streamProbe = HttpStreamProbe(OkHttpClient()),
                baseUrlProvider = { server.url("/").toString().trimEnd('/') },
                tokenProvider = { TEST_TOKEN },
            )

            val first = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)
            val second = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID, startPositionMs = 600_000L)

            assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, first.level, "这次没问出结论,兜底 L3 是对的")
            assertEquals(
                AudioDeliveryLevel.SERVER_AUDIO_ONLY,
                second.level,
                "一次 500 不是结论,后续必须重新探测;记住它等于把整个会话钉死在 ~42 倍带宽的 L3",
            )
        } finally {
            server.shutdown()
        }
    }

    /** 缓存的键是媒体源,不是"探测过一次就对所有条目生效"。换一个条目必须重新探测。 */
    @Test fun `换一个条目必须重新探测`() = runTest {
        val probedUrls = mutableListOf<String>()
        val countingProbe = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean? {
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

    /**
     * L3 兜底必须把视频压到最低(设计文档 §3.3/§5.3)。
     *
     * 不加这些参数时服务端对视频做**流拷贝**:4K HEVC 片源实测 54.5 Mbps,客户端即使禁用了
     * 视频轨,混流的字节也必须整条下载才能取出音频 —— 家宽上行不可能,表现就是"一直卡死"。
     * 加上限后实测 0.51 Mbps、2.2 倍实时。
     *
     * 音频参数是顺带补的:L3 原来一个音频参数都不带,服务端给什么是什么。
     */
    @Test
    fun `L3兜底URL带上视频降码率与音频转码参数`() = runTest {
        val resolver = newResolver(probe(emptySet()))   // 探测说"不是纯音频" → 走 L3

        val source = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)

        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, source.level)
        listOf(
            "videoCodec=h264",
            "videoBitRate=100000",
            "maxWidth=320",
            "maxFramerate=15",
            "audioCodec=aac",
            "audioBitRate=128000",
            "maxAudioChannels=2",
        ).forEach { param ->
            assertTrue(source.streamUrl.contains(param), "L3 URL 缺少 $param:${source.streamUrl}")
        }
    }

    /** 这次改动不许污染 L1:L1 是纯音频接口,加视频参数没有意义还可能改变服务端行为。 */
    @Test
    fun `L1的URL不含任何视频参数`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/")))

        val source = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)

        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY, source.level)
        listOf("videoCodec", "videoBitRate", "maxWidth", "maxFramerate").forEach { param ->
            assertFalse(source.streamUrl.contains(param), "L1 URL 不该出现 $param:${source.streamUrl}")
        }
    }

    /**
     * `L3兜底URL带上视频降码率与音频转码参数` 只用了默认 resolver,而默认的 `audioBitRateBps`
     * 恰好等于 `DEFAULT_AUDIO_BIT_RATE_BPS`(128 000)—— 所以就算 `buildVideoStreamUrl` 把
     * `audioBitRate` 写死成 `128000` 而不是真正拼 `audioBitRateBps` 参数,那条测试也测不出来。
     *
     * 镜像 [L1 的 URL 携带 spike 验证过的参数](第 76 行)的写法:用非默认 `audioBitRateBps`
     * 构造 resolver,断言这个值真的流进了 L3 URL,而不是默认值。
     */
    @Test
    fun `L3的URL携带非默认audioBitRateBps`() = runTest {
        val resolver = newResolver(probe(emptySet()), audioBitRateBps = 64_000)   // 走 L3

        val source = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)

        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, source.level)
        assertTrue(source.streamUrl.contains("audioBitRate=64000"), source.streamUrl)
        assertFalse(source.streamUrl.contains("audioBitRate=128000"), source.streamUrl)
    }
}
