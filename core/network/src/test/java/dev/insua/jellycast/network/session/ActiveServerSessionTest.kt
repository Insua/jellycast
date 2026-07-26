package dev.insua.jellycast.network.session

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointProbe
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.dto.AuthResultDto
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.dto.PlaybackInfoResponseDto
import dev.insua.jellycast.network.dto.PlaybackProgressInfoDto
import dev.insua.jellycast.network.dto.PlaybackStartInfoDto
import dev.insua.jellycast.network.dto.PlaybackStopInfoDto
import dev.insua.jellycast.network.dto.PublicSystemInfoDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ActiveServerSession] 是"运行时可变 baseUrl"这个设计的核心:纯 Kotlin,不依赖真实网络/
 * Android Context——用假的 [EndpointProbe] + 假的 [SessionApiFactory] 就能离线单测选路缓存、
 * 服务器切换、[ActiveServerSession.invalidate] 的行为。
 */
class ActiveServerSessionTest {

    private val lan = Endpoint(url = "http://192.168.1.10:8096", label = "局域网", priority = 0)
    private val tailscale = Endpoint(url = "http://100.1.1.1:8096", label = "Tailscale", priority = 1)

    private fun server(id: String, endpoints: List<Endpoint>) = Server(
        id = id, name = "srv-$id", endpoints = endpoints, userId = "user-$id", accessToken = "token-$id",
    )

    /** 只让 [reachable] 里的地址探测成功。 */
    private fun probe(reachable: Set<Endpoint>) = object : EndpointProbe {
        override suspend fun probe(endpoint: Endpoint): EndpointHealth =
            EndpointHealth(endpoint, reachable.contains(endpoint), if (reachable.contains(endpoint)) 10L else null)
    }

    /** 记录每次被要求创建 api 的 endpoint。 */
    private class RecordingApiFactory : SessionApiFactory {
        val created = mutableListOf<Endpoint>()
        override fun create(endpoint: Endpoint, tokenProvider: () -> String?): JellyfinApi {
            created += endpoint
            return NoopJellyfinApi
        }
    }

    @Test fun `第一次解析会跑一次并发选路并缓存结果`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan, tailscale)))
        val apiFactory = RecordingApiFactory()
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(setOf(lan))), apiFactory)

        val userId = session.userId()
        val baseUrl = session.baseUrl()

        assertEquals("user-1", userId)
        assertEquals(lan.url, baseUrl)
        assertEquals(1, apiFactory.created.size)
    }

    @Test fun `同一台服务器重复解析复用缓存不重新选路`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan, tailscale)))
        val apiFactory = RecordingApiFactory()
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(setOf(lan))), apiFactory)

        session.api()
        session.api()
        session.userId()

        assertEquals(1, apiFactory.created.size, "同一台服务器不应该反复触发选路/重新建 api")
    }

    @Test fun `切换到不同服务器会重新选路`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan)))
        val apiFactory = RecordingApiFactory()
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(setOf(lan, tailscale))), apiFactory)

        session.userId()
        activeServer.value = server("2", listOf(tailscale))
        val userId2 = session.userId()

        assertEquals("user-2", userId2)
        assertEquals(2, apiFactory.created.size)
    }

    @Test fun `invalidate 后下一次解析重新选路,即使服务器没变`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan, tailscale)))
        val apiFactory = RecordingApiFactory()
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(setOf(lan))), apiFactory)

        session.userId()
        session.invalidate()
        session.userId()

        assertEquals(2, apiFactory.created.size, "invalidate 之后应该重新触发一次选路")
    }

    @Test fun `没有已激活服务器时解析失败`() = runTest {
        val activeServer = MutableStateFlow<Server?>(null)
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(emptySet())), RecordingApiFactory())

        val failure = runCatching { session.userId() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test fun `所有 endpoint 都不可达时解析失败`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan, tailscale)))
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(emptySet())), RecordingApiFactory())

        val failure = runCatching { session.baseUrl() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test fun `解析成功前同步缓存读取返回 null,成功后返回新鲜值`() = runTest {
        val activeServer = MutableStateFlow(server("1", listOf(lan)))
        val session = ActiveServerSession(activeServer, EndpointSelector(probe(setOf(lan))), RecordingApiFactory())

        assertNull(session.cachedBaseUrlOrNull())
        assertNull(session.cachedTokenOrNull())

        session.userId()

        assertEquals(lan.url, session.cachedBaseUrlOrNull())
        assertEquals("token-1", session.cachedTokenOrNull())
    }
}

/** 测试里只需要一个能被构造出来、永远不会被真的调用的 [JellyfinApi]。 */
private object NoopJellyfinApi : JellyfinApi {
    override suspend fun publicInfo(): PublicSystemInfoDto = error("not used in test")
    override suspend fun authenticate(body: AuthRequestDto): AuthResultDto = error("not used in test")
    override suspend fun items(
        userId: String,
        types: String,
        recursive: Boolean,
        sortBy: String,
        startIndex: Int?,
        limit: Int?,
        parentId: String?,
        searchTerm: String?,
    ): ItemsResponseDto = error("not used in test")
    override suspend fun resume(userId: String): ItemsResponseDto = error("not used in test")
    override suspend fun nextUp(userId: String, limit: Int): ItemsResponseDto = error("not used in test")
    override suspend fun seasons(seriesId: String, userId: String): ItemsResponseDto = error("not used in test")
    override suspend fun episodes(seriesId: String, seasonId: String, userId: String): ItemsResponseDto =
        error("not used in test")
    override suspend fun itemDetail(itemId: String, userId: String): BaseItemDto = error("not used in test")
    override suspend fun playbackInfo(
        itemId: String,
        userId: String,
        body: Map<String, String>,
    ): PlaybackInfoResponseDto = error("not used in test")
    override suspend fun reportStart(body: PlaybackStartInfoDto) = error("not used in test")
    override suspend fun reportProgress(body: PlaybackProgressInfoDto) = error("not used in test")
    override suspend fun reportStop(body: PlaybackStopInfoDto) = error("not used in test")
}
