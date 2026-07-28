package dev.insua.jellycast.diagnostics

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * design doc §5 / 计划 Task 5:"捕获未处理异常时也要落盘,但这不等于吞掉崩溃——只是先记录再交还
 * 给系统默认处理器"。这里验证两件事都发生,并且顺序正确(先记后交还),以及记录本身失败时
 * 崩溃仍然必须继续传播(诊断功能不能变成新的故障点,也不能替用户"消化"掉一次真崩溃)。
 */
class DiagnosticsUncaughtExceptionHandlerTest {

    @Test fun `捕获未处理异常时先记录再交还给默认处理器`() {
        val logger = mockk<DiagnosticsLogger>(relaxed = true)
        val defaultHandler = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val handler = DiagnosticsUncaughtExceptionHandler(logger, defaultHandler)
        val thread = Thread.currentThread()
        val error = RuntimeException("崩溃现场")

        handler.uncaughtException(thread, error)

        verifyOrder {
            logger.logCrash(error)
            defaultHandler.uncaughtException(thread, error)
        }
    }

    @Test fun `没有上一个默认处理器时不会崩溃`() {
        val logger = mockk<DiagnosticsLogger>(relaxed = true)
        val handler = DiagnosticsUncaughtExceptionHandler(logger, defaultHandler = null)

        assertDoesNotThrow {
            handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        }
    }

    @Test fun `记录失败也不能阻止异常交还给默认处理器`() {
        val logger = mockk<DiagnosticsLogger>()
        every { logger.logCrash(any()) } throws IllegalStateException("落盘失败")
        val defaultHandler = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val handler = DiagnosticsUncaughtExceptionHandler(logger, defaultHandler)
        val thread = Thread.currentThread()
        val error = RuntimeException("崩溃现场")

        assertDoesNotThrow { handler.uncaughtException(thread, error) }

        verify { defaultHandler.uncaughtException(thread, error) }
    }
}
