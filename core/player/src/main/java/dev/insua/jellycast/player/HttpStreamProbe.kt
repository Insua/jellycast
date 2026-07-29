package dev.insua.jellycast.player

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
class HttpStreamProbe(private val client: OkHttpClient) : StreamProbe {
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
