package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.JellyfinApi
import kotlinx.coroutines.CancellationException

/**
 * 探测某个 URL 实测吐出的流是否为"纯音频"(不含视频轨)。
 * 任何异常都不得向上抛出 —— 调用方(resolve)负责把异常吞掉当作探测失败,静默降级。
 */
interface StreamProbe {
    suspend fun isAudioOnly(url: String): Boolean
}

/**
 * 两级音频降级链决策器 —— JellyCast 的技术核心。
 *
 * 依据 docs/superpowers/specs/2026-07-25-spike-results.md 的实测结论:
 * - L1 `GET /Audio/{itemId}/universal`(把音频接口用在视频条目上,不是 `/Videos/`):
 *   服务端直接转出纯音频,audioBitRate 参数实测生效,128 kbps 相对原始 5.57 Mbps 视频流省 42 倍流量。
 * - L2(HLS 独立音频 rendition)实测不存在,已从降级链中彻底删除
 *   (`/Audio/../master.m3u8` 500;`/Videos/../master.m3u8` 只有一条 1080p 混流)。
 * - L3 `GET /Videos/{itemId}/stream?static=true`:兜底,拉完整流由客户端 TrackSelector 禁用视频轨。
 *   L3 必须永远给出可播 URL(项目铁律:降级链必须保底可用)。
 *
 * 转码流不支持 Range 请求(`Accept-Ranges: none`),所以 seek / 断点续播不能靠字节 range,
 * 必须换成带新的 `startTimeTicks` 重新发起请求。`ticks = ms * 10_000`,这个换算只在这一层做一次,
 * 绝不让 ticks 逃逸到上层。
 */
