package dev.insua.jellycast.player

import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进度上报的窄接口。生产实现是 [ProgressReporter](它同时负责失败入队与补报),这里抽出接口只为了
 * 让 [PlaybackProgressCoordinator] 的"什么时候上报什么"这部分决策逻辑能完全脱离 Retrofit/Room
 * 单测(项目铁律 6)。
 */
interface ProgressSink {
    suspend fun start(itemId: String, sessionId: String?, positionMs: Long)
    suspend fun progress(itemId: String, sessionId: String?, positionMs: Long)
    suspend fun stop(itemId: String, sessionId: String?, positionMs: Long)
    suspend fun flushPending()
}

/**
 * 一次"源就绪"到底是什么(复审 Finding 2)。
 *
 * 靠"当前有没有条目在播"([PlaybackProgressCoordinator] 的 `currentItemId` 是不是 null)去**猜**
 * 这件事是不够的:Service 销毁时 `onPlaybackStopped()` 把它清成了 null,于是 Service 重建后被
 * `StateFlow` 重放进来的那个陈旧 `Ready` 会撞进"null → 这是一次新播放 → 发 start"的分支,给一个
 * 根本没在播的条目开一个幽灵会话。所以调用方必须**明确说明**这次就绪是哪一种。
 */
enum class PlaybackReadyTrigger {
    /** 真的有一条新流被 prepare 了:首播 / 换集 / seek 后重新 resolve。 */
    NEW_PLAYBACK,

    /**
     * 只是 `StateFlow` 把"上一条命留下的状态"重放给了新的订阅者(Service 重建)。此刻播放器已经被
     * `stop()` + `clearMediaItems()` 过,什么都没在播。
     */
    STATE_REPLAY,
}

/** 一次值得上报的"源就绪"事件,由 [playbackReadyEvents] 从引擎状态流里提炼出来。 */
data class PlaybackReadyEvent(
    val source: PlaybackSource,
    val startPositionMs: Long,
    val trigger: PlaybackReadyTrigger,
)

/**
 * 把引擎状态流转成进度上报要的就绪事件流,并把"这是重放还是真的开始播了"这件事判定出来
 * (复审 Finding 2)。
 *
 * 判定规则:`StateFlow` 的新订阅者一上来就会收到**当前值**。对一个刚创建的 `PlaybackService` 来说,
 * 这第一个值必然是"上一条命留下的状态",不是订阅之后新发生的播放开始 —— 所以第一个值一律标成
 * [PlaybackReadyTrigger.STATE_REPLAY],之后的每一次转换才是 [PlaybackReadyTrigger.NEW_PLAYBACK]。
 *
 * 为什么"第一个值是 Ready"不可能是一次真播放:Service 一定在播放开始**之前**就存在了——
 * `AppSessionViewModel.play()` 先 `startForegroundService(...)` 才调 `engine.play(...)`,而
 * `engine.play` 中间还夹着 `PlaybackInfo` 请求与 L1 探测两次网络往返;并且 `PlaybackService.onDestroy`
 * 一定会 `stop()` + `clearMediaItems()`,所以被重放的 `Ready` 背后从来没有正在响的音频。
 * (再加一层保险:`onDestroy` 现在还会调 `AudioPlaybackEngine.reset()`,连这个陈旧的 `Ready` 都不会
 * 留下来——见该方法 KDoc。)
 *
 * 做成 `Flow` 适配器而不是写在 `PlaybackService` 里,是为了让这条判定可以离线单测(项目铁律 6):
 * Service 本身在 JVM 单测里造不出来。
 */
fun Flow<PlaybackEngineState>.playbackReadyEvents(): Flow<PlaybackReadyEvent> = flow {
    var isFirstValue = true
    collect { state ->
        val trigger = if (isFirstValue) PlaybackReadyTrigger.STATE_REPLAY else PlaybackReadyTrigger.NEW_PLAYBACK
        isFirstValue = false
        if (state is PlaybackEngineState.Ready) {
            emit(PlaybackReadyEvent(state.source, state.startPositionMs, trigger))
        }
    }
}

