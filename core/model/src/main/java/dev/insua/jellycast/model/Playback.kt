package dev.insua.jellycast.model

/** 音频降级链的级别 */
enum class AudioDeliveryLevel {
    /** L1 服务端直出纯音频 */ SERVER_AUDIO_ONLY,
    /** L2 HLS 独立音频 rendition */ HLS_AUDIO_RENDITION,
    /** L3 拉完整流,客户端禁用视频轨 */ CLIENT_VIDEO_DISABLED,
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
