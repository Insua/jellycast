package dev.insua.jellycast.diagnostics

/**
 * design doc §5 / 计划 Task 5:"捕获未处理异常时也要落盘,但这不等于吞掉崩溃——只是先记录再交还
 * 给系统默认处理器"。
 *
 * 顺序是刻意的:先 [DiagnosticsLogger.logCrash] 再调用 [defaultHandler]——如果颠倒过来,
 * `defaultHandler`(系统默认实现通常会立刻结束进程)可能在我们来得及落盘之前就已经把进程杀掉。
 *
 * [logger] 的记录动作被 `runCatching` 包住:诊断日志写失败(存储满、并发写冲突……)绝不能拦下
 * 真正的崩溃——一个"因为记日志失败而没有真的崩溃"的 App 比直接崩溃更危险,用户和系统都会误以为
 * 一切正常。这是本次任务里最容易被写反的一条,所以专门用测试锁住顺序和"记录失败仍必须传播"。
 */
class DiagnosticsUncaughtExceptionHandler(
    private val logger: DiagnosticsLogger,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { logger.logCrash(throwable) }
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
