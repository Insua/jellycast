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
            } catch (e: Exception) {
                SubtitleTimeline(emptyList())
            }
        }
}
