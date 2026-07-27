package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.JellyfinApi
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

/**
 * Finding 1(闭合):Task 21/22 的 `PlaybackService` 在 `Player.STATE_ENDED` 时直接调
 * `ExoPlayerControl(exoPlayer).setMediaItemAndPrepare(next.streamUrl)`,绕开了
 * [AudioPlaybackEngine.play]——engine 内部私有的 `currentItemId`/`currentUserId` 从没更新过。
 * 两个用户可见后果:
 * 1. 自动连播之后锁屏拖进度条 → [AudioPlaybackEngine.seekTo] 拿着**上一集**的 itemId 重新
 *    resolve(这条流 `Accept-Ranges: none`,seek 靠重新 resolve,不是 `player.seekTo`),悄悄跳回
 *    上一集。
 * 2. `MediaControllerPlayerConnection.nowPlaying` 按 `queueItem.id == source.itemId` 匹配,
 *    自动连播后立刻对不上,迷你条/通知栏显示旧数据或者空。
 *
 * 这里在 [AudioPlaybackEngine] 这一层(不需要真实 ExoPlayer/Android Context)复现并验证修复:
 * [PlaybackEndedAdvancer] 必须经 `engine.play(...)`,和 `MediaControllerPlayerConnection.skipToNext()`
 * / `AppSessionViewModel.play()` 走同一条路。
 */
class PlaybackEndedAdvancerTest {

    private fun source(itemId: String) = PlaybackSource(
        itemId = itemId,
        mediaSourceId = "ms",
        streamUrl = "https://nas.example.com/Audio/$itemId/universal",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = null,
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    private class RecordingPlayerControl : PlayerControl {
        val urls = mutableListOf<String>()
        override val currentPositionMs: Long = 0L
        override fun setMediaItemAndPrepare(url: String) {
            urls += url
        }
        override fun release() {}
    }

    @Test fun `STATE_ENDED 自动连播后,seekTo 针对新一集重新 resolve,不再停在旧一集`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val resolvedItemIds = mutableListOf<String>()
        val provider = PlaybackSourceProvider { itemId, _, _ ->
            resolvedItemIds += itemId
            source(itemId)
        }
        val controller = AutoPlayNextController(queue, api, flowOf(true))
        val engine = AudioPlaybackEngineImpl(provider, RecordingPlayerControl())
        val advancer = PlaybackEndedAdvancer(engine, controller) { "u1" }

        engine.play("1", "u1", 0L) // 模拟正在播第 1 集
        resolvedItemIds.clear()

        advancer.onPlaybackEnded() // 模拟 STATE_ENDED,自动连播到第 2 集

        val readyState = engine.state.value as PlaybackEngineState.Ready
        assertEquals("2", readyState.source.itemId, "engine.state 应该已经切到新一集")

        engine.seekTo(5_000L) // 模拟锁屏拖进度条

        assertEquals("2", resolvedItemIds.last(), "seekTo 应该针对新一集重新 resolve,不是停在旧一集的 itemId")
    }

    /**
     * ## 稳定性根因 #4:一次换集只该解析一次播放源
     *
     * [AutoPlayNextController] 原本自己 `resolve()` 一次(只为了回答"要不要连播/连播到哪一条"),
     * 结果被整个丢掉;紧接着 [PlaybackEndedAdvancer] 又经 `engine.play()` 再 resolve 一次才是真正
     * 用来播的那一条。于是**每次换集都白跑一整个 `POST Items/{id}/PlaybackInfo` 往返**,
     * 并在 Jellyfin 上凭空多开一个等不到 stop 的播放会话。
     *
     * "要不要连播"这个决策**根本不需要播放源** —— 它只依赖偏好开关、睡眠定时武装状态和队列。
     * 把 resolve 留给唯一真正需要它的地方([AudioPlaybackEngine.play]),换集路径就只剩一次往返,
     * 而且 engine 的 `currentItemId` 和实际在放的条目依然是同一条(Finding 1 的不变量不受影响)。
     */
    @Test fun `一次自动连播只解析一次播放源`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val resolvedItemIds = mutableListOf<String>()
        val provider = PlaybackSourceProvider { itemId, _, _ ->
            resolvedItemIds += itemId
            source(itemId)
        }
        val controller = AutoPlayNextController(queue, api, flowOf(true))
        val engine = AudioPlaybackEngineImpl(provider, RecordingPlayerControl())
        val advancer = PlaybackEndedAdvancer(engine, controller) { "u1" }

        engine.play("1", "u1", 0L)
        resolvedItemIds.clear()

        advancer.onPlaybackEnded()

        assertEquals(
            listOf("2"),
            resolvedItemIds,
            "一次换集只该解析一次播放源;多出来的那次是白跑的 PlaybackInfo 往返 + 一个等不到 stop 的幽灵会话",
        )
    }

    @Test fun `武装播完本集时,STATE_ENDED 不推进,engine 状态保持当前集`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        val api = mockk<JellyfinApi>()
        val provider = PlaybackSourceProvider { itemId, _, _ -> source(itemId) }
        val controller = AutoPlayNextController(queue, api, flowOf(true))
        val engine = AudioPlaybackEngineImpl(provider, RecordingPlayerControl())
        val advancer = PlaybackEndedAdvancer(engine, controller) { "u1" }

        engine.play("1", "u1", 0L)
        controller.armStopAfterCurrentEpisode()

        advancer.onPlaybackEnded()

        val readyState = engine.state.value as PlaybackEngineState.Ready
        assertEquals("1", readyState.source.itemId, "武装「播完本集」时不应该推进到下一集")
        assertEquals("1", queue.current.value?.id, "队列也不应该被推进")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `advance 进行中重入 STATE_ENDED 不会让队列被重复推进`() = runTest {
        val queue = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2"), ep("3")), 0) }
        val api = mockk<JellyfinApi>()
        val gate = CompletableDeferred<Unit>()
        var gateArmed = true
        val provider = PlaybackSourceProvider { itemId, _, _ ->
            if (itemId == "2" && gateArmed) {
                gateArmed = false
                gate.await() // 模拟 engine.play() 触发的 prepare 还没走完
            }
            source(itemId)
        }
        val controller = AutoPlayNextController(queue, api, flowOf(true))
        val engine = AudioPlaybackEngineImpl(provider, RecordingPlayerControl())
        val advancer = PlaybackEndedAdvancer(engine, controller) { "u1" }

        engine.play("1", "u1", 0L)

        val firstAdvance = launch { advancer.onPlaybackEnded() }
        runCurrent() // 让第一次 advance 跑到卡在 gate 上的地方(队列此时应该已经推进到 "2")

        advancer.onPlaybackEnded() // 重入:模拟第一次还没完成时又收到一次 STATE_ENDED

        assertEquals(
            "2",
            queue.current.value?.id,
            "重入不应该让队列被推进两次(直接跳到 \"3\"),必须被结构性地挡住",
        )

        gate.complete(Unit)
        firstAdvance.join()

        assertEquals("2", queue.current.value?.id)
        val readyState = engine.state.value as PlaybackEngineState.Ready
        assertEquals("2", readyState.source.itemId)
    }
}
