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

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
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

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
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

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
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

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertEquals(listOf(ITEM_ID), calls, "缓存查询失败必须原样委派,不能让用户看到错误")
        assertEquals(expected, source)
    }

    @Test fun `命中缓存更新最后访问时间`() = runTest {
        val delegate = fakeDelegate(mutableListOf())
        val cacheStore = cacheStoreReturning(LOCAL_PATH)
        val api = mockk<JellyfinApi>()
        coEvery { api.playbackInfo(any(), any(), any()) } returns metadataResponse()

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
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

        val provider = CacheAwareSourceProvider(delegate, cacheStore, api, SERVER_ID)
        val source = provider.resolve(ITEM_ID, USER_ID, 0L)

        assertTrue(source.isLocalFile, "断网也必须能播——这正是缓存存在的意义")
        assertEquals("file://$LOCAL_PATH", source.streamUrl)
        assertTrue(source.audioTracks.isEmpty(), "元数据查询失败,音轨信息缺失是可接受的降级")
        assertTrue(source.textSubtitles.isEmpty(), "元数据查询失败,字幕信息缺失是可接受的降级——不是不能播")
        assertTrue(calls.isEmpty(), "元数据走轻量 api.playbackInfo,不该退回到会起转码探测的底层解析器")
    }
}
