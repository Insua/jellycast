package dev.insua.jellycast.feature.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EndpointUrlNormalizationTest {

    @Test fun `局域网 HTTP 地址原样保留`() {
        assertEquals("http://192.168.1.10:8096", normalizeEndpointUrl("http://192.168.1.10:8096"))
    }

    @Test fun `Tailscale HTTP 地址原样保留`() {
        assertEquals("http://100.126.20.77:8096", normalizeEndpointUrl("http://100.126.20.77:8096"))
    }

    @Test fun `公网 HTTPS DDNS 域名地址原样保留`() {
        assertEquals("https://xxx.ddns.net:8920", normalizeEndpointUrl("https://xxx.ddns.net:8920"))
    }

    @Test fun `IPv6 方括号地址不被破坏`() {
        assertEquals("https://[240e::1]:8920", normalizeEndpointUrl("https://[240e::1]:8920"))
    }

    @Test fun `去除首尾空白`() {
        assertEquals("http://192.168.1.10:8096", normalizeEndpointUrl("  http://192.168.1.10:8096  "))
    }

    @Test fun `去除路径产生的尾部斜杠`() {
        assertEquals("http://192.168.1.10:8096", normalizeEndpointUrl("http://192.168.1.10:8096/"))
        assertEquals("https://[240e::1]:8920", normalizeEndpointUrl("https://[240e::1]:8920/"))
    }

    @Test fun `空字符串不是合法地址`() {
        assertNull(normalizeEndpointUrl(""))
        assertNull(normalizeEndpointUrl("   "))
    }

    @Test fun `非 http-https 协议不是合法地址`() {
        assertNull(normalizeEndpointUrl("ftp://192.168.1.10:8096"))
    }

    @Test fun `不是 URL 的乱字符串不是合法地址`() {
        assertNull(normalizeEndpointUrl("不是一个地址"))
    }
}
