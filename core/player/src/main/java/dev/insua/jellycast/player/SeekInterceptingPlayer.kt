package dev.insua.jellycast.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
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
) : SeekRouter {
    override fun seekTo(positionMs: Long) {
        scope.launch { engine.seekTo(positionMs) }
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
) : ForwardingPlayer(player) {

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

    override fun seekBack() {
        val target = (currentPosition - seekBackIncrement).coerceAtLeast(0L)
        seekRouter.seekTo(target)
    }

    override fun seekForward() {
        seekRouter.seekTo(currentPosition + seekForwardIncrement)
    }

    override fun seekToPrevious() {
        seekRouter.seekTo(0L)
    }
}
