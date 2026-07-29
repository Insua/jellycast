package dev.insua.jellycast.player

import androidx.media3.common.Player
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 闭合 Task 9/10 遗留的 Important 缺陷:[PlaybackService] 曾经把 [androidx.media3.session.MediaSession]
 * 建在裸 ExoPlayer 上,没有任何拦截层。Media3 默认的 MediaSession 回调会把 seek 家族命令
 * (锁屏拖动进度条、`MediaController.seekTo`、快进快退按钮)直接转发给 `player.seekTo()` ——
 * 而 Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)转码音频流
 * `Accept-Ranges: none`,`player.seekTo()` 在这条流上不可靠。
 *
 * [SeekInterceptingPlayer] 用 `ForwardingPlayer` 包一层,覆写全部"能实际移动播放位置"的 seek
 * 方法,统一委派给 [SeekRouter],绝不调用 `super.xxx()`——结构上杜绝了落到底层 player 字节级
 * seek 的可能。
 *
 * `第一个测试是本 Task 的核心验收项`:证明通过 Player 接口发出的 seek 不落到底层
 * `player.seekTo`,而是走完整链路(SeekInterceptingPlayer -> EngineSeekRouter ->
 * AudioPlaybackEngineImpl -> PlaybackSourceProvider)触发以新位置重新 resolve + prepare。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekInterceptingPlayerTest {

    private fun source(startPositionMs: Long) = PlaybackSource(
        itemId = "ep1",
        mediaSourceId = "ms1",
        streamUrl = "https://nas.example.com/Audio/ep1/universal?startTimeTicks=${startPositionMs * 10_000}",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = "session-1",
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    @Test fun `核心验收 -- 通过 Player 接口发出的 seek 不落到底层 player seekTo,而是触发完整重新 resolve 链路`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            requestedPositions += startPositionMs
            source(startPositionMs)
        }
        val preparedUrls = mutableListOf<String>()
        val playerControl = object : PlayerControl {
            override val currentPositionMs: Long = 0L
            override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) { preparedUrls += url }
            override fun release() {}
        }
        val engine = AudioPlaybackEngineImpl(provider, playerControl)
        engine.play("ep1", "u1", startPositionMs = 0L)

        val router = EngineSeekRouter(engine, this)
        val sessionPlayer = SeekInterceptingPlayer(underlying, router)

        sessionPlayer.seekTo(90_000L)
        advanceUntilIdle()

        verify(exactly = 0) { underlying.seekTo(any()) }
        assertEquals(listOf(0L, 90_000L), requestedPositions)
        assertTrue(preparedUrls.last().contains("startTimeTicks=900000000"), preparedUrls.last())
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    @Test fun `seekTo(long) 路由给 SeekRouter,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekTo(12_345L)

        assertEquals(listOf(12_345L), seen)
        verify(exactly = 0) { underlying.seekTo(any()) }
    }

    @Test fun `seekTo(mediaItemIndex, positionMs) 路由给 SeekRouter,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekTo(0, 7_000L)

        assertEquals(listOf(7_000L), seen)
        verify(exactly = 0) { underlying.seekTo(any(), any()) }
    }

    @Test fun `seekToDefaultPosition 家族路由为 seek 到 0,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekToDefaultPosition()
        sessionPlayer.seekToDefaultPosition(0)

        assertEquals(listOf(0L, 0L), seen)
        verify(exactly = 0) { underlying.seekToDefaultPosition() }
        verify(exactly = 0) { underlying.seekToDefaultPosition(any()) }
    }

    /**
     * 只提供绝对位置、不提供总时长的假 [AbsoluteTimeline]。
     *
     * ⚠️ **重新基线说明(全支线复审 Critical 1)**:下面三个 seekBack/seekForward 测试原来把
     * `underlying.currentPosition` 打桩成 20_000 / 5_000,并断言目标位置由**底层 player 的位置**
     * 算出。那个断言把缺陷写死成了规格:底层 `currentPosition` 是"当前这条转码流里播了多久",
     * 每次 seek/续播换流都从 0 重新开始,不是条目内绝对位置。以它为快进快退起点,从 8:00 续播后
     * 按一下快进就会跳到 0:30(往回 7 分半)。
     *
     * 现在断言的期望值一个都没放松(仍然是 5_000 / 0 / 50_000),但**位置的来源换成了权威的
     * 绝对时间轴**,并且把 `underlying.currentPosition` 打桩成一个明显不同的值——如果实现回退去读
     * 底层位置,这三个测试立刻变红。
     */
    private fun absoluteTimeline(positionMs: Long) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = positionMs
        override fun absoluteDurationMs(): Long? = null
    }

    @Test fun `seekBack 用绝对位置和回退步长算出目标位置再路由,不读底层流内相对位置,不调用底层 seekBack`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 3_000L      // 流内相对位置:不该被用到
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(20_000L))

        sessionPlayer.seekBack()

        assertEquals(listOf(5_000L), seen)
        verify(exactly = 0) { underlying.seekBack() }
    }

    @Test fun `seekBack 不会算出负数位置`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 999_000L     // 流内相对位置:不该被用到
        every { underlying.seekBackIncrement } returns 15_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(5_000L))

        sessionPlayer.seekBack()

        assertEquals(listOf(0L), seen)
    }

    @Test fun `seekForward 用绝对位置和快进步长算出目标位置再路由,不读底层流内相对位置,不调用底层 seekForward`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 3_000L       // 流内相对位置:不该被用到
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(20_000L))

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
        verify(exactly = 0) { underlying.seekForward() }
    }

    /**
     * 复审 Minor:`seekForward()` 没有上界钳制,而 `PlayerViewModel.onSkipForward()` 有
     * (`coerceAtMost(durationMs)`)。两条路必须一致——锁屏/蓝牙耳机的快进键走的是这一条,
     * 在快结尾处按一下就会请求一个**超过条目结尾**的 `startTimeTicks`,服务端拿到它只能给出一条
     * 空流或直接失败。上界取权威总时长(Jellyfin 元数据 `runTimeMs`),不是底层 `duration`
     * (chunked AAC 转码流常是 `C.TIME_UNSET`)。
     */
    @Test fun `seekForward 钳制到条目总时长,不会请求超过结尾的位置`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val timeline = object : AbsoluteTimeline {
            override fun absolutePositionMs(): Long = 1_490_000L      // 距结尾只剩 10 秒
            override fun absoluteDurationMs(): Long = 1_500_000L
        }
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, timeline)

        sessionPlayer.seekForward()

        assertEquals(listOf(1_500_000L), seen)
    }

    /** 总时长未知时(元数据缺失 + 转码流 `duration` 是 `C.TIME_UNSET`)不能钳制成 0,照常快进。 */
    @Test fun `总时长未知时 seekForward 不做钳制`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.seekForwardIncrement } returns 30_000L
        every { underlying.duration } returns androidx.media3.common.C.TIME_UNSET
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, absoluteTimeline(20_000L))

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
    }

    /** 没有接绝对时间轴时(默认 [AbsoluteTimeline.Unknown])必须安全回退到底层 player,而不是报 0。 */
    @Test fun `未接绝对时间轴时回退到底层 player 的位置,不至于把起点当成 0`() {
        val underlying = mockk<Player>(relaxed = true)
        every { underlying.currentPosition } returns 20_000L
        every { underlying.seekForwardIncrement } returns 30_000L
        val seen = mutableListOf<Long>()
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it })

        sessionPlayer.seekForward()

        assertEquals(listOf(50_000L), seen)
    }

    /**
     * 复审发现(Task 11/12 review Finding 1):`ForwardingPlayer.seekToPrevious()` 未被覆写时,
     * 对单条目非直播场景(本项目模型),`BasePlayer.seekToPrevious()`(media3-common 1.10.1
     * 字节码核实)在"没有上一条目 或 currentPosition > maxSeekToPreviousPosition"时会调用
     * `seekToCurrentItem(0, ...)`——对当前条目做一次真正的位置 0 的 seek,直接转发到底层裸
     * ExoPlayer,完全绕开本类的拦截。这正是蓝牙/锁屏"上一曲"键通过 MediaSession 的
     * SEEK_TO_PREVIOUS 命令触发的路径。用和核心验收测试一样的真实
     * AudioPlaybackEngineImpl + 假 PlaybackSourceProvider/PlayerControl 编排,证明
     * seekToPrevious() 也必须经过完整重新 resolve 链路,而不是落到底层 seekToPrevious()。
     */
    @Test fun `seekToPrevious 不落到底层 player seekToPrevious,而是路由为 seek 到 0 并触发完整重新 resolve 链路`() = runTest {
        val underlying = mockk<Player>(relaxed = true)
        val requestedPositions = mutableListOf<Long>()
        val provider = PlaybackSourceProvider { _, _, startPositionMs ->
            requestedPositions += startPositionMs
            source(startPositionMs)
        }
        val preparedUrls = mutableListOf<String>()
        val playerControl = object : PlayerControl {
            override val currentPositionMs: Long = 0L
            override fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?) { preparedUrls += url }
            override fun release() {}
        }
        val engine = AudioPlaybackEngineImpl(provider, playerControl)
        engine.play("ep1", "u1", startPositionMs = 90_000L)

        val router = EngineSeekRouter(engine, this)
        val sessionPlayer = SeekInterceptingPlayer(underlying, router)

        sessionPlayer.seekToPrevious()
        advanceUntilIdle()

        verify(exactly = 0) { underlying.seekToPrevious() }
        assertEquals(listOf(90_000L, 0L), requestedPositions)
        assertTrue(preparedUrls.last().contains("startTimeTicks=0"), preparedUrls.last())
        assertTrue(engine.state.value is PlaybackEngineState.Ready)
    }

    // ---- 设计文档 §3.3:上一集/下一集,按 QueueNavigator 的真实状态加/删命令,委派执行 ----

    private fun fakeNavigator(hasNext: Boolean = false, hasPrevious: Boolean = false) = object : QueueNavigator {
        var nextCalled = false
        var previousCalled = false
        var hasNextQueried = false
        var hasPreviousQueried = false
        override fun hasNext(): Boolean { hasNextQueried = true; return hasNext }
        override fun hasPrevious(): Boolean { hasPreviousQueried = true; return hasPrevious }
        override fun next() { nextCalled = true }
        override fun previous() { previousCalled = true }
    }

    /**
     * ⚠️ v3 复审 Finding 2(已闭合):这里**不**通过构造 `Player.Commands`
     * (`Builder().add(...).build()`)再 `.contains()` 回读来断言 [SeekInterceptingPlayer.getAvailableCommands]
     * 的结果——排查过:`Player.Commands.Builder` 内部用 `android.util.SparseBooleanArray`(真实
     * Android 框架类)存储,在本模块 `testOptions.unitTests.isReturnDefaultValues = true` 的纯 JVM
     * 环境下,它的 `add`/`get` 全是静默空桩(不抛异常,但也不真的存东西),`.build()` 出来的
     * `Commands` 无论加没加过命令,`.contains()` 永远回 `false`——这一层测出来的只会是假阳性/假阴性,
     * 和 `Uri.parse` 是同一类环境限制([PlaybackDisplayMetadata] 的类注释)。
     *
     * 这个测试本身**只**可靠验证**接线**:[SeekInterceptingPlayer.getAvailableCommands] 有没有真的去问
     * [QueueNavigator] 的 `hasNext()`/`hasPrevious()`——单靠它不够,即使 `add`/`remove` 被写反(该加
     * 的时候删、该删的时候加)它也照样通过。真正"加没加对命令"这件事现在由
     * [SeekInterceptingPlayerCommandsTest] 覆盖:借 `tech.apter.junit5.jupiter:robolectric-extension`
     * 把 Robolectric 接进 JUnit5,拿到真实的 `SparseBooleanArray` shadow,直接断言
     * `Player.Commands.contains(...)` 的真实结果,不再需要留到真机/流体云验收。
     */
    @Test fun `getAvailableCommands 会查询 QueueNavigator 的 hasNext 和 hasPrevious`() {
        val underlying = mockk<Player>(relaxed = true)
        val navigator = fakeNavigator(hasNext = true, hasPrevious = false)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        sessionPlayer.availableCommands

        assertTrue(navigator.hasNextQueried, "getAvailableCommands 应该查询 QueueNavigator.hasNext()")
        assertTrue(navigator.hasPreviousQueried, "getAvailableCommands 应该查询 QueueNavigator.hasPrevious()")
    }

    @Test fun `没有接 QueueNavigator 时(默认 None)hasNext hasPrevious 恒为 false,不会抛异常`() {
        val underlying = mockk<Player>(relaxed = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { })

        sessionPlayer.availableCommands // 不应该抛异常

        assertTrue(!QueueNavigator.None.hasNext())
        assertTrue(!QueueNavigator.None.hasPrevious())
    }

    @Test fun `seekToNextMediaItem 委派给 QueueNavigator,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val navigator = fakeNavigator(hasNext = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        sessionPlayer.seekToNextMediaItem()

        assertTrue(navigator.nextCalled)
        verify(exactly = 0) { underlying.seekToNextMediaItem() }
    }

    @Test fun `seekToPreviousMediaItem 委派给 QueueNavigator,不落到底层 player`() {
        val underlying = mockk<Player>(relaxed = true)
        val navigator = fakeNavigator(hasPrevious = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        sessionPlayer.seekToPreviousMediaItem()

        assertTrue(navigator.previousCalled)
        verify(exactly = 0) { underlying.seekToPreviousMediaItem() }
    }

    /**
     * ⚠️ 接上 QueueNavigator 之后必须重新确认:`seekToPrevious()`(不带 MediaItem 后缀)的语义
     * 完全不受影响——它和 `seekToPreviousMediaItem()` 是两个不同的方法、两套不同的语义
     * (类顶部 KDoc:非直播场景下"回到本集开头",不是"切到上一条队列项")。即使队列里确实有上一条
     * (`hasPrevious() == true`),`seekToPrevious()` 也绝不能被新加的队列导航接管。
     */
    @Test fun `新增队列导航后,seekToPrevious 语义不变 —— 仍是 seek 到 0,不会被 QueueNavigator 接管`() {
        val underlying = mockk<Player>(relaxed = true)
        val seen = mutableListOf<Long>()
        val navigator = fakeNavigator(hasPrevious = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { seen += it }, queueNavigator = navigator)

        sessionPlayer.seekToPrevious()

        assertEquals(listOf(0L), seen)
        assertTrue(!navigator.previousCalled, "seekToPrevious()(不带 MediaItem 后缀)不该被队列导航接管")
        verify(exactly = 0) { underlying.seekToPrevious() }
    }
}