class PlaybackSourceResolver(
    private val api: JellyfinApi,
    private val streamProbe: StreamProbe,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val audioBitRateBps: Int = DEFAULT_AUDIO_BIT_RATE_BPS,
) {
    /**
     * ## 稳定性根因 #3:L1 判定按媒体源缓存
     *
     * seek 只能实现成"带新的 `startTimeTicks` 重新 resolve"(转码流 `Accept-Ranges: none`),
     * 而 [HttpStreamProbe] 每次 resolve 都对 L1 候选 URL 发一次**完整 GET**:
     * **用户每按一次快进,群晖 J4125 上就多起一个转码任务,读完响应头就被丢弃。**
     * 连点几下快进 = 几个孤儿转码在那颗 4 核赛扬上和正在听的那一条抢 CPU —— 用户报的
     * "seek/快进卡死、播放中断"就是这么来的。顺带还白花一整个网络往返,把 seek 拖慢一倍。
     *
     * 而探测结论**和起始位置无关**:「`/Audio/{item}/universal` 在这个 mediaSource 上吐不吐纯音频」
     * 是媒体源自身的属性。所以按 (itemId, mediaSourceId) 记住,同一条流后续所有 seek 直接复用。
     *
     * 用 itemId + mediaSourceId 而不是只用 mediaSourceId:mediaSourceId 是服务端给的标识,
     * 不同条目撞号时宁可多探测一次,也不要拿错的判定去播另一个条目。
     *
     * 键是进程内缓存,不落盘:服务端换了转码配置(装了/掉了 QSV)重启 App 就重新探测,
     * 不需要任何失效逻辑。
     */
    private val audioOnlyByMediaSource = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    suspend fun resolve(itemId: String, userId: String, startPositionMs: Long = 0L): PlaybackSource {
        val info = api.playbackInfo(itemId, userId)
        val ms = info.mediaSources.firstOrNull()
            ?: error("item $itemId has no media source")

        val base = baseUrlProvider().trimEnd('/')
        val token = tokenProvider()
        val startTimeTicks = if (startPositionMs > 0L) startPositionMs * TICKS_PER_MS else null

        val audioTracks = ms.mediaStreams
            .filter { it.type == "Audio" }
            .map { AudioTrack(it.index, it.language, it.displayTitle ?: it.language ?: "音轨 ${it.index}") }

        // 位图字幕(PGS/VobSub)取不到文本,直接过滤,不进入可选字幕列表
        val subtitles = ms.mediaStreams
            .filter { it.type == "Subtitle" && it.isTextSubtitle }
            .map {
                SubtitleTrackRef(
                    index = it.index,
                    language = it.language,
                    displayName = it.displayTitle ?: it.language ?: "字幕 ${it.index}",
                    isTextBased = true,
                )
            }

        // ---- L1:服务端纯音频(GET /Audio/{itemId}/universal) ----
        val l1 = buildAudioUniversalUrl(base, itemId, token, ms.id, audioBitRateBps, startTimeTicks)
        if (isAudioOnly(itemId, ms.id, base, token)) {
            return PlaybackSource(
                itemId = itemId,
                mediaSourceId = ms.id,
                streamUrl = l1,
                level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
                isHls = false,
                playSessionId = info.playSessionId,
                audioTracks = audioTracks,
                textSubtitles = subtitles,
            )
        }

        // ---- L3:兜底,拉完整流由客户端禁用视频轨(GET /Videos/{itemId}/stream?static=true) ----
        val l3 = buildVideoStreamUrl(base, itemId, token, ms.id, startTimeTicks)
        return PlaybackSource(
            itemId = itemId,
            mediaSourceId = ms.id,
            streamUrl = l3,
            level = AudioDeliveryLevel.CLIENT_VIDEO_DISABLED,
            isHls = false,
            playSessionId = info.playSessionId,
            audioTracks = audioTracks,
            textSubtitles = subtitles,
        )
    }

    /**
     * 见 [audioOnlyByMediaSource]。探测用的是**不带 `startTimeTicks` 的**候选 URL —— 判定和起始
     * 位置无关,而且从 0 探测让服务端起的那个(随即被丢弃的)转码任务最轻。
     *
     * 探测异常照旧静默吞掉当"不是纯音频"(铁律 3:降级链必须保底可用,失败不得让用户看到错误)。
     * 但**失败的判定不写进缓存**:那多半是一次偶发的网络抖动,记下来会把整个会话钉死在 L3
     * (~42 倍带宽),这正是本产品最不能接受的静默降级。
     *
     * ⚠️ [CancellationException] **必须**穿过去。原来这里是 `runCatching { … }.getOrDefault(false)`,
     * 连协程取消一起吞:`PlaybackService.onDestroy` 取消 `serviceScope` 时,一次正在飞的 seek 探测被
     * 取消,却被当成"不是纯音频"若无其事地走完 L3 分支,把一个播放源交给一个正在被拆掉的播放器。
     * [HttpStreamProbe] 特意重新抛出了它,不能在这一层又吞回去。**取消不是失败。**
     */
    private suspend fun isAudioOnly(itemId: String, mediaSourceId: String, base: String, token: String): Boolean {
        val key = "$itemId/$mediaSourceId"
        audioOnlyByMediaSource[key]?.let { return it }
        val probeUrl = buildAudioUniversalUrl(base, itemId, token, mediaSourceId, audioBitRateBps, startTimeTicks = null)
        val verdict = try {
            streamProbe.isAudioOnly(probeUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        audioOnlyByMediaSource[key] = verdict
        return verdict
    }

    private fun buildAudioUniversalUrl(
        base: String,
        itemId: String,
        token: String,
        mediaSourceId: String,
        audioBitRateBps: Int,
        startTimeTicks: Long?,
    ): String = buildString {
        append(base).append("/Audio/").append(itemId).append("/universal")
        append("?api_key=").append(token)
        append("&mediaSourceId=").append(mediaSourceId)
        append("&audioCodec=aac")
        append("&audioBitRate=").append(audioBitRateBps)
        append("&maxAudioChannels=2")
        if (startTimeTicks != null) append("&startTimeTicks=").append(startTimeTicks)
    }

    private fun buildVideoStreamUrl(
        base: String,
        itemId: String,
        token: String,
        mediaSourceId: String,
        startTimeTicks: Long?,
    ): String = buildString {
        append(base).append("/Videos/").append(itemId).append("/stream")
        append("?api_key=").append(token)
        append("&mediaSourceId=").append(mediaSourceId)
        append("&static=true")
        if (startTimeTicks != null) append("&startTimeTicks=").append(startTimeTicks)
    }

    private companion object {
        const val DEFAULT_AUDIO_BIT_RATE_BPS = 128_000
        const val TICKS_PER_MS = 10_000L
    }
}
