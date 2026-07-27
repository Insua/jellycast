package dev.insua.jellycast.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthInterceptorTest {
    @Test fun `未登录时也带上客户端标识但无 Token`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ null }, "device-123")).build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        val header = server.takeRequest().getHeader("Authorization")!!
        assertTrue(header.contains("""Client="JellyCast""""))
        assertTrue(header.contains("""DeviceId="device-123""""))
        assertTrue(!header.contains("Token="))
        server.shutdown()
    }

    @Test fun `已登录时附带 Token`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "tok-abc" }, "device-123")).build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertTrue(server.takeRequest().getHeader("Authorization")!!.contains("""Token="tok-abc""""))
        server.shutdown()
    }
}
