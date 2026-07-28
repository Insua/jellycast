package dev.insua.jellycast.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [FileDiagnosticsLogger] 只依赖 [File],不依赖 Android `Context`——可以纯 JVM 单测
 * (CLAUDE.md 铁律 6)。用 JUnit5 的 `@TempDir` 给每个用例一份隔离的落盘目录。
 */
class FileDiagnosticsLoggerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var logger: FileDiagnosticsLogger

    @BeforeEach
    fun setUp() {
        logger = FileDiagnosticsLogger(directory = tempDir)
    }

    @Test fun `记录一条错误后落盘文件包含 tag 和 message`() {
        logger.logError(tag = "network", message = "endpoint 探测超时")

        val content = logger.currentLogFile()?.readText().orEmpty()
        assertTrue(content.contains("network"))
        assertTrue(content.contains("endpoint 探测超时"))
    }

    @Test fun `落盘内容经过脱敏,凭据不会原样出现`() {
        logger.logError(tag = "auth", message = "登录失败 token=deadbeefcafefeed12345678deadbeef")

        val content = logger.currentLogFile()?.readText().orEmpty()
        assertFalse(content.contains("deadbeefcafefeed12345678deadbeef"))
    }

    @Test fun `logCrash 记录异常的类型和消息与堆栈`() {
        val error = IllegalStateException("播放引擎状态异常")
        logger.logCrash(error)

        val content = logger.currentLogFile()?.readText().orEmpty()
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(content.contains("播放引擎状态异常"))
    }

    @Test fun `setEnabled false 之后不再写入新日志`() {
        logger.setEnabled(false)
        logger.logError(tag = "x", message = "不应该被记录")

        val content = logger.currentLogFile()
        assertTrue(content == null || !content.readText().contains("不应该被记录"))
    }

    @Test fun `默认是开启状态`() {
        assertTrue(logger.isEnabled())
    }

    @Test fun `持续写入不会让磁盘占用无限增长`() {
        // 每条约 100+ 字节,写够多条足以触发多次轮转。
        repeat(5_000) { i ->
            logger.logError(tag = "loop", message = "第 $i 条测试日志,用来把文件写过轮转阈值" + "x".repeat(50))
        }

        val totalBytes = tempDir.listFiles()?.sumOf { it.length() } ?: 0L
        // 上限 = 当前文件 + 一份轮转备份,各自不超过 FileDiagnosticsLogger.MAX_FILE_BYTES,
        // 允许最后一次写入的单行溢出一点点,给一个宽松但仍然是"有限"的上界。
        val bound = FileDiagnosticsLogger.MAX_FILE_BYTES * 2 + 8192
        assertTrue(totalBytes in 1..bound, "总字节数 $totalBytes 应该在 (0, $bound] 之间")
    }

    @Test fun `写日志失败时不抛异常,诊断功能本身不能成为新的崩溃源`() {
        // 用一个"不可能是目录"的路径(把一个文件当目录用)模拟落盘失败。
        val obstruction = File(tempDir, "not-a-directory")
        obstruction.writeText("occupied")
        val brokenLogger = FileDiagnosticsLogger(directory = File(obstruction, "diagnostics"))

        brokenLogger.logError(tag = "x", message = "这次写入必然失败")
        brokenLogger.logCrash(RuntimeException("也必然失败"))
        // 没有抛出异常就是通过。
    }

    @Test fun `exportSnapshot 合并当前文件与轮转备份`() {
        repeat(50) { i -> logger.logError(tag = "loop", message = "line $i") }

        val snapshot = logger.exportSnapshot(exportDirectory = File(tempDir, "export"))
        assertTrue(snapshot != null && snapshot.exists())
        assertEquals(true, snapshot?.readText()?.contains("line 49"))
    }

    @Test fun `没有任何日志时 exportSnapshot 返回 null`() {
        val snapshot = logger.exportSnapshot(exportDirectory = File(tempDir, "export"))
        assertTrue(snapshot == null)
    }
}
