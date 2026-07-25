package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 构造健康检查请求 URL:`<baseUrl>/System/Info/Public`。
 *
 * 用 OkHttp 的 [HttpUrl] 解析 + [HttpUrl.Builder.addPathSegments] 拼接,而不是裸字符串拼接,
 * 这样 IPv6 方括号地址(`https://[240e::1]:8920`)在拼接和序列化时始终保持合法形式——
 * [HttpUrl.toString] 在渲染 IPv6 host 时总会带上方括号。
 */
internal fun buildHealthCheckUrl(baseUrl: String): HttpUrl =
    baseUrl.toHttpUrl().newBuilder()
        .addPathSegments("System/Info/Public")
        .build()

/**
 * 不认识 Jellyfin 的完整 API,只探测"这个地址此刻是否可达、多快"。
 * 传入的 [baseClient] 由调用方构造好信任链(证书白名单等是 Task 5 的事,这里绝不自建/全局关闭 TLS 校验)。
 */
class HttpEndpointProbe(baseClient: OkHttpClient) : EndpointProbe {
    private val client = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override suspend fun probe(endpoint: Endpoint): EndpointHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(buildHealthCheckUrl(endpoint.url)).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    EndpointHealth(endpoint, true, System.currentTimeMillis() - start)
                } else {
                    EndpointHealth(endpoint, false, null, "HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            // Structured concurrency: a losing probe cancelled by EndpointSelector.select() must
            // propagate cancellation, not be swallowed into a fake "failed" health result.
            throw e
        } catch (e: Exception) {
            EndpointHealth(endpoint, false, null, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}
