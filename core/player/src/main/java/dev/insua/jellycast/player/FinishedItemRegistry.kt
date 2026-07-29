package dev.insua.jellycast.player

/**
 * 「哪些条目的上报生命周期已经终结」—— 设计文档 §2.3 规则 1 的状态载体。
 *
 * ## 为什么需要它
 *
 * Jellyfin 的实测行为(设计文档 §1.1):一条 `stop` 上报的位置 ≥ 90% 时,服务端把条目标记
 * 为已播放**并把播放位置清零**。但**此后**再收到该条目的任何 `progress`、或一条位置低于 90%
 * 的 `stop`,位置就会被重新写上,而已播放的勾**不会**被撤销。用户看到的是:一集明明播完了、
 * 打了勾,却还带着进度条赖在「继续观看」里。
 *
 * 所以客户端必须自己记住「这一集已经收尾了」,并据此丢弃所有更旧的上报。
 *
 * ## 为什么只在内存里
 *
 * `ProgressReporter` 在发出 `stop` 之前会先 `deleteForItem` 清空该条目的补报队列
 * (设计文档 §2.3 规则 2),因此**持久队列里不可能残留已终结条目的旧记录**。这份标记只需要
 * 覆盖「stop 已发出,但同一条目还有在飞或排队的上报」这个**秒级窗口**,进程重启后无需恢复。
 *
 * ## 为什么有界
 *
 * 一个长时间运行的播放进程会连播很多集,无界的话这张表只增不减。容量 [capacity] 条、按
 * LRU 淘汰:窗口是秒级的,32 条远超任何真实场景下同时处于窗口内的条目数,被淘汰的那些
 * 条目的窗口早已关闭。
 *
 * ## 线程契约
 *
 * 生产上的调用方来自主线程(`PlaybackService` 的 main-looper scope)和 `Dispatchers.IO`
 * (`PlaybackService.onDestroy` 的收尾 stop),所以每个方法都必须自带同步 ——
 * `LinkedHashMap` 本身不是线程安全的。
 */
class FinishedItemRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    /** `accessOrder = true` 即 LRU:`get` 和 `put` 都会把条目移到最新端。 */
    private val finished = object : LinkedHashMap<String, Unit>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > capacity
    }

    /** 该条目已收尾:此后它的 `progress` 与队列里的旧记录一律作废。 */
    @Synchronized
    fun markFinished(itemId: String) {
        finished[itemId] = Unit
    }

    /**
     * 该条目重新开始播放。对应服务端的重看语义 —— 实测已 `Played` 的条目收到一条 `start`
     * 之后,`Played` 会被取消、位置归零,客户端这边的终结标记也必须跟着失效。
     */
    @Synchronized
    fun clearFinished(itemId: String) {
        finished.remove(itemId)
    }

    @Synchronized
    fun isFinished(itemId: String): Boolean = finished[itemId] != null

    private companion object {
        /** 见类注释「为什么有界」。 */
        const val DEFAULT_CAPACITY = 32
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
