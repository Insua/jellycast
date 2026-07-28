package dev.insua.jellycast.diagnostics

import javax.inject.Inject

/**
 * 进程启动时把 [DiagnosticsUncaughtExceptionHandler] 接到 [Thread.setDefaultUncaughtExceptionHandler]
 * 上。故意不放进 Hilt `@Provides` 方法里当副作用——"安装一个全局 JVM 处理器"这件事应该由调用方
 * (`JellyCastApp.onCreate`)显式触发一次,而不是藏在 DI 图构建过程里,读代码的人才能一眼看到
 * "这个副作用发生在这里"。
 *
 * 必须只调用一次:每次调用都会把当前的 `Thread.getDefaultUncaughtExceptionHandler()` 包一层再设回去,
 * 反复调用会链式包出多层(不会导致重复记录之外的问题,但没有意义,而且 `JellyCastApp` 的生命周期
 * 决定了 `onCreate` 本来就只会跑一次)。
 */
class DiagnosticsInstaller @Inject constructor(
    private val logger: DiagnosticsLogger,
) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticsUncaughtExceptionHandler(logger, previous))
    }
}
