package dev.insua.jellycast.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.cache.NetworkTypeMonitor
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.di.ActiveServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 后台播放宿主:承载 [MediaSession],让系统提供锁屏媒体卡片、通知栏控制、蓝牙耳机按键路由,
 * 并在按 Home 键退到后台后继续播放。
 *
 * 铁律提醒:这里创建的 player 来自 [createAudioOnlyPlayer] —— 视频轨在 TrackSelector 层已经
 * 被全局禁用;本类也绝不调用任何 `setVideoSurface*` 方法,不创建 PlayerView,播放页只显示封面。
 *
 * ⚠️ Task 9/10 遗留的 Important 缺陷(已闭合):`MediaSession` **不** 直接建在裸 ExoPlayer 上。
 * Media3 默认回调会把 seek 命令直接转发给 `player.seekTo()`,而 Spike 实测转码流
 * `Accept-Ranges: none`,`seekTo()` 在这条流上不可靠(见 [SeekInterceptingPlayer] 类注释)。
 * 这里用 [SeekInterceptingPlayer] 包一层 ExoPlayer 再交给 `MediaSession.Builder`,所有 seek
 * 家族调用都被结构性地拦截,委派给注入的 [playbackEngine]([AudioPlaybackEngine])。
 *
 * ⚠️ 修正 §1(a)/(b)(已闭合):[exoPlayer] / [playbackEngine] / [autoPlayNextController] /
 * [jellyfinSession] 全部经 Hilt 的 `PlayerModule`(见 `di/PlayerModule.kt`)装配注入,不再是
 * "写好了但没有生产调用方"的死代码——[onCreate] 直接把注入到手的 [playbackEngine] 交给
 * [EngineSeekRouter] / [EngineQueueNavigator](原先经一个可空的 `engine` 字段 + `bindEngine()`
 * 间接绕一手,注入本身已经保证非空,这层间接是多余的,已删除),它就是
 * [Player.STATE_ENDED] 那一刻驱动自动连播的同一个实例,和 `exoPlayer` 也是同一个单例
 * (见 `PlayerModule.provideExoPlayer` 的 KDoc)。
 *
 * ⚠️ Finding 1(已闭合):[Player.STATE_ENDED] 的自动连播曾经直接调
 * `ExoPlayerControl(exoPlayer).setMediaItemAndPrepare(next.streamUrl)`,绕开了
 * [AudioPlaybackEngine.play]——`engine` 内部的 `currentItemId`/`currentUserId` 从没更新过,
 * 自动连播之后锁屏拖进度条会悄悄跳回上一集。现在经 [PlaybackEndedAdvancer] 统一走
 * `engine.play(...)`,和 `MediaControllerPlayerConnection.skipToNext()` / `AppSessionViewModel.play()`
 * 是同一条路,详见该类 KDoc。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var playbackEngine: AudioPlaybackEngine

    @Inject
    lateinit var autoPlayNextController: AutoPlayNextController

    @Inject
    lateinit var playQueue: PlayQueue

    @Inject
    lateinit var jellyfinSession: JellyfinSession

    @Inject
    lateinit var sessionByteCounter: SessionByteCounter

    /**
     * 复审 Critical 2:进度上报的**生产驱动方**。在这之前 [ProgressReporter] 只在 DI 里被 provide,
     * 没有任何注入点,`POST /Sessions/Playing` / `/Progress` / `/Stopped` 一次都没发出去过。
     * 决策逻辑在 [PlaybackProgressCoordinator](已单测),这里只负责提供时机。
     */
    @Inject
    lateinit var progressCoordinator: PlaybackProgressCoordinator

    @Inject
    lateinit var preferencesStore: PreferencesStore

    /**
     * Task 4:「这台设备上次在听什么」的写入方——冷启动时迷你播放条靠它装填(见 [LastPlayedStore]
     * 类注释)。「从当前条目 + 位置构造记录」这段决策抽在纯函数 [buildLastPlayedRecord] 里单测,
     * 这里只负责在心跳里调用它并 [LastPlayedStore.save]。
     */
    @Inject
    lateinit var lastPlayedStore: LastPlayedStore

    /**
     * Task 6:[CachePrefetchController] 编排要用到的几样东西——决策逻辑全在那个类里(它在 JVM
     * 单测里可以完整覆盖),本 Service 只负责在换集时调它一次(见 [observeCachePrefetch]),
     * 以及提供它需要、但只能在这里拿到的几样依赖(Service 生命周期的 `serviceScope`、装配时刻的
     * `serverId` 快照)。
     */
    @Inject
    lateinit var audioCacheStore: AudioCacheStore

    @Inject
    lateinit var networkTypeMonitor: NetworkTypeMonitor

    @Inject
    lateinit var cachedAudioDao: CachedAudioDao

    @Inject
    lateinit var playbackSourceResolver: PlaybackSourceResolver

    @Inject
    lateinit var jellyfinApi: JellyfinApi

    @Inject
    @ActiveServerId
    lateinit var activeServerId: String

    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Handler(Looper.getMainLooper()).asCoroutineDispatcher(),
    )

    /**
     * [serviceScope] 在 [onDestroy] 里必须被取消(否则 10 秒心跳会一直跑),但"最后一次 stop 上报"
     * 得在取消之后仍然能跑完,所以单独留一个不随 Service 生命周期取消的 scope。已知取舍:进程被系统
     * 直接杀掉时这次 stop 会丢——10 秒心跳保证 Jellyfin 里的进度最多落后 10 秒,可接受。
     */
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 复审 Important 3:`exoPlayer` 是 `@Singleton`,**比本 Service 活得久**。所以在 Service 上注册的
     * 监听器必须在 [onDestroy] 里摘掉——否则每次 Service 重建都往同一个播放器上再挂一份,
     * STATE_ENDED 会被处理多次(重复推进队列),流量计数也会被重复累加。
     */
    private var playbackStateListener: Player.Listener? = null
    private var bandwidthListener: AnalyticsListener? = null

    /**
     * 「进前台」和「退前台」的决策全在这里(见 [ForegroundLifecycleController] 类注释)。
     * 本类只提供两件 Android 才做得到的事:贴/摘通知、停服务。
     */
    private var foregroundLifecycle: ForegroundLifecycleController? = null

    private val foregroundHooks = object : ForegroundLifecycleController.Hooks {
        override fun enterForeground() = enterForegroundNow()

        /**
         * 占位通知是本类贴的,只能由本类摘。`STOP_FOREGROUND_REMOVE` 是关键 —— 不带这个标志
         * 通知会留在栏里;而它 `setOngoing(true)` 且没有 `contentIntent`,用户既划不掉也点不动。
         *
         * `stopSelf()` 在有 `MediaController` 绑着的时候不会真的销毁服务(绑定客户端会把它留住),
         * 那也没关系:用户可见的那条假通知已经没了,进程也不再被钉在前台。
         */
        override fun dismissNotificationAndStop() {
            runCatching {
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 唯一的登录用户 id 来源:自动连播(下方 playbackEndedAdvancer)和上一集/下一集
        // (queueNavigator)都要用同一份——读取失败(未登录/断网)时安全地返回 null,
        // 调用方各自静默降级(不连播 / 不切集),不向上抛错(铁律 3/4 的同一条精神)。
        val userIdProvider: suspend () -> String? = { runCatching { jellyfinSession.userId() }.getOrNull() }

        // 生产实现见 EngineSeekRouter 的类注释:连点快进/拖动进度条在这里被合并(seek 防抖),
        // 只有最后一次真正触发 AudioPlaybackEngine.seekTo,避免群晖 J4125 上堆孤儿转码任务
        // (也就避免了"连点后偶发弹出「该条目无法播放」"那个竞态,见 SeekCoalescingTest)。
        val router = EngineSeekRouter(playbackEngine, serviceScope)

        // 设计文档 §3.3:不给 Media3 塞假播放列表(会和"每次重新 resolve"冲突),让
        // SeekInterceptingPlayer 按 PlayQueue 的真实状态声明/执行上一集下一集命令,通知栏/锁屏/
        // 蓝牙/车机才会显示出这两个按钮。
        val queueNavigator = EngineQueueNavigator(
            playQueue = playQueue,
            engine = playbackEngine,
            userIdProvider = userIdProvider,
            scope = serviceScope,
        )

        // 复审 Critical 1:交给 MediaSession 的这个 Player 必须报**条目内绝对位置**和**元数据总时长**。
        // 锁屏/通知栏/蓝牙的进度条与快进快退全部读它;底层 ExoPlayer 报的是转码流内的相对位置
        // (每次 seek/续播归零)和常为 C.TIME_UNSET 的时长,两个都不能直接用。
        // 时长取自 PlayQueue 当前项的 runTimeMs —— PlayQueue 只认识 :core:model 的 MediaItem,
        // 这里没有引入任何 Jellyfin DTO 依赖(设计文档 §5 的模块边界)。
        val sessionPlayer = SeekInterceptingPlayer(
            exoPlayer,
            router,
            playbackEngine.asAbsoluteTimeline { playQueue.current.value?.runTimeMs },
            queueNavigator,
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()

        // 修正 §1(b)/Finding 1:自动连播接到 Player.STATE_ENDED。播完一集后,拿当前登录用户 id 问
        // AutoPlayNextController 要不要接着播下一条——它内部已经处理好"关闭自动连播/播完本集
        // 睡眠定时/队列耗尽回退 NextUp"这些分支,这里只负责把结果喂给 engine,不重复决策逻辑。
        // 必须经 PlaybackEndedAdvancer → engine.play(...),不能直接操作 exoPlayer/ExoPlayerControl——
        // 否则 engine 内部状态和实际在放的条目就对不上了(详见 PlaybackEndedAdvancer 类注释)。
        val playbackEndedAdvancer = PlaybackEndedAdvancer(
            engine = playbackEngine,
            autoPlayNextController = autoPlayNextController,
            userIdProvider = userIdProvider,
        )
        val stateListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                serviceScope.launch {
                    // 顺序要紧:先把这一集的 stop 报出去(此刻绝对位置还是这一集的),再推进队列。
                    // 反过来的话 engine 已经切到下一集,stop 会带着下一集的起点位置发出去。
                    progressCoordinator.onPlaybackStopped()
                    playbackEndedAdvancer.onPlaybackEnded()
                }
            }
        }
        exoPlayer.addListener(stateListener)
        playbackStateListener = stateListener

        // 设置页"开发者信息 → 本次会话已传输字节数"的数据来源(修正 §3)。
        // totalBytesLoaded 是 ExoPlayer 侧的累计值,SessionByteCounter.update 本身也做了
        // "只增不减"的钳制,两者叠加保证这个数字单调递增、不会因为某次回调乱序而跳变变小。
        val analyticsListener = object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                sessionByteCounter.update(totalBytesLoaded)
            }
        }
        exoPlayer.addAnalyticsListener(analyticsListener)
        bandwidthListener = analyticsListener

        observeProgressReporting()
        observePlaybackSpeedPreference()
        observeCachePrefetch(userIdProvider)

        // 占位通知的收尾责任方。判据是"播放器手里还有内容吗" —— `STATE_IDLE` 表示 stop/clear 过或者
        // 从来没 prepare 成功过,也就是"占位通知后面什么都没有"。在主线程 scope 上读播放器,
        // 满足 ExoPlayer 的 application thread 约束。
        foregroundLifecycle = ForegroundLifecycleController(
            scope = serviceScope,
            engineState = playbackEngine.state,
            isPlayerActive = { exoPlayer.playbackState != Player.STATE_IDLE },
            hooks = foregroundHooks,
        ).also { it.start() }
    }

    /**
     * ## 🔴 稳定性根因 #1:前台服务时限不得被网络往返绑架
     *
     * `Context.startForegroundService()` 之后系统只给 **10 秒**,超时就是
     * `ForegroundServiceDidNotStartInTimeException` → `bg anr` → 整个进程 `kill -9`。
     * 用户报的「锁屏就断」「点播放就闪退」都出在这里。
     *
     * 在这一行之前,本 Service **自己从不调 `startForeground()`**,完全指望 Media3 的
     * `MediaNotificationManager` 在"播放器真的开始缓冲"那一刻顺带把服务提到前台。那一刻要等
     * `POST Items/{id}/PlaybackInfo` 往返 + L1 纯音频探测 + ExoPlayer 拉起远端转码流 ——
     * Task 1 在**模拟器 + 局域网**实测 `startForegroundDelayMs:10785`,已经越线;真机走公网只会更慢。
     * 也就是说:**这条时限被网络延迟绑架了,而网络延迟没有上界。**
     *
     * ### 为什么选"立刻进前台 + 占位通知",而不是"把解析搬进 Service"
     *
     * 把解析搬进 Service 只是把同一段网络往返换个地方跑 —— 只要"进前台"这件事仍然排在解析**之后**,
     * 时限就仍然和网络耦合。而在这里进前台,时限从 O(网络) 变成 O(微秒),和服务器多慢、
     * 家宽上行多窄彻底解耦。代价只是可能多显示一秒"正在准备播放",这是诚实的信息。
     * 另外它也不需要动 `AudioPlaybackEngine` / DI 装配 —— 稳定性修复不该顺手做架构重构。
     *
     * ### 为什么用 Media3 的通知 id / channel id
     *
     * [MEDIA_NOTIFICATION_ID] / [MEDIA_CHANNEL_ID] 就是 `DefaultMediaNotificationProvider` 的默认值。
     * 用同一个 id 意味着 Media3 随后 post 的媒体通知是**替换**这条占位通知,而不是并排多出一条;
     * 用同一个 channel 意味着用户在系统设置里只看到一个「正在播放」通道。
     *
     * ### 为什么要先看 `activeNotifications`
     *
     * 用户在已经有内容在播时点另一集,会再走一次 `startForegroundService`。这时如果无脑贴占位通知,
     * 锁屏卡片会从完整的媒体卡片闪回"正在准备播放"再闪回去。所以:媒体通知已经在了就拿它自己
     * 重新 `startForeground` 一次 —— 视觉上零变化,而"进前台"这个义务照样当场履行完。
     *
     * 这里也一并修掉 Task 1 发现的第二个事实:一个进程里只出现过一次 `am_foreground_service_start`。
     * Service 被销毁重建后 Media3 再也没把它提到前台。现在**每一次带 intent 的** `onStartCommand`
     * 都会进前台,不依赖 Media3 内部任何状态。
     *
     * ### ⚠️ 复审 Important 1(已闭合):进得去也得出得来
     *
     * `intent == null` 说明这是系统按 `START_STICKY` 重启本服务 —— 什么都没在播,而且这条路
     * **根本没有 10s 时限要赶**(时限只针对 `startForegroundService()`)。此时贴占位通知等于凭空
     * 造一条幽灵通知。判定连同"解析失败 / 长时间空转就收摊"一起交给
     * [ForegroundLifecycleController](纯 Kotlin,已单测),本方法只负责把事实告诉它。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foregroundLifecycle?.onStartCommand(hasIntent = intent != null)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun enterForegroundNow() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val existing = runCatching {
            manager.activeNotifications.firstOrNull { it.id == MEDIA_NOTIFICATION_ID }?.notification
        }.getOrNull()

        // 铁律 3/4 的同一条精神:进前台失败也绝不把播放搞崩。真进不去,最坏是回到修复前的行为,
        // 而不是在这里抛一个没人接得住的异常。
        runCatching {
            ServiceCompat.startForeground(
                this,
                MEDIA_NOTIFICATION_ID,
                existing ?: preparingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
    }

    /** 占位通知:只在"媒体通知还没被 Media3 贴出来"的那一小段窗口里出现。 */
    private fun preparingNotification(): Notification =
        NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(PREPARING_TITLE)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /**
     * 复审 Critical 2:进度上报的两个时机。
     *
     * 1. **新源就绪** —— 引擎每进入 `Ready` 就是"首播 / 换集 / seek 后重新 resolve"三者之一。
     *    [PlaybackProgressCoordinator] 负责区分该发 start / progress / stop+start,并顺带调
     *    `flushPending()` 排空离线积压的补报队列(会话刚 resolve 成功,说明服务器此刻可达)。
     *    ⚠️ 复审 Finding 2:必须经 [playbackReadyEvents] 而不是直接 `collect` —— 本 Service 是**新建**
     *    的,`StateFlow` 会先把"上一条命留下的状态"重放给它,那不是一次新的播放开始,当成 start 报
     *    出去就会给一个没在播的条目开一个幽灵会话。
     * 2. **10 秒心跳** —— 设计文档 §7 的"每 10s 或 seek 时"。只在真正在播时上报:暂停期间反复上报
     *    同一个位置没有意义,还会白耗电和流量。
     *
     * 上报的位置一律来自 `AudioPlaybackEngine.absolutePositionMs`(复审 Critical 1):上报转码流内的
     * 相对位置会把用户在 Jellyfin 里的记录冲成一个更早的时间点,比不上报更糟。
     */
    private fun observeProgressReporting() {
        serviceScope.launch {
            playbackEngine.state.playbackReadyEvents().collect { event ->
                progressCoordinator.onSourceReady(event.source, event.startPositionMs, event.trigger)
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_INTERVAL_MS)
                if (exoPlayer.isPlaying) {
                    progressCoordinator.onTick()
                    playQueue.current.value?.let { item ->
                        persistLastPlayed(item, playbackEngine.absolutePositionMs)
                    }
                }
            }
        }
    }

    /**
     * Task 6:预取控制器的**唯一**驱动时机——[PlayQueue.current] 只在真的换了条目
     * ([PlayQueue.setQueue]/[PlayQueue.next]/[PlayQueue.previous])时变化,精确对应"换集"这件事
     * 本身,不会被 seek 误触发(即使是 carry-forward 之二修好之后、本地缓存源 seek 也会重新发一次
     * `Ready` 的那种情况——那条信号走的是 [AudioPlaybackEngine.state],不是这里)。
     *
     * [CachePrefetchController] 不能整个交给 Hilt 装配(它需要这个 Service 的 `serviceScope`,
     * 生命周期必须和播放会话绑在一起——Service 销毁时 `serviceScope` 被取消,飞行中的预取下载
     * 随之被取消并清理半成品文件),所以在这里手动 `new`,和 [EngineSeekRouter] /
     * [EngineQueueNavigator] 是同一种取舍。
     */
    private fun observeCachePrefetch(userIdProvider: suspend () -> String?) {
        val cachePrefetchController = CachePrefetchController(
            cacheDao = cachedAudioDao,
            cacheStore = audioCacheStore,
            networkTypeMonitor = networkTypeMonitor,
            downloadSourceProvider = playbackSourceResolver.asProvider(),
            api = jellyfinApi,
            serverId = activeServerId,
            userIdProvider = userIdProvider,
            scope = serviceScope,
            // Task 7:接住 CachePrefetchController 类注释「maxBytes」里留的接缝——读用户在设置页
            // 选的存储上限,不再用构造默认值(DEFAULT_CACHE_MAX_BYTES)。读取失败时 safeMaxBytes()
            // 已经会退回保守默认值,这里不需要重复处理异常。
            maxBytesProvider = { preferencesStore.cacheMaxBytes.first() },
        )
        serviceScope.launch {
            playQueue.current.collect { item -> item?.let(cachePrefetchController::onItemChanged) }
        }
    }

    /**
     * 铁律:写入失败绝不打断播放。和 [ProgressReporter] 里的写法同形——`runCatching` 会连
     * [CancellationException] 一起吞掉,导致取消信号在这里被吸收、协程无法正常 unwind,所以
     * 这里显式重抛它,只吞掉其余异常(存储写坏 / 磁盘满等)。
     */
    private suspend fun persistLastPlayed(item: MediaItem, positionMs: Long) {
        try {
            lastPlayedStore.save(buildLastPlayedRecord(item, positionMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:见上方注释,这条记录只是冷启动时的便利功能,丢一次不影响播放。
        }
    }

    /**
     * 复审 Important 5:`playbackSpeed` 偏好此前只被写入和显示,没有任何地方把它应用到播放器上——
     * 设置里调了倍速对正在播放的内容毫无影响,重启后也总是回到 1.0x(设计文档 §3.5 要求"记忆上次设置")。
     *
     * 放在这里而不是 ViewModel:这是唯一同时满足"进程内只有一处"和"播放器一存在就生效"的地方,
     * 而且设置页改偏好会立刻反映到正在播的内容上。播放页的 `onCycleSpeed` 写偏好,这条流再把它应用
     * 下去,两条路殊途同归。
     */
    private fun observePlaybackSpeedPreference() {
        serviceScope.launch {
            preferencesStore.playbackSpeed.collect { speed ->
                exoPlayer.setPlaybackSpeed(speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * ⚠️ 复审 Important 3(已闭合):这里以前调的是 `mediaSession.player.release()` —— 把 Hilt 的
     * **`@Singleton` ExoPlayer 释放掉了**。触发路径很常见:暂停 → 从最近任务里划掉 App →
     * `MediaSessionService.onTaskRemoved` 在"没在播放"时 stopSelf → 播放器被释放 → 进程还活着的时候
     * 再打开 App → Hilt 注入的还是那个**已释放**的播放器 → `setMediaItem/prepare` 抛
     * `IllegalStateException` → 被 `AudioPlaybackEngineImpl` 静默转成「该条目无法播放」。用户看到的是
     * 一个和事实无关的错误提示,而且必须强制停止 App 才能恢复。
     *
     * **决定:释放 MediaSession,不释放这个单例播放器。** 理由:
     * - 这个播放器**必须**是单例(`PlayerModule.provideExoPlayer` 的 KDoc):引擎切 URL 和锁屏看到的
     *   播放状态必须是同一个播放器。把它改成 Service 作用域就得连引擎、`MediaControllerPlayerConnection`
     *   一起改成 Service 作用域,那是整条播放链路的重构,不是这次修正该做的事。
     * - 播放器对象本身很轻,重的是解码器和缓冲区——`stop()` + `clearMediaItems()` 已经把它们放掉了,
     *   留下的只是一个空闲、可复用的播放器实例,而不是一个不可用的死对象。
     * - 真正的资源回收交给进程结束。这是"宁可多留一个空闲播放器,也不要留一个死播放器"的取舍。
     *
     * 相应地,注册在这个长寿播放器上的监听器必须在这里摘掉,否则 Service 重建会叠加多份。
     */
    override fun onDestroy() {
        // 位置只能在主线程读(ExoPlayer 的 application thread 限制),所以先取好值再交给
        // shutdownScope 去发请求——serviceScope 马上就要被取消了,在它上面 launch 会被立刻丢弃。
        val finalPositionMs = playbackEngine.absolutePositionMs
        val finalItem = playQueue.current.value
        val coordinator = progressCoordinator
        shutdownScope.launch { coordinator.onPlaybackStopped(finalPositionMs) }
        // Task 4:收尾时再写一次最终位置,和心跳同一条纯函数、同一套"写入失败不打断"的处理。
        finalItem?.let { item -> shutdownScope.launch { persistLastPlayed(item, finalPositionMs) } }

        playbackStateListener?.let(exoPlayer::removeListener)
        bandwidthListener?.let(exoPlayer::removeAnalyticsListener)
        playbackStateListener = null
        bandwidthListener = null

        // 停止取流、放掉解码器与缓冲,但**不** release() —— 见上面的决定。
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        // ⚠️ 复审 Finding 2:播放器刚被清空,引擎手里那个 `startPositionMs` 流基准和 `Ready` 状态就都
        // 过期了。不清掉的话:(a) `absolutePositionMs` 会衰减成"旧流起始位置 + 0",一个几分钟前的值;
        // (b) Service 重建后订阅 `state` 会被重放到那个陈旧的 `Ready`,进度协调器把它当成新的播放开始,
        // 给一个根本没在播的条目发 `Sessions/Playing`。位置已经在本方法开头取好了,这里清是安全的。
        playbackEngine.reset()

        mediaSession?.release()
        mediaSession = null
        foregroundLifecycle?.stop()
        foregroundLifecycle = null
        serviceScope.cancel()
        // 占位通知是本类自己贴的,也必须由本类自己收走 —— 否则 Service 销毁后通知栏会留下一条
        // 点不动的"正在准备播放"。
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private companion object {
        /**
         * 必须和 `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID` /
         * `DEFAULT_CHANNEL_ID` 一致 —— 见 [onStartCommand] 的说明。这两个常量在 Media3 里是
         * public,但刻意不直接引用:直接引用会让"占位通知"这件事看起来像是 Media3 的一部分,
         * 而它其实是本类为了不被系统杀掉而必须自己承担的责任。
         */
        const val MEDIA_NOTIFICATION_ID = 1001
        const val MEDIA_CHANNEL_ID = "default_channel_id"
        const val NOTIFICATION_CHANNEL_NAME = "正在播放"
        const val PREPARING_TITLE = "正在准备播放"

        /** 设计文档 §7:进度上报"每 10s 或 seek 时"。 */
        const val PROGRESS_TICK_INTERVAL_MS = 10_000L

        /** 设计文档 §3.5:倍速 0.5x – 3.0x。 */
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 3.0f
    }
}
