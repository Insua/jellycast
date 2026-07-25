package dev.insua.jellycast.player.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.database.JellyCastDatabase
import dev.insua.jellycast.database.ProgressReportDao
import dev.insua.jellycast.database.buildJellyCastDatabase
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AudioPlaybackEngineImpl
import dev.insua.jellycast.player.AutoPlayNextController
import dev.insua.jellycast.player.ExoPlayerControl
import dev.insua.jellycast.player.HttpStreamProbe
import dev.insua.jellycast.player.PlaybackSourceProvider
import dev.insua.jellycast.player.PlaybackSourceResolver
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.ProgressReporter
import dev.insua.jellycast.player.StreamProbe
import dev.insua.jellycast.player.asProvider
import dev.insua.jellycast.player.createAudioOnlyPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * 闭合修正 §1(a):这个模块之前完全不存在——`AudioPlaybackEngine`/`PlaybackSourceResolver` 写好了
 * 但没有任何生产 provider 把它们和真实的 `JellyfinApi`/`ExoPlayer` 接起来,后果是
 * `PlaybackService.bindEngine()` 在生产环境从来没被调用过(见 `PlaybackService` 的改动)。
 *
 * `JellyfinApi` / `JellyfinSession` 由 :app 的 DI 模块提供(见该模块的 KDoc——运行时可变 baseUrl
 * 的核心设计在那里);`ServerStore` / `EndpointSelector` 复用 `:feature:server` 的 `ServerModule`
 * 已有绑定。这里只装配"播放"这个领域自己的东西。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    /** 只用于 [HttpStreamProbe] 探测流的 Content-Type,不需要认证 token,普通 client 即可。 */
    @Provides
    fun provideStreamProbeOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    fun provideStreamProbe(client: OkHttpClient): StreamProbe = HttpStreamProbe(client)

    /**
     * [PlaybackSourceResolver] 的 `baseUrlProvider`/`tokenProvider` 是同步 lambda(见接口 KDoc),
     * 读取 [JellyfinSession] 的同步缓存值——resolve() 内部总是先调一次 `api.playbackInfo(...)`,
     * 而这个 `api` 就是 :app 提供的、内部会先经过 [JellyfinSession.api] 的会话代理,所以调用到
     * baseUrlProvider/tokenProvider 时缓存必然已经被同一次 resolve() 暖好,详见 `JellyfinSession` KDoc。
     */
    /**
     * `audioBitRateBps` 是 [PlaybackSourceResolver] 构造时固定的 Int(既有、已测试过的签名,不改
     * 成 lambda)。设置页允许用户选码率(修正 §3),这里在装配时刻做一次快照——已知取舍:
     * 运行中修改设置需要重启 App/进程才会用新码率,记录在任务报告里,和 [provideProgressReporter]
     * 的 serverId 快照是同一类取舍。
     */
    @Provides
    fun providePlaybackSourceResolver(
        api: JellyfinApi,
        streamProbe: StreamProbe,
        session: JellyfinSession,
        preferencesStore: PreferencesStore,
    ): PlaybackSourceResolver {
        val audioBitRateBps = runBlocking { preferencesStore.audioBitRateKbps.first() } * 1000
        return PlaybackSourceResolver(
            api = api,
            streamProbe = streamProbe,
            baseUrlProvider = { session.cachedBaseUrlOrNull().orEmpty() },
            tokenProvider = { session.cachedTokenOrNull().orEmpty() },
            audioBitRateBps = audioBitRateBps,
        )
    }

    @Provides
    fun providePlaybackSourceProvider(resolver: PlaybackSourceResolver): PlaybackSourceProvider = resolver.asProvider()

    @Provides
    @Singleton
    fun providePlayQueue(): PlayQueue = PlayQueue()

    @Provides
    @Singleton
    fun provideAutoPlayNextController(
        playQueue: PlayQueue,
        sourceProvider: PlaybackSourceProvider,
        api: JellyfinApi,
        preferencesStore: PreferencesStore,
    ): AutoPlayNextController = AutoPlayNextController(playQueue, sourceProvider, api, preferencesStore)

    /**
     * 单例 [ExoPlayer]:同一个实例既是 [AudioPlaybackEngine] 内部驱动的播放器,也是
     * `PlaybackService` 建 `MediaSession`(经 `SeekInterceptingPlayer` 包一层)时用的那个播放器——
     * 两者必须是同一个实例,否则"引擎切换 URL"和"锁屏/通知栏看到的播放状态"就是两个互不相干的播放器。
     */
    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer = createAudioOnlyPlayer(context)

    @Provides
    @Singleton
    fun provideAudioPlaybackEngine(
        exoPlayer: ExoPlayer,
        sourceProvider: PlaybackSourceProvider,
    ): AudioPlaybackEngine = AudioPlaybackEngineImpl(sourceProvider, ExoPlayerControl(exoPlayer))

    @Provides
    @Singleton
    fun providePreferencesStore(@ApplicationContext context: Context): PreferencesStore = PreferencesStore(context)

    @Provides
    @Singleton
    fun provideJellyCastDatabase(@ApplicationContext context: Context): JellyCastDatabase =
        buildJellyCastDatabase(context)

    @Provides
    fun provideProgressReportDao(database: JellyCastDatabase): ProgressReportDao = database.progressReportDao()

    /**
     * [ProgressReporter] 的 `serverId` 是构造时固定的值(既有、已测试过的签名,不改)。这里用
     * [ServerStore.activeServerId] 在装配时刻做一次快照——已知取舍:进程存活期间切换激活服务器
     * 不会更新这个值(本地补报队列的分区键),需要重启进程才会用新服务器的 id。记录在任务报告里。
     */
    @Provides
    fun provideProgressReporter(
        api: JellyfinApi,
        dao: ProgressReportDao,
        serverStore: ServerStore,
    ): ProgressReporter {
        val serverId = runBlocking { serverStore.activeServerId.first() } ?: "unknown-server"
        return ProgressReporter(api, dao, serverId)
    }
}
