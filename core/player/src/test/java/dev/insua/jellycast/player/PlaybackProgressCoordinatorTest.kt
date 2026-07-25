package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
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

    /**
     * Service 被销毁后重建,会重新订阅到引擎那个仍然 `Ready` 的 StateFlow —— 同一条目又"就绪"一次。
     * 此时用户可能已经在这条流里多听了几分钟,若按 `startPositionMs` 上报就会把 Jellyfin 里的进度
     * **往回**冲(比不上报更糟)。必须以实时绝对位置为准。
     */
    @Test fun `同一条目的重复就绪按实时绝对位置上报,不会把进度往回冲`() = runTest {
        val sink = RecordingSink()
        var position = 480_000L
        val c = coordinator(sink) { position }

        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)
        position = 900_000L                             // 用户又听了 7 分钟
        c.onSourceReady(source("ep12"), startPositionMs = 480_000L)

        assertEquals(
            listOf("start ep12 session-ep12 480000", "progress ep12 session-ep12 900000"),
            sink.calls,
        )
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
}
