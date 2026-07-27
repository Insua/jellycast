package dev.insua.jellycast.network.session

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.network.AuthInterceptor
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.trustPinnedSelfSigned
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.URI
import java.util.UUID

/**
 * 按 [Endpoint] 现场组装一个**已认证**的 [JellyfinApi] 客户端。
 *
 * 和 `feature:server` 的 `JellyfinApiFactory`(登录专用,`tokenProvider` 恒为 null)是两个不同的
 * 东西——那个只用于"还没有 token 时"的登录请求,这个用于登录之后所有正常业务请求,`tokenProvider`
 * 由调用方(会话层)传入,读取当前登录用户的 access token。两者刻意不合并成一个接口:登录请求和
 * 已认证请求对"token 从哪来"的语义完全不同,合并会强迫其中一个分支传一个没有意义的空实现。
 *
 * 铁律复查:证书信任仍然只走 [trustPinnedSelfSigned](per-host 白名单),不新增任何全局信任分支。
 */
fun interface SessionApiFactory {
    fun create(endpoint: Endpoint, tokenProvider: () -> String?): JellyfinApi
}

class RetrofitSessionApiFactory : SessionApiFactory {
    override fun create(endpoint: Endpoint, tokenProvider: () -> String?): JellyfinApi {
        val host = URI(endpoint.url).host
        val trustedByHost = endpoint.trustedCertSha256
            ?.let { fingerprint -> if (host != null) mapOf(host to fingerprint) else emptyMap() }
            ?: emptyMap()

        val client = OkHttpClient.Builder()
            .trustPinnedSelfSigned(trustedByHost)
            .addInterceptor(AuthInterceptor(tokenProvider = tokenProvider, deviceId = UUID.randomUUID().toString()))
            .build()

        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(if (endpoint.url.endsWith("/")) endpoint.url else "${endpoint.url}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(JellyfinApi::class.java)
    }
}
