package dev.insua.jellycast.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "把播放位置移动到 positionMs"这件事的最小抽象。生产实现见 [EngineSeekRouter](委派给
 * [AudioPlaybackEngine.seekTo] —— 重新 resolve + prepare)。单测里可以直接用 lambda 造假,
 * 完全绕开 AudioPlaybackEngine/ExoPlayer。
 */
fun interface SeekRouter {
    fun seekTo(positionMs: Long)
}

/**
 * 生产环境的 [SeekRouter]:把 seek 委派给 [AudioPlaybackEngine.seekTo]。
 *
 * `engine.seekTo` 是 suspend(内部要重新调 [PlaybackSourceProvider.resolve] 换一个新 URL 再
 * setMediaItem + prepare),而 [Player] 接口的 seek 家族方法都是同步调用(通常在应用主线程 /
 * MediaSession 的 Binder 线程上),所以这里用调用方传入的 [scope] 发起协程,不阻塞调用线程。
 */
class EngineSeekRouter(
    private val engine: AudioPlaybackEngine,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : SeekRouter {

    /**
     * ## seek 防抖 / 连点合并
     *
     * 复现见 `SeekCoalescingTest`:连点快进大约 1/7 会弹出「该条目无法播放」,而耳机里其实还在
     * 正常出声。根因不是"迟到的结果覆盖了更新的状态"——那条路已经被
     * [AudioPlaybackEngineImpl] 既有的 `requestSeq`/`isStale` 令牌机制挡住了(见
     * [AudioPlaybackEngineTest] 的"连点 seek 时先发出的慢 resolve 不得覆盖后发出的快
     * resolve"/"过期 seek 失败时不得把已经成功的新一次 seek 打成 Error")。真正的根因是
     * **每一次按键都无条件起一个新的 resolve 请求**,而群晖 J4125 只能扛有限的并发转码探测
     * (`PlaybackSourceResolver`「稳定性根因 #3」的 KDoc)。连点两下快进,**第二下**(真正最新、
     * 按令牌判定完全不 stale 的那一次)可能因为第一下还占着并发名额而竞争失败——它本身就是
     * "最新",令牌机制救不了它,`state` 被打成 Error,而播放器上还在放第一下发起前的那条旧流,
     * 声音完全没有断。
     *
     * 这里在真正调用 [AudioPlaybackEngine.seekTo] 之前,先取消上一次还没落地的请求:如果它还在
     * [debounceMs] 的等待窗口里,直接被取消,连 resolve 都不会发起;如果它已经在 resolve 中,
     * 取消会让协程在下一个挂起点抛 [kotlinx.coroutines.CancellationException],
     * [AudioPlaybackEngineImpl.resolveAndPrepare] 把它原样向上抛出、绝不会落到
     * `catch (e: Exception)` 分支——所以被取消的那次请求**不可能**把 state 打成 Error。
     *
     * 这是在 [AudioPlaybackEngine] 既有的令牌机制之上叠加,不是另起一套并行方案:令牌机制继续
     * 兜底"取消信号还没来得及生效、resolve 已经跑完"那极小的窗口;这一层从根上减少冗余请求
     * 本身,不让陈旧请求有机会去抢并发名额。
     */
    @Volatile
    private var pendingSeek: Job? = null

    override fun seekTo(positionMs: Long) {
        pendingSeek?.cancel()
        pendingSeek = scope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            engine.seekTo(positionMs)
        }
    }

    private companion object {
        /** 连点快进/拖动进度条的合并窗口。 */
        const val DEFAULT_DEBOUNCE_MS = 250L
    }
}

/**
 * 闭合 Task 9/10 遗留的 Important 缺陷:[PlaybackService] 曾经把 `MediaSession` 建在裸
 * ExoPlayer 上,没有任何拦截层。Media3 的默认 MediaSession 回调会把控制器发来的 seek 命令
 * (锁屏拖动进度条、`MediaController.seekTo`、快进快退按钮)直接转发给 `player.seekTo()`——
 * 而 Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)Jellyfin 转码音频流的
 * 响应头是 `Accept-Ranges: none`:服务端根本不支持字节区间取流,`ExoPlayer.seekTo()` 在这种
 * 流上不可靠(会卡住,或者从头播放)。
 *
 * 用 `ForwardingPlayer` 包一层,覆写全部"能实际移动当前条目内播放位置"的方法:
 * `seekTo(Long)` / `seekTo(Int, Long)` / `seekToDefaultPosition()` / `seekToDefaultPosition(Int)`
 * / `seekBack()` / `seekForward()`。这些覆写**从不调用 `super.xxx()`**,只调用 [seekRouter]——
 * 结构上就不可能落到底层 player 的字节级 seek,和 [PlayerControl] 刻意不暴露 `seekTo` 是同一个
 * "结构上杜绝误用"的思路(见 [AudioPlaybackEngine] 的类注释)。
 *
 * `MediaSession.Builder(context, sessionPlayer)` 接的是这个包装后的 Player,不是裸 ExoPlayer——
 * 这样锁屏/通知栏/蓝牙耳机按键发来的 seek,不管走 MediaSession 默认回调还是自定义回调,最终都
 * 只能落到这一层。
 *
 * `seekToPrevious()` **必须覆盖**(复审 Finding 1):对 media3-common 1.10.1 的 `BasePlayer`
 * 字节码核实过——非直播、单条目场景下(本项目模型:没有上一条目),`seekToPrevious()` 总是走
 * "没有上一条目 或 currentPosition > maxSeekToPreviousPosition"分支,调用
 * `seekToCurrentItem(0, ...)`,即对**当前条目**做一次真正的位置 0 的 seek。这正是蓝牙/锁屏
 * "上一曲"键通过 MediaSession 的 `SEEK_TO_PREVIOUS` 命令触发的路径——不覆盖的话它会绕开这一层,
 * 直接落到裸 ExoPlayer。所以和 `seekToDefaultPosition()` 一样,路由为 seek 到 0。
 *
 * 刻意不覆盖 `seekToNextMediaItem()` / `seekToPreviousMediaItem()` / `seekToNext()`:本项目不使用
 * ExoPlayer 自身的多条目播放列表(每次只 `setMediaItem` 一个条目),这三个方法在单条目、非直播
 * 场景下要么是 no-op(没有上一个/下一个条目可切),要么(`seekToNext()`)会被 `BasePlayer` 转成对
 * 当前条目的操作但**不会**触发真正的位置 seek(与 `seekToPrevious()` 的字节码路径不同)。
 * 集与集之间的连播由 [PlayQueue](Task 11)在播放器外部驱动、通过 [AudioPlaybackEngine.play]
 * 重新准备下一条目,和"当前条目内 seek"是两件不同的事。
 */
