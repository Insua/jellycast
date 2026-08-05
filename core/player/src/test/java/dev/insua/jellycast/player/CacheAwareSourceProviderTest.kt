package dev.insua.jellycast.player

import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.MediaSourceDto
import dev.insua.jellycast.network.dto.MediaStreamDto
import dev.insua.jellycast.network.dto.PlaybackInfoResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SERVER_ID = "server-1"
private const val ITEM_ID = "ep1"
private const val USER_ID = "u1"
private const val LOCAL_PATH = "/data/audio-cache/$SERVER_ID/$ITEM_ID"

/**
 * 覆盖 Task 5 的核心行为:命中缓存改播本地文件、未命中原样委派、缓存查询失败静默降级、
 * 命中时更新最后访问时间,以及"命中缓存但元数据查询失败(断网)仍要能播,只是没字幕"这条
 * 铁律 4 的具体化。
 */
class CacheAwareSourceProviderTest {

    private fun delegateSource() = PlaybackSource(
        itemId = ITEM_ID,
        mediaSourceId = "ms-remote",
        streamUrl = "https://nas.example.com/Audio/$ITEM_ID/universal?mediaSourceId=ms-remote",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = "session-remote",
        audioTracks = listOf(AudioTrack(1, "jpn", "日语")),
        textSubtitles = listOf(SubtitleTrackRef(2, "chi", "简体中文", isTextBased = true)),
    )

    /** 记录每一次被调用的 itemId,断言用——空列表就是"底层解析器一次都没被调用"的证据。 */
    private fun fakeDelegate(calls: MutableList<String>, result: PlaybackSource = delegateSource()) =
        PlaybackSourceProvider { itemId, _, _ ->
            calls += itemId
            result
        }

    private fun metadataResponse() = PlaybackInfoResponseDto(
        mediaSources = listOf(
            MediaSourceDto(
                id = "ms-cached",
                mediaStreams = listOf(
                    MediaStreamDto(type = "Audio", index = 1, language = "jpn", displayTitle = "日语 AAC"),
                    MediaStreamDto(
                        type = "Subtitle", index = 2, language = "chi",
                        displayTitle = "简体中文", isTextSubtitle = true,
                    ),
                ),
            ),
        ),
        playSessionId = "session-cached",
    )

    private fun cacheStoreReturning(path: String?) = mockk<AudioCacheStore>().also {
        coEvery { it.pathIfComplete(SERVER_ID, ITEM_ID) } returns path
        coEvery { it.touch(SERVER_ID, ITEM_ID) } returns Unit
    }

    @Test fun `命中缓存返回本地源且底层解析器一次都不被调用`() = runTest {
        val calls = mutableListOf<String>()
        val delegate = fakeDelegate(calls)
        val cacheStore = cacheStoreReturning(LOCAL_PATH)
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns metadataResponse()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertTrue(source.isLocalFile, "命中缓存必须标记为本地文件")
        assertEquals("file://$LOCAL_PATH", source.streamUrl, "streamUrl 必须指向本地缓存文件")
        assertTrue(calls.isEmpty(), "命中缓存时底层解析器一次都不该被调用——这是缓存生效的实质证据")
    }

    @Test fun `命中缓存仍通过轻量 PlaybackInfo 查询拿到最新的音轨与字幕元数据`() = runTest {
        val delegate = fakeDelegate(mutableListOf())
        val cacheStore = cacheStoreReturning(LOCAL_PATH)
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns metadataResponse()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertEquals("ms-cached", source.mediaSourceId)
        assertEquals("session-cached", source.playSessionId)
        assertEquals(listOf(AudioTrack(1, "jpn", "日语 AAC")), source.audioTracks)
        assertEquals(listOf(SubtitleTrackRef(2, "chi", "简体中文", isTextBased = true)), source.textSubtitles)
    }

    @Test fun `未命中缓存原样委派逐字段一致`() = runTest {
        val expected = delegateSource()
        val calls = mutableListOf<String>()
        val delegate = fakeDelegate(calls, expected)
        val cacheStore = cacheStoreReturning(null)
        val api = mockk<JellyfinApi>()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        val source = provider.resolve(ITEM_ID, USER_ID, 5_000L)

        assertEquals(listOf(ITEM_ID), calls, "未命中必须委派给底层解析器")
        assertEquals(expected.itemId, source.itemId)
        assertEquals(expected.mediaSourceId, source.mediaSourceId)
        assertEquals(expected.streamUrl, source.streamUrl)
        assertEquals(expected.level, source.level)
        assertEquals(expected.isHls, source.isHls)
        assertEquals(expected.playSessionId, source.playSessionId)
        assertEquals(expected.audioTracks, source.audioTracks)
        assertEquals(expected.textSubtitles, source.textSubtitles)
        assertEquals(expected.isLocalFile, source.isLocalFile)
    }

