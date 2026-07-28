package dev.insua.jellycast.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "切到上一条/下一条队列项"这件事的最小抽象。[SeekInterceptingPlayer] 只需要知道"有没有
 * 下一条/上一条"和"切过去"——不需要认识 [PlayQueue]/[AudioPlaybackEngine] 的具体实现,
 * 单测里可以直接造假,完全绕开真实播放器。
 *
 * 设计文档 §3.3:不给 Media3 塞假播放列表(播放器每次只 `setMediaItem` 一个条目,假列表会和
 * "seek 靠重新 resolve"这个机制冲突),而是让 [SeekInterceptingPlayer] 按 [PlayQueue] 的真实状态
 * 声明 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`,并把这两个命令
 * 的实际执行委派到这里。
 */
interface QueueNavigator {
    fun hasNext(): Boolean
    fun hasPrevious(): Boolean
    fun next()
    fun previous()

    companion object {
        /** 没有接队列时的空实现:恒无上一条/下一条,调用安全地什么都不做。 */
        val None: QueueNavigator = object : QueueNavigator {
            override fun hasNext(): Boolean = false
            override fun hasPrevious(): Boolean = false
            override fun next() {}
            override fun previous() {}
        }
    }
}

/**
 * 生产环境实现:把"切下一条/上一条"翻译成"[PlayQueue] 推进游标 + [AudioPlaybackEngine.play]
 * 重新播放那一条"——和 `AppSessionViewModel.play()` / [PlaybackEndedAdvancer] 走同一条路,
 * engine 内部的 `currentItemId`/`state` 必须跟着真正在播的条目走,不能被绕开
 * (否则重蹈 [PlaybackEndedAdvancer] 类注释里 Finding 1 的覆辙:锁屏拖进度条会悄悄跳回旧条目)。
 *
 * 先取 [userIdProvider] 再推进队列:拿不到登录用户(未登录/断网)时整个操作是安全的空操作,
 * 不会把队列游标改到一个实际上没有播放的位置——队列和引擎状态因此始终保持同步。
 */
class EngineQueueNavigator(
    private val playQueue: PlayQueue,
    private val engine: AudioPlaybackEngine,
    private val userIdProvider: suspend () -> String?,
    private val scope: CoroutineScope,
) : QueueNavigator {
    override fun hasNext(): Boolean = playQueue.hasNext()
    override fun hasPrevious(): Boolean = playQueue.hasPrevious()

    override fun next() {
        scope.launch {
            val userId = userIdProvider() ?: return@launch
            val item = playQueue.next() ?: return@launch
            engine.play(item.id, userId, item.resumePositionMs)
        }
    }

    override fun previous() {
        scope.launch {
            val userId = userIdProvider() ?: return@launch
            val item = playQueue.previous() ?: return@launch
            engine.play(item.id, userId, item.resumePositionMs)
        }
    }
}
