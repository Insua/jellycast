package dev.insua.jellycast.diagnostics

import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * [DiagnosticsLogger] 的落盘实现。只依赖 [File]——不依赖 Android `Context`,构造时直接给一个目录
 * (调用方,见 `di/DiagnosticsModule.kt`,用 `context.filesDir/diagnostics`)。这样核心逻辑
 * (脱敏、轮转、启停)可以纯 JVM 单测,不需要 Robolectric(CLAUDE.md 铁律 6)。
 *
 * 落盘策略:
 * - 当前文件 `diagnostics.log` + 一份轮转备份 `diagnostics.log.1`。写入前检查当前文件是否已经
 *   达到 [MAX_FILE_BYTES],达到就把它整个改名成备份(覆盖旧备份),从空文件重新开始——
 *   保证磁盘占用有确定上界,不需要解析已有内容做局部截断。
 * - 所有写入都在 [writeLock] 内串行化:诊断日志可能同时被主线程的 `logError` 和崩溃线程的
 *   `logCrash` 触发,不加锁会有并发写坏同一个文件的风险。
 * - 任何 I/O 失败(存储满、目录不可写……)都被 [logError]/[logCrash] 内的 `runCatching` 吞掉——
 *   诊断功能本身绝不能成为新的崩溃源,这是本次任务的硬性约束,优先级高于"日志不能丢"。
 */
class FileDiagnosticsLogger(
    private val directory: File,
) : DiagnosticsLogger, DiagnosticsExportSource {

    @Volatile
    private var enabled: Boolean = true

    private val writeLock = Any()

    private val currentFile: File get() = File(directory, LOG_FILE_NAME)
    private val backupFile: File get() = File(directory, BACKUP_FILE_NAME)

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun isEnabled(): Boolean = enabled

    override fun logError(tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        runCatching { write(level = "ERROR", tag = tag, message = message, throwable = throwable) }
    }

    override fun logCrash(throwable: Throwable) {
        if (!enabled) return
        runCatching {
            write(level = "CRASH", tag = "uncaught", message = throwable.message.orEmpty(), throwable = throwable)
        }
    }

    override fun exportSnapshot(exportDirectory: File): File? {
        val hasCurrent = currentFile.exists()
        val hasBackup = backupFile.exists()
        if (!hasCurrent && !hasBackup) return null
        return runCatching {
            exportDirectory.mkdirs()
            val snapshot = File(exportDirectory, EXPORT_FILE_NAME)
            // 备份在前(更早的内容),当前文件在后——分享出去的文件读起来是按时间顺序的。
            snapshot.bufferedWriter().use { writer ->
                if (hasBackup) writer.append(backupFile.readText())
                if (hasCurrent) writer.append(currentFile.readText())
            }
            snapshot
        }.getOrNull()
    }

    /** 供测试/内部诊断观察当前落盘文件,不是对外契约的一部分。 */
    internal fun currentLogFile(): File? = currentFile.takeIf { it.exists() }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        synchronized(writeLock) {
            directory.mkdirs()
            rotateIfNeeded()
            val line = buildString {
                append(TIMESTAMP_FORMAT.format(Instant.now()))
                append(" [").append(level).append("] ").append(tag).append(": ")
                append(Redactor.redact(message))
                if (throwable != null) {
                    append('\n')
                    append(Redactor.redact(throwable.stackTraceToString()))
                }
                append('\n')
            }
            currentFile.appendText(line)
        }
    }

    private fun rotateIfNeeded() {
        if (currentFile.exists() && currentFile.length() >= MAX_FILE_BYTES) {
            backupFile.delete()
            currentFile.renameTo(backupFile)
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "diagnostics.log"
        private const val BACKUP_FILE_NAME = "diagnostics.log.1"
        internal const val EXPORT_FILE_NAME = "diagnostics_export.log"

        /**
         * 单个日志文件的容量上限:**256 KiB**。
         *
         * 依据:一行典型记录(时间戳 + level + tag + 脱敏后的消息,崩溃再加一份堆栈)通常几十到
         * 几百字节;256 KiB 足够装下几千行,覆盖"一次崩溃 + 崩溃前后一段时间的关键错误"这个诊断
         * 场景绰绰有余。配合下面固定一份的轮转备份,总占用上限是 512 KiB——量级上和一张封面图
         * 缓存相当,对手机存储可以忽略不计,同时满足"不得无限增长"这条硬性要求(design doc §5)。
         */
        const val MAX_FILE_BYTES = 256L * 1024

        private val TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT
    }
}
