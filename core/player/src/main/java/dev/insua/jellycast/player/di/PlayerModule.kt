package dev.insua.jellycast.player.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.cache.AudioCacheDownloader
import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.database.JellyCastDatabase
import dev.insua.jellycast.database.ProgressReportDao
import dev.insua.jellycast.database.buildJellyCastDatabase
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.di.TrustAwareHttpClient
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AudioPlaybackEngineImpl
import dev.insua.jellycast.player.AutoPlayNextController
import dev.insua.jellycast.player.CacheAwareSourceProvider
import dev.insua.jellycast.player.ExoPlayerControl
import dev.insua.jellycast.player.HttpStreamProbe
import dev.insua.jellycast.player.PlaybackProgressCoordinator
import dev.insua.jellycast.player.PlaybackSourceProvider
import dev.insua.jellycast.player.PlaybackSourceResolver
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.ProgressReporter
import dev.insua.jellycast.player.StreamProbe
import dev.insua.jellycast.player.asProvider
import dev.insua.jellycast.player.createAudioOnlyPlayer
import dev.insua.jellycast.player.toPlaybackDisplayMetadata
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

    /**
     * ⚠️ Finding 2(已闭合):[HttpStreamProbe] 探测的是 L1 候选 URL 本身——如果这条 URL 挂在一个
     * 自签证书的 endpoint 上,用不认得该证书的裸 `OkHttpClient()` 探测必定
     * `SSLHandshakeException`,而 [PlaybackSourceResolver] 把探测异常静默当"不是纯音频"处理、
     * 直接退化到 L3(全量视频流,~42 倍带宽)——这是本产品要不惜代价避免的"用户毫无感知的
     * 最坏降级"。改用 [TrustAwareHttpClient],和 Coil/字幕共用同一份"系统信任优先、白名单里
     * 用户确认过的指纹兜底"的证书策略(`:core:network` 的 `trustPinnedSelfSigned`),不新增任何
     * TrustManager/HostnameVerifier。
     */
    @Provides
    fun provideStreamProbe(@TrustAwareHttpClient client: OkHttpClient): StreamProbe = HttpStreamProbe(client)

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
    /**
     * ⚠️ 必须是 `@Singleton`。[PlaybackSourceResolver] 内部按媒体源缓存 L1 探测结论(见该类的
     * `audioOnlyByMediaSource`);每个注入点各造一个 resolver 的话就是各缓存各的,
     * "同一条流只探测一次"这件事当场作废,seek 照样在群晖上起孤儿转码。
     * 顺带也让下面那次 DataStore 的 `runBlocking` 快照在整个进程里只发生一次。
     */
    @Provides
    @Singleton
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

    /**
     * [AudioCacheStore] 复用 [provideStreamProbe] 那份带证书信任策略的 [TrustAwareHttpClient]——
     * 缓存下载和 L1 探测打的是同一个可能自签证书的 endpoint,不新增第二套证书处理逻辑。
     * `dao` 取自下面 [provideJellyCastDatabase] 提供的同一个单例数据库实例。
     */
    @Provides
    @Singleton
    fun provideAudioCacheStore(
        @ApplicationContext context: Context,
        database: JellyCastDatabase,
        @TrustAwareHttpClient client: OkHttpClient,
    ): AudioCacheStore = AudioCacheStore(
        context = context,
        dao = database.cachedAudioDao(),
        downloader = AudioCacheDownloader(client),
    )

    /**
     * 生产环境的 [PlaybackSourceProvider] 是 [CacheAwareSourceProvider] 包一层 [PlaybackSourceResolver]
     * ——命中本地音频缓存就直接播本地文件,未命中原样委派给 resolver 的 L1/L3 降级链,一字不动。
     * 设计决定见 [CacheAwareSourceProvider] 类 KDoc。
     *
     * `serverId` 是装配时刻的快照,和 [provideProgressReporter] 的 `serverId` 同一类已知取舍——
     * 进程存活期间切换激活服务器不会更新这个值,需要重启进程才会用新服务器的 id。
     */
    @Provides
    @Singleton
    fun providePlaybackSourceProvider(
        resolver: PlaybackSourceResolver,
        cacheStore: AudioCacheStore,
        api: JellyfinApi,
        serverStore: ServerStore,
    ): PlaybackSourceProvider {
        val serverId = runBlocking { serverStore.activeServerId.first() } ?: "unknown-server"
        return CacheAwareSourceProvider(
            delegate = resolver.asProvider(),
            cacheStore = cacheStore,
            api = api,
            serverId = serverId,
        )
    }

    @Provides
    @Singleton
    fun providePlayQueue(): PlayQueue = PlayQueue()

    @Provides
    @Singleton
    fun provideAutoPlayNextController(
        playQueue: PlayQueue,
        api: JellyfinApi,
        preferencesStore: PreferencesStore,
    ): AutoPlayNextController = AutoPlayNextController(playQueue, api, preferencesStore)

    /**
     * 单例 [ExoPlayer]:同一个实例既是 [AudioPlaybackEngine] 内部驱动的播放器,也是
     * `PlaybackService` 建 `MediaSession`(经 `SeekInterceptingPlayer` 包一层)时用的那个播放器——
     * 两者必须是同一个实例,否则"引擎切换 URL"和"锁屏/通知栏看到的播放状态"就是两个互不相干的播放器。
     */
    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer = createAudioOnlyPlayer(context)

    /**
     * `metadataProvider` 是「媒体元数据」任务的接线点:锁屏/通知/蓝牙/流体云共同的前提。
     * 按 itemId 到 [PlayQueue.current] 里查——[PlayQueue] 已经在 `engine.play()` 之前被调用方
     * (`AppSessionViewModel.play()` / [dev.insua.jellycast.player.PlaybackEndedAdvancer])
     * 设成目标条目,所以这里查到的永远是"即将/正在播的那一条",不是陈旧数据。查不到
     * (itemId 对不上,理论上不会发生但防御性地处理)时返回 `null`,[ExoPlayerControl] 对 `null`
     * 的处理是"标题/副标题/封面都不设",不影响播放。
     *
     * 封面 URL 复用 `:core:network` 的 `posterUrl`(经 [toPlaybackDisplayMetadata]),baseUrl 取
     * [JellyfinSession.cachedBaseUrlOrNull]——和 [providePlaybackSourceResolver] 的
     * `baseUrlProvider` 同一份缓存值,同步、非阻塞。
     */
    @Provides
    @Singleton
    fun provideAudioPlaybackEngine(
        exoPlayer: ExoPlayer,
        sourceProvider: PlaybackSourceProvider,
        playQueue: PlayQueue,
        session: JellyfinSession,
    ): AudioPlaybackEngine = AudioPlaybackEngineImpl(
        sourceProvider = sourceProvider,
        playerControl = ExoPlayerControl(exoPlayer),
        metadataProvider = { itemId ->
            playQueue.current.value
                ?.takeIf { it.id == itemId }
                ?.toPlaybackDisplayMetadata(session.cachedBaseUrlOrNull().orEmpty())
        },
    )

    @Provides
    @Singleton
    fun providePreferencesStore(@ApplicationContext context: Context): PreferencesStore = PreferencesStore(context)

    /**
     * 复审(Task 3):[LastPlayedStore] 的 `Context` 构造函数把 preferences 文件名收拢在类内部
     * (`"last_played"`,和 [ServerStore]/[PreferencesStore] 各自的文件互不相同)——这里不能像
     * [ProgressReporter] 那样自己拼一个 `DataStore<Preferences>` 出来,否则文件名会和某个既有
     * store 撞在一起,运行时抛 "multiple DataStores active for the same file"。
     */
    @Provides
    @Singleton
    fun provideLastPlayedStore(@ApplicationContext context: Context): LastPlayedStore = LastPlayedStore(context)

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
    @Singleton
    fun provideProgressReporter(
        api: JellyfinApi,
        dao: ProgressReportDao,
        serverStore: ServerStore,
    ): ProgressReporter {
        val serverId = runBlocking { serverStore.activeServerId.first() } ?: "unknown-server"
        return ProgressReporter(api, dao, serverId)
    }

    /**
     * 闭合复审 Critical 2:[ProgressReporter] 此前只被 provide、没有任何注入点,进度上报的写方向
     * 根本不存在。真正的驱动方是 `PlaybackService`(见其 `observeProgressReporting`),决策逻辑在
     * 这个已单测的协调器里。
     *
     * 必须是 `@Singleton`,而且必须和 `PlaybackService` 的生命周期解耦:Service 可能被销毁后重建
     * (划掉最近任务、系统回收),而 `AudioPlaybackEngine` 是单例、它的 `state` 是 StateFlow ——
     * Service 重建后会立刻重放当前那个 `Ready`。协调器留着"上一次报的是哪个条目"这份状态,才能把
     * 这次重放识别成"同一条目"而发 progress,不会重复发一次 `Sessions/Playing` 凭空多出一个播放会话。
     *
     * 位置 lambda 接的是绝对位置权威(复审 Critical 1)。
     */
    @Provides
    @Singleton
    fun providePlaybackProgressCoordinator(
        reporter: ProgressReporter,
        engine: AudioPlaybackEngine,
    ): PlaybackProgressCoordinator =
        PlaybackProgressCoordinator(reporter) { engine.absolutePositionMs }
}
