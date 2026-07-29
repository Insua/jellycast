package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SubtitleRepository(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    /**
     * 独立于播放流拉取字幕文本。
     * 因为播放走的是纯音频流,字幕轨不在其中,必须单独取。
     *
     * 铁律:字幕失败不得影响播放。任何失败(HTTP 非 2xx、网络异常、解析异常)
     * 都必须降级为"无字幕"(空 SubtitleTimeline),绝不向上抛出异常。
     *
     * ## 为什么这里 `catch (e: Throwable)` 而不是常规的 `catch (e: Exception)`
     *
     * 这是一个**刻意的、有边界的例外**,不是随手放宽——放宽到 `Throwable` 连
     * `OutOfMemoryError` 这类"本该让进程死掉"的错误也会被吞掉,这在绝大多数路径上都是错的。
     *
     * 之所以在**这一个方法**里这么做:字幕是纯装饰功能,v1 就定下的铁律是"字幕相关异常绝不
     * 向上抛错"。而现实中确实发生过一次"失败以 `Error` 形式逃逸"——`SubtitleTags` 内部的正则
     * 编译失败被 JVM 包装成 `ExceptionInInitializerError`(是 `Error`,不是 `Exception`),
     * 原来的 `catch (e: Exception)` 接不住,一路穿透到进程被杀
     * (详见 docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2)。
     * 只转义那一个正则治标不治本——下一次任何新的解析器 bug、任何新的 `Error` 子类,
     * 只要发生在这条调用链上,同样的穿透还会再来一次。
     *
     * **边界:**
     * - `CancellationException` 必须无条件重抛——它是结构化并发用来传递"这个协程该停了"的信号,
     *   吞掉它会让取消传播断裂(下面这条 `catch` 分支之前特意先接一次 `CancellationException` 再重抛)。
     * - 这个取舍**只适用于字幕路径**。不要把 `catch (e: Throwable)` 当成通用写法抄到别处——
     *   播放、网络、进度上报等路径的失败是要向上传播、处理或提示用户的,吞掉 `Throwable`
     *   在那些地方是缺陷,不是防御。
     */
    suspend fun load(itemId: String, mediaSourceId: String, subtitleIndex: Int): SubtitleTimeline =
        withContext(Dispatchers.IO) {
            try {
                val format = "srt"
                val url = "${baseUrlProvider().trimEnd('/')}/Videos/$itemId/$mediaSourceId/" +
                    "Subtitles/$subtitleIndex/Stream.$format?api_key=${tokenProvider()}"
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        SubtitleTimeline(emptyList())
                    } else {
                        SubtitleTimeline(parserFor(format).parse(resp.body.string()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SubtitleTimeline(emptyList())
            }
        }
}
