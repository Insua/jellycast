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
