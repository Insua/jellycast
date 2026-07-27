package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 *
 * [timeoutMs] 是这一次探测请求自己的连接 + 读超时,默认取 [DEFAULT_TIMEOUT_MS]。它与
 * [EndpointSelector] 的 `timeoutMs` 概念不同、来源不同——见 [EndpointSelector.select] 的 KDoc。
 */
class HttpEndpointProbe(
    baseClient: OkHttpClient,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : EndpointProbe {
    private val client = baseClient.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * ## 为什么整段跑在 [Dispatchers.IO] 上,而不是"反正 enqueue 是异步的所以随便哪个线程都行"
     *
     * `enqueue()` 本身确实不在调用线程上做网络 I/O,但 **`Response.close()` 会**:
     * 探测只看状态码、从不读响应体,于是 `use { }` 退出时 OkHttp 为了让连接能复用,会把没读完的
     * 响应体 drain 掉——`Http1ExchangeCodec$FixedLengthSource.close()` → `discard()` →
     * `skipAll()` → `SocketInputStream.read()`,一次**阻塞 socket 读**。而
     * `suspendCancellableCoroutine` 的续体是按**调用方的调度器**恢复的,所以调用方在
     * `Dispatchers.Main`(`viewModelScope.launch { }` 就是)时,这个 socket 读发生在主线程上,
     * StrictMode 直接抛 `NetworkOnMainThreadException`。
     *
     * 而且它是**竞态**的:如果响应头和响应体恰好在同一个 TCP 段里到达,drain 只消耗 okio 缓冲区、
     * 不碰 socket,就什么事都没有——这正是真机上"有时能连上、有时报错"的原因,也是 274 个纯 JVM
     * 单测全绿却没发现它的原因(JVM 上根本没有 StrictMode)。
     *
     * 因此 dispatcher 保证放在这里、而不是放在 ViewModel 调用点:一个执行 I/O 的 `suspend` 函数
     * 必须能从任意调度器安全调用,这是被调用方的契约,不是每个调用方各自记得包一层 IO 的义务。
     */
    override suspend fun probe(endpoint: Endpoint): EndpointHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(buildHealthCheckUrl(endpoint.url)).get().build()
            val call = client.newCall(request)
            call.await().use { response ->
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
            EndpointHealth(endpoint, false, null, describeProbeFailure(endpoint, e))
        }
    }

    companion object {
        /**
         * 单次探测请求的连接 + 读超时,毫秒。产品设计要求 3s 探测超时,这里作为唯一权威来源——
         * [EndpointSelector] 的整体超时默认值也引用这个常量,避免两处各写一份字面量、改一处忘另一处。
         */
        const val DEFAULT_TIMEOUT_MS = 3000L
    }
}

/**
 * 把 OkHttp 的异步 [Call.enqueue] 包成可挂起、可取消的调用。
 *
 * 关键点是 [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation] 里真的调用了
 * [Call.cancel]——协程取消(比如 [EndpointSelector.select] 淘汰掉落选的探测)会让底层 HTTP 调用
 * 立刻中止(关闭连接/从 OkHttp 调度队列移除),而不是只在协程层面记账、底层请求继续跑到自己的
 * connect/read 超时才收尾。这样落选探测不会占着 socket 不放,select() 的收尾也不必等它。
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            // Call.cancel() from invokeOnCancellation below surfaces here as an IOException; the
            // continuation is already cancelled by then, so resuming it would throw. Just drop it.
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation {
        cancel()
    }
}
