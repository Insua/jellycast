package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.JellyfinApi

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
        if (runCatching { streamProbe.isAudioOnly(l1) }.getOrDefault(false)) {
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
