package dev.insua.jellycast.feature.server

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
 * 每个 [Endpoint] 有各自的 baseUrl、各自可能的自签证书信任白名单,所以不能像常规 DI 那样注入
 * 一个全局单例 [JellyfinApi]——这里按 endpoint 现场组装一个 Retrofit 客户端。
 *
 * 铁律:不允许全局关闭 TLS 校验。这里只是复用 :core:network 已经实现、已按 host 隔离的
 * [trustPinnedSelfSigned],不重新实现信任逻辑;`endpoint.trustedCertSha256` 为 null 时,
 * [trustPinnedSelfSigned] 原样返回 builder,完全依赖系统信任链。
 */
fun interface JellyfinApiFactory {
    fun create(endpoint: Endpoint): JellyfinApi
}

class RetrofitJellyfinApiFactory : JellyfinApiFactory {
    override fun create(endpoint: Endpoint): JellyfinApi {
        val host = URI(endpoint.url).host
        val trustedByHost = endpoint.trustedCertSha256
            ?.let { fingerprint -> if (host != null) mapOf(host to fingerprint) else emptyMap() }
            ?: emptyMap()

        val client = OkHttpClient.Builder()
            .trustPinnedSelfSigned(trustedByHost)
            // 登录请求本身不需要携带 token;AuthInterceptor 只是补上 Jellyfin 要求的
            // Client/Device/DeviceId 标识头,tokenProvider 为 null 表示"尚未登录"。
            .addInterceptor(AuthInterceptor(tokenProvider = { null }, deviceId = UUID.randomUUID().toString()))
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