/**
 * 「什么时候上报什么」的全部决策。闭合全支线复审 Critical 2:[ProgressReporter] 此前**没有任何
 * 生产调用方**,`POST /Sessions/Playing` / `/Progress` / `/Stopped` 一次都没发出去过,
 * `flushPending()` 也从没被调用——Room 里的补报队列只进不出。设计文档 §3.5/§7 的"进度双向同步"
 * 实际只有读方向,成功标准 #3(手机听到 8:00,换电视接着看)不成立。
 *
 * 时机由 [PlaybackService] 提供:
 * - [onSourceReady] ← 引擎进入 `Ready`(首播 / 换集 / seek 后重新 resolve)
 * - [onTick] ← 播放中每 10 秒一次(设计文档 §7)
 * - [onPlaybackStopped] ← `Player.STATE_ENDED` 与 `Service.onDestroy`
 *
 * ## 怎么做到既不重复上报、又不漏报
 *
 * - **不重复 start**:同一条目重新 resolve 只可能是一次 seek(转码流 `Accept-Ranges: none`,
 *   seek 只能重新起流),这时发 `progress` 而不是再发一次 `Sessions/Playing` —— 后者会被 Jellyfin
 *   当成一个新的播放会话。
 * - **不漏 stop**:换条目时先替上一条目补一个 `stop`。用的是 [lastKnownPositionMs](上一次心跳/
 *   就绪时记下的位置),不是当下的 [absolutePositionMs] —— 观察到新 `Ready` 时播放器已经切到新流,
 *   现问位置只会得到新集的起点。
 * - **stop 幂等**:`STATE_ENDED` 之后 `onDestroy` 也会调 [onPlaybackStopped],用"清空
 *   [currentItemId]"实现一次性,不会把同一集停两次。
 *
 * ## 上报的位置一定是绝对位置
 *
 * [absolutePositionMs] 必须接 `AudioPlaybackEngine.absolutePositionMs`(复审 Critical 1)。上报流内
 * 相对位置会把用户在 Jellyfin 里的观看记录冲成一个更早的时间点,**比不上报更糟**。
 */
