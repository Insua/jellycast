package dev.insua.jellycast.diagnostics.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.diagnostics.DiagnosticsExportSource
import dev.insua.jellycast.diagnostics.DiagnosticsExporter
import dev.insua.jellycast.diagnostics.DiagnosticsLogger
import dev.insua.jellycast.diagnostics.FileDiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Singleton

/**
 * 装配"诊断日志"这个领域(design doc §5)。落盘目录用 `context.filesDir/diagnostics`——
 * 应用私有、persist 直到用户主动关闭诊断或系统清理应用数据,和 `:core:database` 的 Room 文件
 * 同一层级,不是 `cacheDir`(那是给导出时的临时合并文件用的,见 [DiagnosticsExporter])。
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {

    /**
     * ⚠️ 必须是 `@Singleton`:[DiagnosticsLogger] 和 [DiagnosticsExportSource] 都要绑定到
     * *同一个* [FileDiagnosticsLogger] 实例上,否则"记的日志"和"导出的日志"就是两份互不相干的
     * 落盘文件。
     */
    @Provides
    @Singleton
    fun provideFileDiagnosticsLogger(@ApplicationContext context: Context): FileDiagnosticsLogger =
        FileDiagnosticsLogger(directory = File(context.filesDir, DIAGNOSTICS_DIR_NAME))

    /**
     * `enabled` 是 [FileDiagnosticsLogger] 内部的 `@Volatile` 字段,同步读取——`logCrash` 可能在
     * 崩溃线程里被触发,不能等一次 DataStore 的 suspend 读。这里在装配时刻订阅
     * [PreferencesStore.diagnosticsEnabled],用一个跟随进程存活的 [CoroutineScope] 持续把最新值
     * 同步进去(默认开启,用户随时可在设置页关闭,下一次 `collect` 触发时生效)。
     *
     * 这个 scope 故意不绑定任何 `ViewModel`/`Service` 生命周期——它和 `:core:player` 里
     * `PlayQueue`/`SessionByteCounter` 这类进程级单例是同一类取舍:活到进程结束,没有显式取消点。
     */
    @Provides
    @Singleton
    fun provideDiagnosticsLogger(
        fileLogger: FileDiagnosticsLogger,
        preferencesStore: PreferencesStore,
    ): DiagnosticsLogger {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { preferencesStore.diagnosticsEnabled.collect { enabled -> fileLogger.setEnabled(enabled) } }
        return fileLogger
    }

    @Provides
    fun provideDiagnosticsExportSource(fileLogger: FileDiagnosticsLogger): DiagnosticsExportSource = fileLogger

    @Provides
    @Singleton
    fun provideDiagnosticsExporter(
        @ApplicationContext context: Context,
        exportSource: DiagnosticsExportSource,
    ): DiagnosticsExporter = DiagnosticsExporter(context, exportSource)

    private const val DIAGNOSTICS_DIR_NAME = "diagnostics"
}
