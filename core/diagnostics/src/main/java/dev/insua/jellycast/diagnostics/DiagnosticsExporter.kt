package dev.insua.jellycast.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 设置页"导出诊断日志"背后的动作:把 [DiagnosticsExportSource] 生成的快照文件包成一个
 * `ACTION_SEND` 分享 Intent。
 *
 * 用 [FileProvider](见本模块的 `AndroidManifest.xml` + `res/xml/diagnostics_file_paths.xml`)而不是
 * `file://` Uri——从 Android 7 起对外暴露 `file://` Uri 会直接抛 `FileUriExposedException`,而且
 * `content://` + `FLAG_GRANT_READ_URI_PERMISSION` 只把读权限临时给分享目标那一个 App,不需要把
 * 应用私有目录整个设成对外可读。
 */
class DiagnosticsExporter(
    private val context: Context,
    private val exportSource: DiagnosticsExportSource,
) {

    /** 没有任何日志内容(从未记录过、或用户此前已关闭诊断日志)时返回 `null`。 */
    fun buildShareIntent(): Intent? {
        val snapshot = exportSource.exportSnapshot(exportDirectory = exportDirectory()) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.fileprovider", snapshot)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享诊断日志")
    }

    private fun exportDirectory(): File = File(context.cacheDir, EXPORT_DIR_NAME)

    private companion object {
        const val EXPORT_DIR_NAME = "diagnostics_export"
    }
}
