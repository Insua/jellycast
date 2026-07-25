package dev.insua.jellycast.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 证书夹具全部在测试里用纯 Java/OkHttp API 现场生成(`HeldCertificate.Builder`),
 * 不 shell out 调 openssl/keytool —— 测试离线自足、可重复。
 */
class CertificatePolicyTest {

    // ---- Step 1: 指纹格式 ----

    @Test fun `指纹格式为大写十六进制冒号分隔`() {
        val heldCert = HeldCertificate.Builder()
            .commonName("jellycast-test")
            .build()

        val fp = heldCert.certificate.sha256Fingerprint()

        assertEquals(95, fp.length) // 32 字节 * 2 + 31 个冒号
        assertEquals(fp, fp.uppercase())
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    // ---- 白名单为空:builder 原样返回,不影响明文 HTTP / 普通 HTTPS ----

    @Test fun `白名单为空时 builder 原样返回`() {
        val builder = OkHttpClient.Builder()
        val result = builder.trustPinnedSelfSigned(emptyMap())
        assertTrue(result === builder)
    }

    // ---- 端到端:指纹在白名单且 host 匹配 —— 允许连接 ----

    @Test fun `白名单命中且 host 匹配时信任自签证书`() {
        val heldCert = HeldCertificate.Builder()
            .commonName("jellycast-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCert)
            .build()

        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            start()
        }

        try {
            val fingerprint = heldCert.certificate.sha256Fingerprint()
            val client = OkHttpClient.Builder()
                .trustPinnedSelfSigned(mapOf(server.hostName to fingerprint))
                .build()

            val response = client.newCall(
                okhttp3.Request.Builder().url(server.url("/")).build()
            ).execute()

            assertTrue(response.isSuccessful)
            response.close()
        } finally {
            server.shutdown()
        }
    }

    // ---- 安全性核心测试 1:证书不在白名单 —— 必须仍被系统校验拒绝 ----
    // 这是"绝不允许 trust-all"这条铁律的直接回归测试:如果实现里出现空的
    // checkServerTrusted 或恒真的 HostnameVerifier,这个测试会失败(不会抛异常)。

    @Test fun `证书不在白名单时仍被系统信任链拒绝`() {
        val heldCert = HeldCertificate.Builder()
            .commonName("jellycast-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCert)
            .build()

        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            start()
        }

        try {
            // 白名单为空 —— 自签证书没有任何理由被信任,必须走系统校验并失败。
            val client = OkHttpClient.Builder()
                .trustPinnedSelfSigned(emptyMap())
                .build()

            assertThrows(IOException::class.java) {
                client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute()
            }
        } finally {
            server.shutdown()
        }
    }

    // ---- 安全性核心测试 2:同一指纹登记在别的 host 名下 —— 不得跨 host 生效 ----
    // 复现修正 §3 指出的缺陷:原始实现按值(fingerprint)匹配、不看 host,
    // A 站点确认过的自签证书会被 B 站点冒用。这里指纹完全正确、且确实在白名单里,
    // 但登记的 host 名和实际连接的 host 名不一致 —— 必须仍然拒绝。

    @Test fun `指纹正确但登记的 host 与实际连接 host 不一致时必须拒绝`() {
        val heldCert = HeldCertificate.Builder()
            .commonName("jellycast-test")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCert)
            .build()

        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            start()
        }

        try {
            val fingerprint = heldCert.certificate.sha256Fingerprint()
            // 指纹本身是对的,但挂在一个不相干的 host 名下 —— 模拟"这张自签证书
            // 是给别的 server 确认过的",实际连接的 server.hostName 与之不匹配。
            val client = OkHttpClient.Builder()
                .trustPinnedSelfSigned(mapOf("some-other-server.example" to fingerprint))
                .build()

            assertThrows(IOException::class.java) {
                client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute()
            }
        } finally {
            server.shutdown()
        }
    }

    // ---- 明文 HTTP 不受影响 ----

    @Test fun `明文 HTTP 端点不受证书策略影响`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("ok"))
            start()
        }
        try {
            val client = OkHttpClient.Builder()
                .trustPinnedSelfSigned(mapOf("irrelevant.example" to "AA:BB"))
                .build()

            val response = client.newCall(
                okhttp3.Request.Builder().url(server.url("/")).build()
            ).execute()

            assertTrue(response.isSuccessful)
            response.close()
        } finally {
            server.shutdown()
        }
    }
}