class PlaybackProgressCoordinator(
    private val sink: ProgressSink,
    private val absolutePositionMs: () -> Long,
) {
    /**
     * 设计文档 §1.2 根因 C / §2.5:三个入口互斥。
     *
     * 缺陷现场:`PlaybackService.onDestroy` 在 `Dispatchers.IO` 上调 [onPlaybackStopped],
     * 其余入口(源就绪、10 秒心跳)全在主线程,而下面这三个字段是普通 `var` —— 两者之间
     * 没有 happens-before 边,IO 线程可能读到陈旧值。更要紧的是三个入口各自的方法体里都
     * 包含一次挂起的网络请求(`sink.start` / `progress` / `stop`),互相之间没有互斥的话,
     * 两个入口的请求可能并发在飞行、以任意顺序落地服务端 —— 心跳的 `progress` 完全可能晚于
     * 收尾的 `stop` 到达,把已经收尾的进度又盖了回去,这正是把进度写回已播完条目的另一条路径。
     *
     * 这把锁同时解决可见性和这个乱序窗口。代价:心跳可能要等一次源就绪或收尾请求的网络往返
     * (受 OkHttp 超时上界约束)。心跳晚几秒没有任何用户可见后果,而乱序落地的那条 `progress` 有。
     */
    private val mutex = Mutex()

    private var currentItemId: String? = null
    private var currentSessionId: String? = null
    private var lastKnownPositionMs: Long = 0L

    /**
     * [trigger] 默认是 [PlaybackReadyTrigger.NEW_PLAYBACK] —— "有人告诉我一条新流就绪了"的常态。
     * 生产调用方([PlaybackService])用 [playbackReadyEvents] 提供准确的 trigger。
     */
    suspend fun onSourceReady(
        source: PlaybackSource,
        startPositionMs: Long,
        trigger: PlaybackReadyTrigger = PlaybackReadyTrigger.NEW_PLAYBACK,
    ) = mutex.withLock {
        // 复审 Finding 2 的不变量:**只有真的开始播放才上报**。状态重放背后没有任何音频在响
        // (播放器早已 stop() + clearMediaItems(),见 PlaybackReadyTrigger.STATE_REPLAY),所以这里
        // 一个字节都不发,也刻意**不**登记 currentItemId —— 否则紧接着的心跳会给一个没在播的条目
        // 发 progress,而之后用户真的点播这一集时又会退化成 progress、拿不到 start。
        // 也不调 flushPending():重放不是一次新的 resolve,它不证明服务器此刻可达。
        if (trigger == PlaybackReadyTrigger.STATE_REPLAY) return@withLock

        val previousItemId = currentItemId
        val previousSessionId = currentSessionId
        val previousPositionMs = lastKnownPositionMs

        currentItemId = source.itemId
        currentSessionId = source.playSessionId

        // resolve 成功 == 服务器此刻可达,是排空离线期间积压的补报队列最自然的时机。
        sink.flushPending()

        when (previousItemId) {
            null -> {
                lastKnownPositionMs = startPositionMs
                sink.start(source.itemId, source.playSessionId, startPositionMs)
            }
            // 同一条目再次就绪 == 一次 seek(转码流 `Accept-Ranges: none`,seek 只能重新起流)。
            // 发 progress 而不是再发一次 Sessions/Playing —— 后者会被 Jellyfin 当成新的播放会话。
            //
            // ⚠️ 这里读的是**实时**绝对位置,不是 startPositionMs。复审指出旧注释拿"Service 重建后的
            // 状态重放"当理由,而那条路径根本走不到这个分支(重建时 currentItemId 已被
            // onPlaybackStopped 清空,而且现在重放会被 STATE_REPLAY 直接挡掉),那个理由已经删掉。
            // 留着读实时位置的真实理由:seek 刚完成时它就等于新流的起始位置,而如果观察到这次就绪
            // 之前播放器已经又走了一点,它比 startPositionMs 更准。两者永远不会更早,不可能把用户在
            // Jellyfin 里的记录往回冲。
            source.itemId -> {
                val positionMs = absolutePositionMs()
                lastKnownPositionMs = positionMs
                sink.progress(source.itemId, source.playSessionId, positionMs)
            }
            else -> {
                lastKnownPositionMs = startPositionMs
                sink.stop(previousItemId, previousSessionId, previousPositionMs)
                sink.start(source.itemId, source.playSessionId, startPositionMs)
            }
        }
    }

    suspend fun onTick() = mutex.withLock {
        val itemId = currentItemId ?: return@withLock
        val positionMs = absolutePositionMs()
        lastKnownPositionMs = positionMs
        sink.progress(itemId, currentSessionId, positionMs)
    }

    /**
     * [positionMs] 默认取实时绝对位置。`Service.onDestroy` 必须显式传值:那里读位置只能在主线程
     * 完成(ExoPlayer 的 application thread 限制),而 stop 请求要在一个不会被立即取消的 scope 上
     * 发出去,两件事不在同一个线程上。
     *
     * 默认参数 `absolutePositionMs()` 在锁外求值 —— 这是对的:读播放器位置必须发生在调用方自己的
     * 线程上(ExoPlayer 的 application thread 约束),`PlaybackService.onDestroy` 正是先在主线程
     * 取好值再显式传进来,不依赖这里的默认参数。
     */
    suspend fun onPlaybackStopped(positionMs: Long = absolutePositionMs()) = mutex.withLock {
        val itemId = currentItemId ?: return@withLock
        val sessionId = currentSessionId
        currentItemId = null
        currentSessionId = null
        lastKnownPositionMs = positionMs
        sink.stop(itemId, sessionId, positionMs)
    }
}
