package dev.insua.jellycast.player

import dev.insua.jellycast.model.PlaybackSource

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
    private var currentItemId: String? = null
    private var currentSessionId: String? = null
    private var lastKnownPositionMs: Long = 0L

    suspend fun onSourceReady(source: PlaybackSource, startPositionMs: Long) {
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
            // 同一条目再次就绪:正常情况是一次 seek(此时绝对位置 == 新流的起始位置)。也可能是
            // Service 重建后重新订阅到引擎那个仍然 Ready 的 StateFlow —— 那时用户可能已经在这条流里
            // 又听了几分钟,报 startPositionMs 会把 Jellyfin 里的进度**往回**冲。所以这里读实时的
            // 绝对位置,两种情形都不会把用户的记录改坏。
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

    suspend fun onTick() {
        val itemId = currentItemId ?: return
        val positionMs = absolutePositionMs()
        lastKnownPositionMs = positionMs
        sink.progress(itemId, currentSessionId, positionMs)
    }

    /**
     * [positionMs] 默认取实时绝对位置。`Service.onDestroy` 必须显式传值:那里读位置只能在主线程
     * 完成(ExoPlayer 的 application thread 限制),而 stop 请求要在一个不会被立即取消的 scope 上
     * 发出去,两件事不在同一个线程上。
     */
    suspend fun onPlaybackStopped(positionMs: Long = absolutePositionMs()) {
        val itemId = currentItemId ?: return
        val sessionId = currentSessionId
        currentItemId = null
        currentSessionId = null
        lastKnownPositionMs = positionMs
        sink.stop(itemId, sessionId, positionMs)
    }
}
