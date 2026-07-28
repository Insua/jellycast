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

/**
 * 缺陷 1(设计文档 §3.3):`IsExternal` 字段名已用 jq 核对
 * `docs/jellyfin-openapi.json` 的 `MediaStream.properties`(`IsExternal: boolean`) ——
 * 服务端外挂字幕文件(含弹幕)与内嵌字幕轨都用它区分。
 */
data class SubtitleTrackRef(
    val index: Int,
    val language: String?,
    val displayName: String,
    val isTextBased: Boolean,   // 位图字幕(PGS/VobSub)为 false,不展示
    val isExternal: Boolean = false,
) {
    /**
     * 弹幕类外挂字幕轨的识别信号:外挂(`IsExternal` true)且标题含 danmu/弹幕关键字。
     * 实测样本里 index 0 是 3676 条的外挂弹幕轨,index 4 才是 774 条的真字幕——弹幕轨条目数
     * 异常多,但"条目数"不是稳定信号(不同源差异很大),标题关键字更可靠。
     *
     * 只判定、不过滤——过滤/降级的决策留给调用方(`PlayerViewModel`),这里保持纯粹的信号。
     */
    val isLikelyDanmaku: Boolean
        get() = isExternal && DANMAKU_TITLE_MARKERS.any { displayName.contains(it, ignoreCase = true) }

    private companion object {
        // 纯字符串包含判断,不用 Regex —— 避免重蹈 SubtitleTags 正则在 Android ICU 引擎上
        // 语法不兼容的覆辙(见 docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2)。
        val DANMAKU_TITLE_MARKERS = listOf("danmu", "danmaku", "弹幕")
    }
}
