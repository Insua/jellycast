package dev.insua.jellycast.datastore

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerSerializationTest {
    @Test fun `服务器多地址序列化往返一致`() {
        val original = listOf(Server(
            id = "s1", name = "家里 NAS",
            endpoints = listOf(
                Endpoint("http://192.168.1.10:8096", "局域网", 1),
                Endpoint("http://100.64.0.5:8096", "Tailscale", 2),
                Endpoint("https://[240e::1]:8920", "公网", 3, trustedCertSha256 = "AA:BB"),
            ),
            userId = "u1", accessToken = "tok",
        ))

        val json: String = Json.encodeToString(ServerListSurrogate.serializer(), ServerListSurrogate.from(original))
        val decoded: ServerListSurrogate = Json.decodeFromString(ServerListSurrogate.serializer(), json)
        val back: List<Server> = decoded.toDomain()

        assertEquals(original, back)
    }

    @Test fun `IPv6 方括号地址不被破坏`() {
        val s = Server("s", "n", listOf(Endpoint("https://[240e::1]:8920", "公网", 1)))
        val round = ServerListSurrogate.from(listOf(s)).toDomain().first()
        assertEquals("https://[240e::1]:8920", round.endpoints.first().url)
    }
}
