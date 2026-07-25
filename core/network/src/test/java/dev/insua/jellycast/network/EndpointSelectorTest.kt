package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private fun ep(url: String, p: Int) = Endpoint(url, "L$p", p)

class EndpointSelectorTest {

    private fun probeOf(vararg results: Pair<String, Pair<Boolean, Long>>) = object : EndpointProbe {
        private val map = results.toMap()
        override suspend fun probe(endpoint: Endpoint): EndpointHealth {
            val (ok, latency) = map[endpoint.url] ?: (false to 0L)
            delay(latency)
            return EndpointHealth(endpoint, ok, if (ok) latency else null,
                if (ok) null else "unreachable")
        }
    }

    @Test fun `返回最先成功响应的 endpoint`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://slow" to (true to 900L),
            "http://fast" to (true to 100L),
        ))
        val chosen = selector.select(listOf(ep("http://slow", 1), ep("http://fast", 2)))
        assertEquals("http://fast", chosen?.endpoint?.url)
    }

    @Test fun `跳过不可达的 endpoint`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://dead" to (false to 0L),
            "http://alive" to (true to 200L),
        ))
        val chosen = selector.select(listOf(ep("http://dead", 1), ep("http://alive", 2)))
        assertEquals("http://alive", chosen?.endpoint?.url)
    }

    @Test fun `全部不可达返回 null`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://a" to (false to 0L), "http://b" to (false to 0L),
        ))
        assertNull(selector.select(listOf(ep("http://a", 1), ep("http://b", 2))))
    }

    @Test fun `probeAll 返回全部结果用于诊断展示`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://a" to (false to 0L), "http://b" to (true to 50L),
        ))
        val all = selector.probeAll(listOf(ep("http://a", 1), ep("http://b", 2)))
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.reachable })
        assertEquals("unreachable", all.first { !it.reachable }.failureReason)
    }

    @Test fun `空列表返回 null`() = runTest {
        assertNull(EndpointSelector(probeOf()).select(emptyList()))
    }

    @Test fun `IPv6 方括号地址的健康检查 URL 拼接保留方括号`() {
        val url = buildHealthCheckUrl("https://[240e::1]:8920")
        assertEquals("https", url.scheme)
        assertEquals(8920, url.port)
        // toString() must render the host back in bracket form, never a bare/garbled IPv6 literal.
        assertEquals("https://[240e::1]:8920/System/Info/Public", url.toString())
    }
}
