package dev.insua.jellycast.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class PublicSystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable data class AuthRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable data class AuthResultDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)
@Serializable data class UserDto(@SerialName("Id") val id: String, @SerialName("Name") val name: String)

@Serializable data class ItemsResponseDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
    // key 是 ImageType(如 "Primary"),value 是缓存 tag。核对 docs/jellyfin-openapi.json
    // BaseItemDto.properties.ImageTags:object,additionalProperties: string。
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
)

@Serializable data class UserDataDto(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
)

@Serializable data class MediaStreamDto(
    @SerialName("Type") val type: String,               // Audio / Video / Subtitle
    @SerialName("Index") val index: Int,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsTextSubtitleStream") val isTextSubtitle: Boolean = false,
)

@Serializable data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable data class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

// 以下三个 DTO 对应 POST Sessions/Playing(started) / Sessions/Playing/Progress /
// Sessions/Playing/Stopped 的请求体,字段名与 docs/jellyfin-openapi.json 中
// PlaybackStartInfo / PlaybackProgressInfo / PlaybackStopInfo 的 properties 逐字核对。
// 三个 schema 的 `required` 均为空,唯一非 nullable 的业务字段是 ItemId,其余按本项目
// 需要(播放会话定位 + 进度上报)取子集:PlaySessionId、PositionTicks(单位:ticks,
// 1 tick = 100ns,换算由调用方在 DTO↔Model 映射层完成,这里保持原始 ticks)、IsPaused。
// 故意不建模:Item(冗余,已用 ItemId 引用)、SessionId(Jellyfin 会话 id,非
// PlaySessionId,本项目不做多会话管理)、MediaSourceId/AudioStreamIndex/
// SubtitleStreamIndex/PlayMethod(转码相关,不在本项目职责内)、
// IsMuted/VolumeLevel/Brightness/AspectRatio/PlaybackStartTimeTicks(客户端 UI 状态,
// Jellyfin 服务端不消费)、RepeatMode/PlaybackOrder/NowPlayingQueue/PlaylistItemId
// (队列管理,v1 不做)、LiveStreamId(直播,不适用)、CanSeek(非 required,发或不发
// 不影响进度上报语义)、Failed/NextMediaType(Stop 的失败上报,v1 不需要)。

@Serializable data class PlaybackStartInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
)

@Serializable data class PlaybackProgressInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
)

@Serializable data class PlaybackStopInfoDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long? = null,
)
