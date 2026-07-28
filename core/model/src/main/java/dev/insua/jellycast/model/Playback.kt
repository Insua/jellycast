package dev.insua.jellycast.model

/**
 * 音频降级链的级别。
 *
 * 原设计为三级,含 L2(HLS 独立音频 rendition)。Spike 实测
 * `/Audio/{id}/master.m3u8` 返回 HTTP 500,`/Videos/{id}/master.m3u8` 只有一条
 * 混流、零个独立音频 rendition —— L2 永不可达,已删除。
 * 详见 docs/superpowers/specs/2026-07-25-spike-results.md。
 */
enum class AudioDeliveryLevel {
    /** L1 服务端直出纯音频:GET /Audio/{itemId}/universal */ SERVER_AUDIO_ONLY,
    /** L3 拉完整流,客户端禁用视频轨:GET /Videos/{itemId}/stream(不带 static —— 带上服务端会忽略 startTimeTicks) */ CLIENT_VIDEO_DISABLED,
}

data class PlaybackSource(
    val itemId: String,
    val mediaSourceId: String,
    val streamUrl: String,
    val level: AudioDeliveryLevel,
    val isHls: Boolean,
    val playSessionId: String?,
    val audioTracks: List<AudioTrack>,
    val textSubtitles: List<SubtitleTrackRef>,
)

data class AudioTrack(val index: Int, val language: String?, val displayName: String)

data class SubtitleTrackRef(
    val index: Int,
    val language: String?,
    val displayName: String,
    val isTextBased: Boolean,   // 位图字幕(PGS/VobSub)为 false,不展示
)
