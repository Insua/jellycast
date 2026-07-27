package dev.insua.jellycast.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给每个请求附上 Jellyfin 的 `Authorization: MediaBrowser ...` 客户端标识头。
 * 未登录(tokenProvider 返回 null)时仍带上 Client/Device/DeviceId/Version,只是不带 Token。
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val deviceId: String,
    private val deviceName: String = android.os.Build.MODEL ?: "Android",
    private val version: String = "0.1.0",
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val auth = buildString {
            append("""MediaBrowser Client="JellyCast", Device="$deviceName", """)
            append("""DeviceId="$deviceId", Version="$version"""")
            if (token != null) append(""", Token="$token"""")
        }
        return chain.proceed(
            chain.request().newBuilder().header("Authorization", auth).build()
        )
    }
}
