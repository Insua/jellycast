package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 全支线复审 Critical 2:[ProgressReporter] 写好了、DI 里也 provide 了,但**没有任何生产调用方**——
 * `POST /Sessions/Playing`、`/Progress`、`/Stopped` 一次都没发出去过,`flushPending()` 也从没被调用,
 * 于是 Room 里的补报队列只进不出、无限增长。设计文档 §3.5/§7 的"进度双向同步"实际只有读方向。
 *
 * 这个类是"什么时候上报什么"的全部决策逻辑,纯 Kotlin、离线可单测(项目铁律 6)。真正的时机来源
 * (引擎状态、10 秒心跳、STATE_ENDED、Service 销毁)在 [PlaybackService] 里接线。
 *
 * **上报的位置必须是绝对位置**(复审 Critical 1):上报流内相对位置会把用户在 Jellyfin 里的观看
 * 记录冲成一个更早的时间点,比不上报更糟。
 */
class PlaybackProgressCoordinatorTest {

    private fun source(itemId: String, sessionId: String? = "session-$itemId") = PlaybackSource(
        itemId = itemId,
        mediaSourceId = "ms",
        streamUrl = "https://nas.example.com/Audio/$itemId/universal",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = sessionId,
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    private class RecordingSink : ProgressSink {
        val calls = mutableListOf<String>()
        var flushCount = 0
        override suspend fun start(itemId: String, sessionId: String?, positionMs: Long) {
            calls += "start $itemId $sessionId $positionMs"
        }
        override suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) {
            calls += "progress $itemId $sessionId $positionMs"
        }
        override suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) {
            calls += "stop $itemId $sessionId $positionMs"
        }
        override suspend fun flushPending() { flushCount++ }
    }

    private fun coordinator(sink: ProgressSink, position: () -> Long) =
        PlaybackProgressCoordinator(sink, position)

    @Test fun `新条目就绪时上报 start,位置是该流在条目内的起始位置`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 480_000L }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)

        assertEquals(listOf("start ep12 session-ep12 480000"), sink.calls)
    }

    @Test fun `每次源就绪都顺带排空一次本地补报队列`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 0L }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)

        assertEquals(1, sink.flushCount)
    }

    /**
     * 同一条目重新 resolve == 一次 seek(转码流 `Accept-Ranges: none`,seek 只能重新起流)。
     * 设计文档 §7 要求"seek 时立即上报",但**不能**重复发 `Sessions/Playing` —— Jellyfin 会把它
     * 当成一个新的播放会话。
     */
    @Test fun `同一条目重新 resolve(seek)时立即上报 progress,不重复 start`() = runTest {
        val sink = RecordingSink()
        var position = 0L
        val c = coordinator(sink) { position }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)
        position = 900_000L                             // seek 完成:引擎报的绝对位置就是新流的起点
        c.onSourceReady(source("ep12"), startPositionMs = 900_000L)

        assertEquals(
            listOf("start ep12 session-ep12 0", "progress ep12 session-ep12 900000"),
            sink.calls,
        )
    }

    // ---- 复审 Finding 2:状态重放绝不能被当成一次新的播放开始 ----

    /**
     * 缺陷现场:`Service.onDestroy` 调 `onPlaybackStopped()` 清掉了 [currentItemId],但引擎那边
     * 仍然是 `Ready`(旧实现没有 `reset()`)。Service 重建后重新订阅引擎状态流,那个陈旧的 `Ready`
     * 被重放进来,撞上"previousItemId == null → start"这条分支,于是给一个**根本没在播的条目**
     * 发了 `Sessions/Playing`,位置还比刚刚报出去的 stop 落后好几分钟。服务端后果:`PlayCount` +1、
     * `Played` 被置回 false、留下一个永远等不到 stop 的幽灵会话。
     *
     * 修正后的不变量:**只有真的开始播放才报 start**。重放只可能来自"播放器已经被
     * `stop()` + `clearMediaItems()` 过"的 Service 重建路径,那时什么都没在播,所以一个字节都不该发。
     */
    @Test fun `状态重放的就绪不上报任何东西,也不排空补报队列`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 480_000L }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L, trigger = PlaybackReadyTrigger.STATE_REPLAY)

        assertEquals(emptyList<String>(), sink.calls)
        // 重放不是一次新的 resolve,它不证明服务器此刻可达,不该拿它当排空补报队列的时机。
        assertEquals(0, sink.flushCount)
    }

    /** 重放之后用户真的点了播放:那才是一次全新的 start,不能因为重放"占了位"而退化成 progress。 */
    @Test fun `状态重放之后真正开始播放仍然报 start`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 0L }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L, trigger = PlaybackReadyTrigger.STATE_REPLAY)
        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)

        assertEquals(listOf("start ep12 session-ep12 480000"), sink.calls)
    }

    /** 重放不得把自己登记成"当前在播条目",否则紧接着的心跳会给一个没在播的条目发 progress。 */
    @Test fun `状态重放之后的心跳什么都不上报`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 480_000L }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L, trigger = PlaybackReadyTrigger.STATE_REPLAY)
        c.onTick()

        assertEquals(emptyList<String>(), sink.calls)
    }

    // ---- playbackReadyEvents:"这是重放还是真的开始播了"的判定 ----

    /**
     * `StateFlow` 的订阅者一上来就会收到当前值。对一个**新建的** Service 来说,这第一个值就是
     * "上一条命留下的状态",不是一次新发生的播放开始——这正是 Finding 2 的入口。这里把这条判定
     * 做成纯 Flow 适配器,于是它可以离线单测(项目铁律 6),而 `PlaybackService` 里只剩接线。
     */
    @Test fun `订阅时就已经是 Ready 的那个值被标成状态重放`() = runTest {
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Ready(source("ep12"), 480_000L))
        val seen = mutableListOf<PlaybackReadyEvent>()
        val job = launch { state.playbackReadyEvents().collect { seen += it } }
        runCurrent()

        state.value = PlaybackEngineState.Ready(source("ep13"), 0L)
        runCurrent()
        job.cancel()

        assertEquals(
            listOf(PlaybackReadyTrigger.STATE_REPLAY, PlaybackReadyTrigger.NEW_PLAYBACK),
            seen.map { it.trigger },
        )
        assertEquals(listOf("ep12", "ep13"), seen.map { it.source.itemId })
        assertEquals(listOf(480_000L, 0L), seen.map { it.startPositionMs })
    }

    /** 正常冷启动:第一个值是 Idle(不产生事件),之后的 Ready 是真正的播放开始。 */
    @Test fun `冷启动时第一个 Ready 是真正的播放开始,不会被误判成重放`() = runTest {
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
        val seen = mutableListOf<PlaybackReadyEvent>()
        val job = launch { state.playbackReadyEvents().collect { seen += it } }
        runCurrent()

        state.value = PlaybackEngineState.Ready(source("ep12"), 0L)
        runCurrent()
        job.cancel()

        assertEquals(listOf(PlaybackReadyTrigger.NEW_PLAYBACK), seen.map { it.trigger })
    }

    @Test fun `非 Ready 状态不产生就绪事件`() = runTest {
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
        val seen = mutableListOf<PlaybackReadyEvent>()
        val job = launch { state.playbackReadyEvents().collect { seen += it } }
        runCurrent()

        state.value = PlaybackEngineState.Error("ep12")
        runCurrent()
        job.cancel()

        assertEquals(emptyList<PlaybackReadyEvent>(), seen)
    }

    @Test fun `onDestroy 传入的位置优先于实时位置(主线程已经先把位置取好了)`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 0L }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)
        c.onPlaybackStopped(positionMs = 512_000L)

        assertEquals(
            listOf("start ep12 session-ep12 480000", "stop ep12 session-ep12 512000"),
            sink.calls,
        )
    }

    @Test fun `换条目时先给上一条目补一个 stop,再给新条目 start`() = runTest {
        val sink = RecordingSink()
        var position = 0L
        val c = coordinator(sink) { position }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)
        position = 1_200_000L
        c.onTick()                                      // 记住 ep12 最后已知的位置
        position = 0L                                   // 播放器已经切到 ep13 的新流
        c.onSourceReady(source("ep13"), startPositionMs = 0L)

        assertEquals(
            listOf(
                "start ep12 session-ep12 0",
                "progress ep12 session-ep12 1200000",
                "stop ep12 session-ep12 1200000",        // 用最后已知位置,不用已经归零的新流位置
                "start ep13 session-ep13 0",
            ),
            sink.calls,
        )
    }

    @Test fun `心跳按绝对位置上报 progress`() = runTest {
        val sink = RecordingSink()
        var position = 480_000L
        val c = coordinator(sink) { position }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)
        position = 490_000L
        c.onTick()

        assertEquals(
            listOf("start ep12 session-ep12 480000", "progress ep12 session-ep12 490000"),
            sink.calls,
        )
    }

    @Test fun `没有条目在播时心跳什么都不上报`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 42L }

        c.onTick()

        assertEquals(emptyList<String>(), sink.calls)
    }

    @Test fun `播放结束上报 stop`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 1_500_000L }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)
        c.onPlaybackStopped()

        assertEquals(
            listOf("start ep12 session-ep12 0", "stop ep12 session-ep12 1500000"),
            sink.calls,
        )
    }

    /** STATE_ENDED 之后 Service 销毁也会走 stop —— 必须幂等,不能重复上报把同一集停两次。 */
    @Test fun `stop 是幂等的,重复调用不重复上报`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 1_500_000L }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)
        c.onPlaybackStopped()
        c.onPlaybackStopped()

        assertEquals(
            listOf("start ep12 session-ep12 0", "stop ep12 session-ep12 1500000"),
            sink.calls,
        )
    }

    /** stop 之后自动连播的下一集是一次全新的 start,而不是被当成"同一条目 seek"。 */
    @Test fun `stop 之后新条目就绪走 start,不走 progress`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 0L }

        c.onSourceReady(source("ep12"), startPositionMs = 0L)
        c.onPlaybackStopped()
        c.onSourceReady(source("ep13"), startPositionMs = 0L)

        assertEquals(
            listOf(
                "start ep12 session-ep12 0",
                "stop ep12 session-ep12 0",
                "start ep13 session-ep13 0",
            ),
            sink.calls,
        )
    }

    /** playSessionId 可能为 null(PlaybackInfo 没返回):照样上报,DTO 里该字段是可空的。 */
    @Test fun `playSessionId 为 null 时照样上报`() = runTest {
        val sink = RecordingSink()
        val c = coordinator(sink) { 0L }

        c.onSourceReady(source("ep12", sessionId = null), startPositionMs = 0L)

        assertEquals(listOf("start ep12 null 0"), sink.calls)
    }

    /**
     * 🔴 设计文档 §1.2 根因 C。`PlaybackService.onDestroy` 在 `Dispatchers.IO` 上调
     * [PlaybackProgressCoordinator.onPlaybackStopped],而其余入口(源就绪、10 秒心跳)全在
     * 主线程 —— `currentItemId` 等可变字段是普通 `var`,两者之间没有 happens-before 边。
     *
     * 判别办法:注意 [PlaybackProgressCoordinator.onPlaybackStopped] 清空 `currentItemId`
     * 这一步在调用 `sink.stop` **之前**、且是同步完成的 —— 让 `sink.stop` 卡住并不能造出心跳
     * 读到陈旧 `currentItemId` 的窗口(那一步早就跑完了)。真正的窗口在**反方向**:心跳先读到
     * `currentItemId`、调用 `sink.progress` 时卡在网络往返里(这一步在心跳内部,清空动作还没
     * 发生),这期间并发的 `onPlaybackStopped` 跑完并把 `stop` 发了出去。等心跳的 `progress`
     * 终于落地,它就晚于 `stop` 到达服务端 —— 一条本该先到的心跳,把已经收尾的进度又盖了回去。
     *
     * 三个入口互斥的话:心跳持有锁、卡在 `sink.progress` 里的这段时间,`onPlaybackStopped` 必须
     * 排队等锁,`sink.stop` 不可能抢在心跳的 `progress` 前面完成 —— 顺序被钉死成
     * `start → progress → stop`。没有互斥的话,`onPlaybackStopped` 不等任何人,`stop` 会抢先
     * 完成,顺序变成 `start → stop → progress`。
     */
    @Test fun `心跳的 progress 请求还在飞行时,并发的收尾请求不能抢先完成`() = runTest {
        val progressGate = CompletableDeferred<Unit>()
        val sink = object : ProgressSink {
            val calls = mutableListOf<String>()
            override suspend fun start(itemId: String, sessionId: String?, positionMs: Long) {
                calls += "start $itemId"
            }
            override suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) {
                progressGate.await()
                calls += "progress $itemId"
            }
            override suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) {
                calls += "stop $itemId"
            }
            override suspend fun flushPending() = Unit
        }
        val c = coordinator(sink) { 2_400_000L }
        c.onSourceReady(source("ep1"), startPositionMs = 0L)

        launch { c.onTick() }
        runCurrent()
        launch { c.onPlaybackStopped() }
        runCurrent()

        progressGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("start ep1", "progress ep1", "stop ep1"), sink.calls)
    }
}
