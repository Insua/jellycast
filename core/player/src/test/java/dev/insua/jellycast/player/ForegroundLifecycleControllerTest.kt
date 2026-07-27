package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * # 幽灵通知:占位通知贴出去之后,没有任何一条路能把它收回来
 *
 * `PlaybackService.enterForegroundNow()` 在**每一次** `onStartCommand` 都会贴一条
 * `setOngoing(true)`、没有 `contentIntent` 的「正在准备播放」占位通知(这是为了把前台时限从
 * O(网络) 变成 O(微秒),必须保留)。但收走它的地方只有 `onDestroy` 一处,而:
 *
 * 1. `resolve()` 失败时引擎进 `Error`,播放器状态**根本不变** —— Media3 永远不会 post 媒体通知来
 *    替换这条占位通知。用户拿到的是一条**划不掉、点不动**的通知,进程还被钉在前台,只有把 App
 *    从最近任务里划掉才消失。而 `observeProgressReporting()` 只收 `playbackReadyEvents()`,
 *    **没有任何地方观察 `Error`**。
 * 2. `MediaSessionService` 返回 `START_STICKY`,系统重启服务时 `intent == null` —— 什么都没在播,
 *    却照样贴一条「正在准备播放」。
 *
 * 本测试覆盖的就是这两条,外加一条兜底:占位通知贴出很久之后播放器仍然空转(resolve 卡死、
 * 或者根本没人调 `play()`),也必须自己收摊,而不是无限期占着前台。
 *
 * 判据统一是 [ForegroundLifecycleController.isPlayerActive] —— 「播放器手里还有内容吗」。
 * 用它而不是"引擎状态是不是 Error":用户在**已经有内容在放**的时候点了另一集,那一集解析失败,
 * 耳机里还在响,这时候停掉服务等于把正在听的东西掐掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundLifecycleControllerTest {

    private class RecordingHooks : ForegroundLifecycleController.Hooks {
        var enterForegroundCount = 0
        var dismissCount = 0
        override fun enterForeground() { enterForegroundCount++ }
        override fun dismissNotificationAndStop() { dismissCount++ }
    }

    private fun readySource() = PlaybackSource(
        itemId = "ep1",
        mediaSourceId = "ms1",
        streamUrl = "https://nas.example.com/Audio/ep1/universal",
        level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        isHls = false,
        playSessionId = "s1",
        audioTracks = emptyList(),
        textSubtitles = emptyList(),
    )

    // ---------------------------------------------------------------- 进前台

    @Test
    fun `收到真实 intent 时立刻进前台`() = runTest {
        val hooks = RecordingHooks()
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = MutableStateFlow(PlaybackEngineState.Idle),
            isPlayerActive = { false },
            hooks = hooks,
        )

        controller.onStartCommand(hasIntent = true)

        assertEquals(1, hooks.enterForegroundCount, "有 intent 说明是真的有人要播,必须当场进前台")
    }

    @Test
    fun `START_STICKY 重启送来的 null intent 不得贴占位通知`() = runTest {
        val hooks = RecordingHooks()
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = MutableStateFlow(PlaybackEngineState.Idle),
            isPlayerActive = { false },
            hooks = hooks,
        )

        // 生产上就是 `onStartCommand(intent = null, ...)`:系统重启粘性服务时不带 intent。
        controller.onStartCommand(hasIntent = false)

        assertEquals(
            0,
            hooks.enterForegroundCount,
            "intent 为 null = 系统按 START_STICKY 重启本服务,什么都没在播。" +
                "此时贴「正在准备播放」就是凭空造一条幽灵通知,而且系统这一路根本没有 10s 时限要赶。",
        )
    }

    // ---------------------------------------------------------------- resolve 失败

    @Test
    fun `解析失败且播放器空转时,占位通知必须被摘掉并停掉服务`() = runTest {
        val hooks = RecordingHooks()
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = state,
            isPlayerActive = { false },
            hooks = hooks,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)
        runCurrent()

        state.value = PlaybackEngineState.Error("ep1")
        runCurrent()

        assertEquals(
            1,
            hooks.dismissCount,
            "resolve 失败时播放器状态不变,Media3 永远不会 post 媒体通知来替换占位通知 —— " +
                "不在这里收走,用户就得到一条划不掉的「正在准备播放」。",
        )
    }

    @Test
    fun `已经有内容在放时,一次失败的解析不得停掉服务`() = runTest {
        val hooks = RecordingHooks()
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Ready(readySource(), 0L))
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = state,
            isPlayerActive = { true },
            hooks = hooks,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)
        runCurrent()

        state.value = PlaybackEngineState.Error("ep2")
        runCurrent()

        assertEquals(
            0,
            hooks.dismissCount,
            "耳机里还在响的时候点了另一集、那一集解析失败 —— 停服务等于把正在听的东西掐掉。",
        )
    }

    @Test
    fun `StateFlow 重放给新订阅者的陈旧 Error 不得停掉刚起来的服务`() = runTest {
        val hooks = RecordingHooks()
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Error("上一条命的失败"))
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = state,
            isPlayerActive = { false },
            hooks = hooks,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)
        runCurrent()

        assertEquals(
            0,
            hooks.dismissCount,
            "订阅 StateFlow 拿到的第一个值是上一条命留下的状态,不是订阅之后新发生的失败 —— " +
                "拿它停服务会在播放还没开始时就把服务停了(和 playbackReadyEvents 同一条纪律)。",
        )
    }

    // ---------------------------------------------------------------- 超时兜底

    @Test
    fun `占位通知贴出后播放器长时间空转,超时兜底必须收摊`() = runTest {
        val hooks = RecordingHooks()
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = MutableStateFlow(PlaybackEngineState.Idle),
            isPlayerActive = { false },
            hooks = hooks,
            idleTimeoutMs = 60_000L,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)

        advanceTimeBy(59_000L)
        assertEquals(0, hooks.dismissCount, "还没到时限就收摊会掐掉一次慢但正常的启动")

        advanceTimeBy(2_000L)
        assertEquals(
            1,
            hooks.dismissCount,
            "resolve 卡死、或者压根没人调 play() —— 这两种情况引擎连 Error 都不会发。" +
                "没有这条兜底,进程就一直被钉在前台顶着一条「正在准备播放」。",
        )
    }

    @Test
    fun `播放器在时限内真的播起来了,超时兜底不得动手`() = runTest {
        val hooks = RecordingHooks()
        var active = false
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = MutableStateFlow(PlaybackEngineState.Idle),
            isPlayerActive = { active },
            hooks = hooks,
            idleTimeoutMs = 60_000L,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)

        advanceTimeBy(10_000L)
        active = true
        advanceTimeBy(100_000L)

        assertEquals(0, hooks.dismissCount, "正在放着东西的服务不能被兜底逻辑停掉")
    }

    @Test
    fun `新的一次 onStartCommand 重新武装超时兜底`() = runTest {
        val hooks = RecordingHooks()
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = MutableStateFlow(PlaybackEngineState.Idle),
            isPlayerActive = { false },
            hooks = hooks,
            idleTimeoutMs = 60_000L,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)
        advanceTimeBy(50_000L)
        controller.onStartCommand(hasIntent = true)

        advanceTimeBy(20_000L)
        assertEquals(0, hooks.dismissCount, "第二次启动应当把计时重新拉满,而不是沿用上一次的余额")

        advanceTimeBy(45_000L)
        assertEquals(1, hooks.dismissCount, "重新武装之后仍然要能收摊")
    }

    @Test
    fun `stop 之后不再有任何回调`() = runTest {
        val hooks = RecordingHooks()
        val state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
        val controller = ForegroundLifecycleController(
            scope = backgroundScope,
            engineState = state,
            isPlayerActive = { false },
            hooks = hooks,
            idleTimeoutMs = 60_000L,
        )
        controller.start()
        controller.onStartCommand(hasIntent = true)
        runCurrent()

        controller.stop()
        state.value = PlaybackEngineState.Error("ep1")
        advanceTimeBy(120_000L)

        assertFalse(
            hooks.dismissCount > 0,
            "Service 已经在 onDestroy 里自己收走通知了,这里再 stopSelf 一次是多余的副作用",
        )
        assertTrue(hooks.enterForegroundCount == 1, "stop() 不该影响此前已经发生的进前台")
    }
}
