package dev.insua.jellycast.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.session.ActiveServerSession
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.network.session.RetrofitSessionApiFactory
import dev.insua.jellycast.network.session.SessionApiFactory
import dev.insua.jellycast.network.session.SessionJellyfinApi
import dev.insua.jellycast.network.trustPinnedSelfSigned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.URI
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 只用于 Coil 封面图 / 字幕 HTTP 拉取的"信任 endpoint 自签证书"OkHttpClient(修正 §1c)。
 * 单独打一个 qualifier,避免和 [dev.insua.jellycast.player.di.PlayerModule] 里那个
 * "只用来探测 Content-Type、不需要认证也不需要信任自签证书"的普通 [OkHttpClient] 绑定混淆。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TrustAwareHttpClient

/**
 * ## 装配整个应用的关键:运行时可变 baseUrl / JellyfinApi 单例
 *
 * 详细设计见 [ActiveServerSession] / [SessionJellyfinApi] 的 KDoc,这里只是把它们接进 Hilt 图:
 * - [ServerStore] / [EndpointSelector] 复用 `:feature:server` 的 `ServerModule` 已有绑定,
 *   不在这里重复 `@Provides`(会造成 Dagger 报重复绑定)。
 * - [provideJellyfinSession] 把 `ServerStore.servers` + `ServerStore.activeServerId` 组合成
 *   一个 `Flow<Server?>`,交给 [ActiveServerSession]——这是"哪台服务器是当前激活的"这份状态
 *   唯一被读取的地方。
 * - [provideJellyfinApi] 是全项目**唯一**的 [JellyfinApi] 绑定:一个稳定单例,内部通过
 *   [JellyfinSession] 按需重新选路。所有需要发 Jellyfin 请求的地方(ViewModel、
 *   `PlaybackSourceResolver`、`ProgressReporter`、`AutoPlayNextController`……)都注入同一个实例。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    fun provideSessionApiFactory(): SessionApiFactory = RetrofitSessionApiFactory()

    @Provides
    @Singleton
    fun provideJellyfinSession(
        serverStore: ServerStore,
        endpointSelector: EndpointSelector,
        apiFactory: SessionApiFactory,
    ): JellyfinSession {
        val activeServer: Flow<Server?> =
            combine(serverStore.servers, serverStore.activeServerId) { servers, activeId ->
                servers.find { it.id == activeId }
            }
        return ActiveServerSession(activeServer, endpointSelector, apiFactory)
    }

    @Provides
    @Singleton
    fun provideJellyfinApi(session: JellyfinSession): JellyfinApi = SessionJellyfinApi(session)

    /**
     * 铁律复查:证书信任只走 [trustPinnedSelfSigned](:core:network 已实现、按 host 隔离、Task 5
     * 审查过),绝不新增全局 TrustManager/HostnameVerifier。
     *
     * 已知取舍:白名单是应用启动时对 [ServerStore] 的一次快照(`runBlocking { .first() }`)——
     * 进程存活期间新增的自签证书信任(用户在添加服务器表单里刚确认的指纹)要等下次冷启动才会
     * 影响封面图/字幕请求。API 请求本身不受影响,因为它们经过 [JellyfinApi] 这条完全独立的路径
     * (每次选路都重新读最新的 `Endpoint.trustedCertSha256`)。记录在任务报告里。
     */
    @Provides
    @Singleton
    @TrustAwareHttpClient
    fun provideTrustAwareOkHttpClient(serverStore: ServerStore): OkHttpClient {
        val trustedByHost = runBlocking {
            serverStore.servers.first().flatMap { server ->
                server.endpoints.mapNotNull { endpoint ->
                    val fingerprint = endpoint.trustedCertSha256 ?: return@mapNotNull null
                    val host = runCatching { URI(endpoint.url).host }.getOrNull() ?: return@mapNotNull null
                    host to fingerprint
                }
            }.toMap()
        }
        return OkHttpClient.Builder().trustPinnedSelfSigned(trustedByHost).build()
    }
}
