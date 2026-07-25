package dev.insua.jellycast.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding 2:歌词视图"手动滚动暂停自动跟随,3 秒后恢复"的计时逻辑,从 [LyricsView] 的
 * `LaunchedEffect(isDragged)` 里抽出来的纯 Kotlin 状态机——用虚拟时间(`runTest` + `advanceTimeBy`)
 * 单测,不需要真的跑 Compose。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsAutoFollowControllerTest {

    @Test fun `初始状态自动跟随开启`() = runTest {
        val controller = LyricsAutoFollowController(this, resumeDelayMs = 3000L)
        assertTrue(controller.autoFollow.value)
    }

    @Test fun `拖拽中立即暂停自动跟随`() = runTest {
        val controller = LyricsAutoFollowController(this, resumeDelayMs = 3000L)

        controller.onDragStateChanged(isDragged = true)

        assertFalse(controller.autoFollow.value)
    }

    @Test fun `松手未满 3 秒时仍保持暂停`() = runTest {
        val controller = LyricsAutoFollowController(this, resumeDelayMs = 3000L)

        controller.onDragStateChanged(isDragged = true)
        controller.onDragStateChanged(isDragged = false)
        advanceTimeBy(2999L)
        runCurrent()

        assertFalse(controller.autoFollow.value)
    }

    @Test fun `松手满 3 秒后恢复自动跟随`() = runTest {
        val controller = LyricsAutoFollowController(this, resumeDelayMs = 3000L)

        controller.onDragStateChanged(isDragged = true)
        controller.onDragStateChanged(isDragged = false)
        advanceTimeBy(3000L)
        runCurrent()

        assertTrue(controller.autoFollow.value)
    }

    @Test fun `恢复计时期间再次触摸会取消上一次等待,重新计时`() = runTest {
        val controller = LyricsAutoFollowController(this, resumeDelayMs = 3000L)

        controller.onDragStateChanged(isDragged = true)
        controller.onDragStateChanged(isDragged = false)
        advanceTimeBy(1500L)
        controller.onDragStateChanged(isDragged = true)   // 再次触摸,取消上一个恢复协程
        controller.onDragStateChanged(isDragged = false)
        advanceTimeBy(1500L)
        runCurrent()

        // 第一次触摸后过了 3000ms(1500+1500),但由于中途重置,新一轮计时只过了 1500ms
        assertFalse(controller.autoFollow.value)

        advanceTimeBy(1500L)
        runCurrent()

        assertTrue(controller.autoFollow.value)
    }
}
