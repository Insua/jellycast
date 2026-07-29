package dev.insua.jellycast.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 前台服务「进得去,**也出得来**」的全部决策。纯 Kotlin,不认识 `Service` / `Notification`,
 * 离线可单测(铁律 6)—— `PlaybackService` 在 JVM 单测里造不出来,所以决策必须在这里。
 *
 * ## 它闭合的缺陷:占位通知贴出去之后没有任何一条路能把它收回来
 *
 * `PlaybackService.enterForegroundNow()` 在每一次 `onStartCommand` 都贴一条 `setOngoing(true)`、
 * 没有 `contentIntent` 的「正在准备播放」占位通知。**这条占位通知必须保留** —— 它把前台时限从
 * O(网络) 变成 O(微秒),是"点播放就闪退"那条崩溃链的解(见 `PlaybackService.onStartCommand` 的
 * KDoc)。问题在收尾:
 *
 * 1. `resolve()` 失败时引擎进 [PlaybackEngineState.Error],**播放器状态根本不变**,Media3 于是
 *    永远不会 post 媒体通知来替换这条占位通知。而 `observeProgressReporting()` 只收
 *    `playbackReadyEvents()`,**没有任何地方观察 `Error`**。用户拿到一条划不掉、点不动的
 *    「正在准备播放」,进程还被钉在前台,只有把 App 从最近任务里划掉才消失。
 * 2. `MediaSessionService` 返回 `START_STICKY`。系统重启服务时 `intent == null` —— 什么都没在播,
 *    却照样贴一条占位通知。而且这条路**根本没有 10s 时限要赶**(时限只针对
 *    `startForegroundService()`),进前台纯属有害无益。
 *
 * ## 判据为什么是"播放器手里还有内容吗",而不是"引擎是不是 Error"
 *
 * 用户在**已经有内容在放**的时候点了另一集,那一集解析失败 —— 耳机里还在响,这时候停服务等于把
 * 正在听的东西掐掉。所以 [isPlayerActive] 说了算:生产上它是
 * `exoPlayer.playbackState != Player.STATE_IDLE`。
 *
 * ## 为什么还要一条超时兜底
 *
 * `Error` 这条路盖不住"resolve 卡在一个没有超时的 socket 上"和"压根没人调 `play()`"——
 * 这两种情况引擎**连 `Error` 都不会发**,状态永远停在 `Idle`,占位通知就永远挂着。
 * 所以额外武装一个计时器:占位通知贴出 [idleTimeoutMs] 之后播放器仍然空转,就收摊。
 *
 * [DEFAULT_IDLE_TIMEOUT_MS] 取 60s 是刻意的取舍:端到端里"拿到第一条流并出声"的宽限是 30s
 * (模拟器 + 服务端起转码),真机走公网只会更慢,取它的两倍;而系统的前台时限是 10s,早在这之前
 * 就已经履行完了,这条计时器只管收尾,不影响启动。代价是**一次极慢但最终会成功的启动可能被误杀**
 * —— 相对"进程被无限期钉在前台顶着一条假通知",这个代价可接受。
 */
class ForegroundLifecycleController(
    private val scope: CoroutineScope,
    private val engineState: Flow<PlaybackEngineState>,
    private val isPlayerActive: () -> Boolean,
    private val hooks: Hooks,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    /** 与 Android 打交道的那两件事;生产实现在 `PlaybackService` 里,单测里是计数器。 */
    interface Hooks {
        /** 立刻进前台(贴占位通知,或复用已经在的媒体通知)。 */
        fun enterForeground()

        /** 摘掉通知(`STOP_FOREGROUND_REMOVE`)并停掉服务。 */
        fun dismissNotificationAndStop()
    }

    private var idleGuard: Job? = null
    private var errorWatch: Job? = null

    /** 在 `onCreate` 里调一次:开始观察"解析失败"。 */
    fun start() {
        if (errorWatch != null) return
        errorWatch = scope.launch {
            // drop(1):`StateFlow` 会先把当前值重放给新订阅者,而本 Service 是**新建**的 ——
            // 那个值是上一条命留下的状态,不是订阅之后新发生的失败。拿它去停服务,会在播放还没
            // 开始时就把刚起来的服务停掉。和 [playbackReadyEvents] 的 STATE_REPLAY 是同一条纪律。
            engineState.drop(1).collect { state ->
                if (state is PlaybackEngineState.Error && !isPlayerActive()) tearDown()
            }
        }
    }

    /**
     * 每一次 `onStartCommand` 都要调。[hasIntent] = `intent != null`。
     *
     * `intent == null` 说明这是系统按 `START_STICKY` 重启本服务,不是用户点了播放:不进前台
     * (见类注释第 2 条),但仍然武装兜底计时器 —— 否则一个什么都不干的服务会一直挂在那儿。
     */
    fun onStartCommand(hasIntent: Boolean) {
        if (hasIntent) hooks.enterForeground()
        armIdleGuard()
    }

    /** 在 `onDestroy` 里调:通知由 `onDestroy` 自己收走,这里只要停止一切回调。 */
    fun stop() {
        idleGuard?.cancel()
        errorWatch?.cancel()
        idleGuard = null
        errorWatch = null
    }

    /**
     * 重新武装:每一次启动都把计时拉满,而不是沿用上一次的余额 —— 用户在放着的时候点另一集,
     * 那一集也应该拿到完整的启动宽限。
     */
    private fun armIdleGuard() {
        idleGuard?.cancel()
        idleGuard = scope.launch {
            delay(idleTimeoutMs)
            if (!isPlayerActive()) tearDown()
        }
    }

    private fun tearDown() {
        idleGuard?.cancel()
        idleGuard = null
        hooks.dismissNotificationAndStop()
    }

    companion object {
        /** 见类注释:端到端启动宽限(30s)的两倍。 */
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
    }
}
