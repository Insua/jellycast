package dev.insua.jellycast.diagnostics

/**
 * 应用内诊断日志的写入端(design doc §5)。所有实现必须满足:
 * - **绝不记录凭据**:实现内部必须经过 [Redactor],不得把调用方传入的原始文本原样落盘。
 * - **写日志失败绝不能抛出异常**:日志功能本身不能成为新的崩溃源(本次任务的硬性约束)。
 * - [logCrash] 只负责"记下来",调用方(见 `DiagnosticsUncaughtExceptionHandler`)负责在记录之后
 *   继续把异常交还给系统默认处理器——这里不做、也不应该做那件事。
 */
interface DiagnosticsLogger {

    /** 记录一条非致命的关键错误(网络失败、字幕解析异常等),不影响调用方后续流程。 */
    fun logError(tag: String, message: String, throwable: Throwable? = null)

    /** 记录一次未捕获异常。只负责落盘,不吞异常、不做任何恢复动作。 */
    fun logCrash(throwable: Throwable)

    /** 用户在设置页关闭诊断日志后,后续的 [logError]/[logCrash] 调用应静默变成空操作。 */
    fun setEnabled(enabled: Boolean)

    fun isEnabled(): Boolean
}
