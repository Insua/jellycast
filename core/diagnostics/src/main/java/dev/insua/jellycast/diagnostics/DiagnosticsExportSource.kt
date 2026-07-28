package dev.insua.jellycast.diagnostics

import java.io.File

/**
 * 诊断日志的导出端,和 [DiagnosticsLogger](记录端)分开成两个接口——分享诊断日志这个动作不应该
 * 通过一个"什么都能干"的 logger 接口暴露出去,调用方(设置页)只需要"给我一份可分享的快照"。
 */
interface DiagnosticsExportSource {

    /**
     * 把当前日志文件(可能还包括一份轮转备份,内容更完整)合并成一份独立文件,写到
     * [exportDirectory] 下,供上层构造分享 Intent。没有任何日志内容时返回 `null`。
     */
    fun exportSnapshot(exportDirectory: File): File?
}
