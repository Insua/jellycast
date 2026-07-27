package dev.insua.jellycast.e2e

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * instrumentation 测试跑的是 [HiltTestApplication],不是生产的 `JellyCastApp`。
 *
 * 为什么必须换掉 Application:`@HiltAndroidTest` 生成的测试组件挂在 [HiltTestApplication] 上,
 * 用生产 Application 启动时 `HiltAndroidRule.inject()` 会直接抛
 * "Hilt test, Application must be HiltTestApplication"。
 *
 * ⚠️ 换掉的只是 Application 这一层。被测的 `PlaybackService` / `MainActivity` / 整张 Hilt 图
 * (真实 ExoPlayer、真实 `AudioPlaybackEngine`、真实 `JellyfinApi`)全都是**生产实现**,
 * 没有任何 fake —— 这正是本套端到端测试存在的理由:281 个 JVM 单测全绿却在真机全崩,
 * 就是因为从来没有让真实的这几个东西一起跑过。
 */
class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