class SeekInterceptingPlayer(
    player: Player,
    private val seekRouter: SeekRouter,
    private val absoluteTimeline: AbsoluteTimeline = AbsoluteTimeline.Unknown,
) : ForwardingPlayer(player) {

    /**
     * 全支线复审 Critical 1:底层 `currentPosition` 是**流内相对位置**,每次 seek/续播换流都从 0
     * 重新开始。锁屏进度条、蓝牙"快进 30 秒"、`MediaController` 报给 UI 的位置全都读这个方法,
     * 所以这里必须改报 [AbsoluteTimeline] 给的条目内绝对位置——否则从 8:00 续播时锁屏显示 0:00,
     * 按一下快进键会往回跳 7 分半。
     *
     * 下面 [seekBack] / [seekForward] 用的 `currentPosition` 也就是这个覆写(虚方法分派),
     * 于是"所有 seek 起点都过绝对位置"这件事在这一层是结构性的,不靠调用方自觉。
     */
    override fun getCurrentPosition(): Long = absoluteTimeline.absolutePositionMs() ?: super.getCurrentPosition()

    /** 本项目没有广告插播,内容位置恒等于播放位置;`MediaSession` 读的是这个,一并覆写保持一致。 */
    override fun getContentPosition(): Long = absoluteTimeline.absolutePositionMs() ?: super.getContentPosition()

    /**
     * 转码流是 chunked AAC,底层 `duration` 往往是 `C.TIME_UNSET`——锁屏/通知栏拿它当总时长会
     * 让进度条钉在 100%,一拖就把当前集从头开始(复审 Critical 1 的第四条)。权威总时长是
     * Jellyfin 元数据的 `runTimeMs`,由 [AbsoluteTimeline] 提供。
     */
    override fun getDuration(): Long = absoluteTimeline.absoluteDurationMs()?.takeIf { it > 0L } ?: super.getDuration()

    override fun getContentDuration(): Long =
        absoluteTimeline.absoluteDurationMs()?.takeIf { it > 0L } ?: super.getContentDuration()

    override fun seekToDefaultPosition() {
        seekRouter.seekTo(0L)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekRouter.seekTo(0L)
    }

    override fun seekTo(positionMs: Long) {
        seekRouter.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekRouter.seekTo(positionMs)
    }

    // 注意:下面两个方法里的 `currentPosition` 是本类覆写的那个(绝对位置),不是底层 player 的
    // 流内相对位置。这是复审 Critical 1 的修正点——锁屏与蓝牙耳机的快进快退走的就是这条路。
    override fun seekBack() {
        val target = (currentPosition - seekBackIncrement).coerceAtLeast(0L)
        seekRouter.seekTo(target)
    }

    /**
     * 复审 Minor:上界钳制不能省。`PlayerViewModel.onSkipForward()` 一直有 `coerceAtMost(durationMs)`,
     * 这一层却没有——于是锁屏/通知栏/蓝牙耳机的快进键在快结尾处按一下,会请求一个**超过条目结尾**的
     * `startTimeTicks`,服务端只能给出一条空流或直接失败。两条路现在一致了。
     *
     * 上界取本类覆写的 [getDuration](权威值来自 Jellyfin 元数据 `runTimeMs`)。它可能是"未知"——
     * 元数据缺失时会回退到底层 `duration`,而 chunked AAC 转码流的 `duration` 常是 `C.TIME_UNSET`
     * (一个很大的负数)。所以只有在 `> 0` 时才钳制,否则照常快进,绝不会因为"不知道多长"就把目标
     * 位置钳成 0。
     */
    override fun seekForward() {
        val target = currentPosition + seekForwardIncrement
        val durationMs = duration
        seekRouter.seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
    }

    override fun seekToPrevious() {
        seekRouter.seekTo(0L)
    }
}