    @Test fun `缓存查询自身抛异常时静默降级为委派不向上抛`() = runTest {
        val expected = delegateSource()
        val calls = mutableListOf<String>()
        val delegate = fakeDelegate(calls, expected)
        val cacheStore = mockk<AudioCacheStore>()
        coEvery { cacheStore.pathIfComplete(SERVER_ID, ITEM_ID) } throws RuntimeException("index 数据库炸了")
        val api = mockk<JellyfinApi>()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertEquals(listOf(ITEM_ID), calls, "缓存查询失败必须原样委派,不能让用户看到错误")
        assertEquals(expected, source)
    }

    @Test fun `命中缓存更新最后访问时间`() = runTest {
        val delegate = fakeDelegate(mutableListOf())
        val cacheStore = cacheStoreReturning(LOCAL_PATH)
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns metadataResponse()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        provider.resolve(ITEM_ID, USER_ID, 0L)

        coVerify(exactly = 1) { cacheStore.touch(SERVER_ID, ITEM_ID) }
    }

    /**
     * 设计决定的落地用例(见 CacheAwareSourceProvider 类 KDoc):命中缓存之后仍会发一次轻量的
     * `PlaybackInfo` 查询去拿音轨/字幕元数据——断网时这次查询失败,必须降级为"能播、没有字幕",
     * 绝不能因为查不到元数据就干脆不返回本地源(那样缓存这个功能就白做了)。
     */
    @Test fun `断网时元数据查询失败仍返回本地源只是没有字幕`() = runTest {
        val calls = mutableListOf<String>()
        val delegate = fakeDelegate(calls)
        val cacheStore = cacheStoreReturning(LOCAL_PATH)
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } throws IOException("断网了")

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, { SERVER_ID })
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertTrue(source.isLocalFile, "断网也必须能播——这正是缓存存在的意义")
        assertEquals("file://$LOCAL_PATH", source.streamUrl)
        assertTrue(source.audioTracks.isEmpty(), "元数据查询失败,音轨信息缺失是可接受的降级")
        assertTrue(source.textSubtitles.isEmpty(), "元数据查询失败,字幕信息缺失是可接受的降级——不是不能播")
        assertTrue(calls.isEmpty(), "元数据走轻量 api.playbackInfo,不该退回到会起转码探测的底层解析器")
        // 复审 Finding 2:touch 目前写在元数据 try/catch 之前、对所有命中路径无条件执行,但唯一
        // 覆盖 touch 的用例(「命中缓存更新最后访问时间」)走的是元数据查询成功的分支——如果以后
        // 有人把 touch 挪进元数据成功之后的分支,不会有任何用例发现。一集如果只在断网时听过,
        // 没有 touch 就永远刷新不了 lastAccessAt,驱逐策略会把它当"很久没碰过"优先删掉,
        // 比真正很久没听的集更早被清掉。
        coVerify(exactly = 1) { cacheStore.touch(SERVER_ID, ITEM_ID) }
    }

    // ---- 复审 I3(Important):serverId 必须每次都动态查询,不能是装配时刻的一次性快照 ----

    /**
     * `AppSessionViewModel.onServerConnected` 切服务器不重启进程——如果 `serverIdProvider` 只在
     * 构造时被读一次并缓存下来,切换到 server-B 之后这里仍然会拿着 server-A 的 id 去查缓存,
     * 而 server-A 底下根本没有 server-B 条目的缓存记录(`AudioCacheStore` 按 serverId 隔离),
     * 后果是"明明命中了,却因为查错了分区而被判定成未命中"。这条用例通过在**同一个** provider
     * 实例上、两次 resolve 之间切换 `currentServerId`,验证每次 resolve 都重新问一次。
     */
    @Test fun `切换激活服务器后resolve使用新的serverId查询缓存`() = runTest {
        val delegate = fakeDelegate(mutableListOf(), delegateSource())
        var currentServerId = "server-A"
        val cacheStore = mockk<AudioCacheStore>()
        coEvery { cacheStore.pathIfComplete("server-A", ITEM_ID) } returns LOCAL_PATH
        coEvery { cacheStore.pathIfComplete("server-B", ITEM_ID) } returns null
        coEvery { cacheStore.touch(any(), any()) } returns Unit
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns metadataResponse()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api) { currentServerId }

        val underA = provider.resolve(ITEM_ID, USER_ID, 0L)
        assertTrue(underA.isLocalFile, "server-A 下这一条应该命中缓存")

        currentServerId = "server-B" // 用户切换到了另一台服务器,进程没有重启。
        val underB = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertFalse(
            underB.isLocalFile,
            "复审 I3:server-B 下没有这条缓存记录,必须原样委派——如果 serverId 是装配时刻缓存下来的" +
                "旧快照,这里会继续错误地命中 server-A 的记录",
        )
    }

    @Test fun `serverIdProvider查不到激活服务器时原样委派不查缓存`() = runTest {
        val calls = mutableListOf<String>()
        val expected = delegateSource()
        val delegate = fakeDelegate(calls, expected)
        val cacheStore = mockk<AudioCacheStore>()
        val api = mockk<JellyfinApi>()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api) { null }
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertEquals(listOf(ITEM_ID), calls, "拿不到激活服务器 id 时应该原样委派")
        assertEquals(expected, source)
        coVerify(exactly = 0) { cacheStore.pathIfComplete(any(), any()) }
    }
}
