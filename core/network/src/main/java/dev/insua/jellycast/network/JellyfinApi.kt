package dev.insua.jellycast.network

import dev.insua.jellycast.network.dto.*
import retrofit2.http.*

/**
 * Jellyfin 10.10.7 REST API 子集。签名逐一核对过 docs/jellyfin-openapi.json,
 * 查询参数大小写以 OpenAPI 文档为准(Jellyfin 的参数名大小写不统一,写错会被静默忽略)。
 *
 * 关于 @Query / @Body 的 Kotlin 默认参数值:已用独立的跨模块编译 + 动态代理实验验证过——
 * Kotlin 在调用处(而非接口实现)解析默认值,生成 `XxxApi$DefaultImpls.foo$default(...)` 桥接方法,
 * 补全全部参数后才把调用转发给接口的抽象方法。这个机制与 Retrofit 用
 * `Proxy.newProxyInstance` 实现接口的方式完全兼容(包括 suspend 函数,Continuation 只是被追加的
 * 最后一个参数),所以默认值在纯 Kotlin 调用方(本项目全 Kotlin)下是可靠的,予以保留。
 */
interface JellyfinApi {
    @GET("System/Info/Public")
    suspend fun publicInfo(): PublicSystemInfoDto

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthRequestDto): AuthResultDto

    // 原文 GET Users/{userId}/Items 在 10.10.7 已移除,改为 GET Items?userId=
    // startIndex / searchTerm 均已核对 docs/jellyfin-openapi.json 的 /Items 参数表。
    // 注意 Jellyfin 对错误大小写的查询参数静默忽略,不报错 —— 参数名必须逐字与 OpenAPI 一致。
    @GET("Items")
    suspend fun items(
        @Query("userId") userId: String,
        @Query("includeItemTypes") types: String,
        @Query("recursive") recursive: Boolean = true,
        @Query("sortBy") sortBy: String = "SortName",
        @Query("startIndex") startIndex: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("parentId") parentId: String? = null,
        @Query("searchTerm") searchTerm: String? = null,
    ): ItemsResponseDto

    // 原文 GET Users/{userId}/Items/Resume 在 10.10.7 已移除,改为 GET UserItems/Resume?userId=
    @GET("UserItems/Resume")
    suspend fun resume(@Query("userId") userId: String): ItemsResponseDto

    @GET("Shows/NextUp")
    suspend fun nextUp(@Query("userId") userId: String, @Query("limit") limit: Int = 20): ItemsResponseDto

    @GET("Shows/{seriesId}/Seasons")
    suspend fun seasons(@Path("seriesId") seriesId: String, @Query("userId") userId: String): ItemsResponseDto

    @GET("Shows/{seriesId}/Episodes")
    suspend fun episodes(
        @Path("seriesId") seriesId: String,
        @Query("seasonId") seasonId: String,
        @Query("userId") userId: String,
    ): ItemsResponseDto

    // 原文 GET Users/{userId}/Items/{itemId} 在 10.10.7 已移除,改为 GET Items/{itemId}?userId=
    @GET("Items/{itemId}")
    suspend fun itemDetail(@Path("itemId") itemId: String, @Query("userId") userId: String): BaseItemDto

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun playbackInfo(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Body body: Map<String, String> = emptyMap(),
    ): PlaybackInfoResponseDto

    @POST("Sessions/Playing")
    suspend fun reportStart(@Body body: PlaybackStartInfoDto)

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: PlaybackProgressInfoDto)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportStop(@Body body: PlaybackStopInfoDto)
}
