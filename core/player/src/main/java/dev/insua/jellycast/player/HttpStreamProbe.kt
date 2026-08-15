package dev.insua.jellycast.player

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [StreamProbe] 的生产实现:只看响应头的 `Content-Type`,不下载完整流体——L1/L3 候选 URL 背后是
 * 转码流,可能几十上百兆,探测不该把整条流拉下来。
 *
 * `response.use { ... }` 在读完头之后立即关闭响应体/连接,okhttp 不会因为我们没读 body 就继续
 * 等对端把整条流发完。
 *
 * 铁律(接口自身文档已声明):任何异常都不得向上抛出,统一降级为"不是纯音频"——探测失败时让
 * [PlaybackSourceResolver] 走 L3 兜底,而不是让整个 resolve() 崩掉。
 */
class HttpStreamProbe(
    client: OkHttpClient,
    connectTimeoutMs: Long = STREAM_CONNECT_TIMEOUT_MS.toLong(),
    readTimeoutMs: Long = STREAM_READ_TIMEOUT_MS.toLong(),
) : StreamProbe {

    /**
     * ## 为什么在这里派生而不是在 DI 模块里配
     *
     * 注入进来的 `@TrustAwareHttpClient` 是**共享**实例 —— Coil 封面图、字幕拉取都用它。
     * 把读超时在那里拉到 60 秒,会让一张挂掉的封面图也干等一分钟。探测是唯一需要长读超时的
     * 用途,所以超时只属于探测。
     *
     * 用 `newBuilder()` 派生而**不是** `OkHttpClient.Builder()` 新建:派生保留连接池、
     * 以及按 host 白名单的自签证书信任(铁律 5)。新建一个裸 client 会在自签证书服务器上
     * 必定 `SSLHandshakeException`,而 [PlaybackSourceResolver] 会把它静默当成"没结论"降到 L3
     * —— 本产品定义的最坏静默降级。这个坑 `TrustAwareHttpClientModule` 的 KDoc 里踩过一次。
     *
     * 超时做成构造参数是为了能被测试注入小值(见 `HttpStreamProbeTimeoutTest`),
     * 生产默认值就是 [STREAM_CONNECT_TIMEOUT_MS] / [STREAM_READ_TIMEOUT_MS]。
     */
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 返回值三态,见 [StreamProbe]:
     * - `true` / `false` —— 服务端 2xx 回来了,`Content-Type` 说了算,**有结论**。
     * - `null` —— 非 2xx 或者压根没连上,**没问出结论**。J4125 上并发转码打架时
     *   `/Audio/{item}/universal` 回 500 是常态,把它当成"这个源不是纯音频"记下来,
     *   会让整个会话被钉死在 L3。
     */
    override suspend fun isAudioOnly(url: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Type")?.startsWith("audio/", ignoreCase = true) == true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
