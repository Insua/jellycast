package dev.insua.jellycast.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.database.CachedItemDao
import dev.insua.jellycast.database.JellyCastDatabase
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.repository.CachingMediaRepository
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.session.ActiveServerSession
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.network.session.RetrofitSessionApiFactory
import dev.insua.jellycast.network.session.SessionApiFactory
import dev.insua.jellycast.network.session.SessionJellyfinApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Singleton

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

    @Provides
    @Singleton
    fun provideCachedItemDao(database: JellyCastDatabase): CachedItemDao = database.cachedItemDao()

    /**
     * 列表类数据的唯一取数入口(设计文档 §3.1):先发缓存、后台刷新。没开 VPN 打开 App 时,
     * 用户看到的是上次的内容而不是白屏 —— 这条绑定就是"零缓存"那个产品级缺陷的修复点。
     *
     * `JellyCastDatabase` 由 `:core:player` 的 `PlayerModule` 提供(同一个 SingletonComponent,
     * 全应用一个 Room 实例),这里只取它的 [CachedItemDao]。
     */
    @Provides
    @Singleton
    fun provideMediaRepository(dao: CachedItemDao, session: JellyfinSession): MediaRepository =
        CachingMediaRepository(dao, session)

    // `@TrustAwareHttpClient` 的 qualifier + provider(修正 §1c)已挪到
    // `:core:network` 的 `dev.insua.jellycast.network.di.TrustAwareHttpClientModule`
    // (Finding 2:`:core:player` 看不到 `:app` 的绑定,`:core:network` 是两边都依赖的公共模块,
    // 详见该文件 KDoc),这里不再重复声明。
}
