package dev.insua.jellycast.e2e

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.insua.jellycast.player.PlaybackService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * # 复现 BUG-1:`startForegroundService()` 之后没人及时调 `startForeground()`
 *
 * Task 1 的场景 4 是靠**整个进程被 `kill -9`** 暴露这个缺陷的 —— 那种"复现"没法当回归测试用:
 * 进程一死,JUnit 连断言都来不及跑,`<failure>` 节点是空的,而且它会连带打断同一次 instrumentation
 * 里其它所有测试。
 *
 * 这个用例把同一个缺陷收敛成一条**确定的、可断言的、不需要任何服务器凭据**的事实:
 *
 * > `Context.startForegroundService()` 之后,系统给的 `startForeground()` 期限是 **10 秒**
 * > (`ANR: Context.startForegroundService() did not then call Service.startForeground()`)。
 * > Task 1 实测 `startForegroundDelayMs:10785` —— 已经越线,只是因为当时进程恰好在
 * > `PROC_STATE_TOP`,ANR 被延后了,一退到后台立刻兑现。
 *
 * 断言用的是**系统自己的视角**(`ActivityManager.RunningServiceInfo.foreground`),不是本应用
 * 记的账 —— 判本应用死刑的是系统,那就必须问系统。
 *
 * 刻意**不**启动任何播放、**不**连 `MediaController`:前台服务的时限从 `startForegroundService()`
 * 那一刻起算,和"播放源解析要多久"无关。反过来说,只要还指望 Media3 在"播放器真的开始缓冲"那一刻
 * 才顺带进前台,这条时限就永远被网络往返绑架 —— 家宽上行越慢越必死。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ForegroundServiceE2eTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before
    fun setUp() {
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        hilt.inject()
    }

    @After
    fun tearDown() {
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    /**
     * 主用例:起服务 → 必须在 [FOREGROUND_DEADLINE_MS] 内进入前台。
     *
     * 两级断言,因为"服务根本没起来"和"起来了但没进前台"是两个不同的 bug:
     * 1. 服务出现在 `getRunningServices` 里 —— 顺带证明这个观测手段本身有效(否则第 2 步永远是
     *    false,会把一个坏掉的观测手段误报成产品缺陷)。
     * 2. `foreground == true`。
     */
    @Test
    fun `前台服务必须在系统时限内进入前台`() {
        val startedAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))

        val running = awaitCondition(SERVICE_ALIVE_TIMEOUT_MS) { serviceInfo() != null }
        assertTrue(
            "PlaybackService 在 ${SERVICE_ALIVE_TIMEOUT_MS}ms 内根本没起来 —— " +
                "观测手段(ActivityManager.getRunningServices)或服务声明有问题,不是前台时序问题。",
            running,
        )

        val enteredForeground = awaitCondition(FOREGROUND_DEADLINE_MS) { serviceInfo()?.foreground == true }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        assertTrue(
            "startForegroundService() 之后 ${elapsed}ms 仍未进入前台(系统时限 10s,本用例要求 " +
                "${FOREGROUND_DEADLINE_MS}ms 内)。这正是 Task 1 里 `startForegroundDelayMs:10785` + " +
                "`Context.startForegroundService() did not then call Service.startForeground()` " +
                "→ `bg anr` → `kill -9` 那条链的起点。",
            enteredForeground,
        )
    }

    /**
     * 第二个用例,盯 Task 1 报告里那条更隐蔽的事实:**一个进程里只出现过一次
     * `am_foreground_service_start`**。Service 被销毁过一次之后(用户划掉最近任务 →
     * `onTaskRemoved` → `stopSelf`),后续每一次 `startForegroundService` 都再也进不了前台,
     * 于是"第二次播放"必被系统杀掉。
     *
     * 用户视角:第一集听得好好的,退出 App 再进来点播放 —— 闪退。
     */
    @Test
    fun `服务被销毁后再次启动仍必须进入前台`() {
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        assertTrue(
            "第一次启动就没进前台,后续断言无从谈起。",
            awaitCondition(FOREGROUND_DEADLINE_MS) { serviceInfo()?.foreground == true },
        )

        context.stopService(Intent(context, PlaybackService::class.java))
        assertTrue(
            "stopService 之后服务没有真正停掉,无法验证重启路径。",
            awaitCondition(SERVICE_ALIVE_TIMEOUT_MS) { serviceInfo() == null },
        )

        val restartedAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        val enteredForeground = awaitCondition(FOREGROUND_DEADLINE_MS) { serviceInfo()?.foreground == true }
        assertTrue(
            "第二次 startForegroundService 之后 ${SystemClock.elapsedRealtime() - restartedAt}ms 仍未进入前台。" +
                "Task 1 的 logcat 里这条路径表现为:3 次 startForegroundService,0 次 am_foreground_service_start。",
            enteredForeground,
        )
    }

    /** 系统视角下的本服务状态;服务没在跑时为 null。API 26+ 只会返回调用方自己的服务。 */
    @Suppress("DEPRECATION")
    private fun serviceInfo(): ActivityManager.RunningServiceInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == PlaybackService::class.java.name }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private companion object {
        /**
         * 系统时限是 10s。这里取一半:前台服务进前台**不该**依赖任何网络往返,留 5s 已经极其宽松,
         * 而卡在 5–10s 之间的实现在真机走公网时必然越线(Task 1 在模拟器+局域网就已经 10.785s)。
         */
        const val FOREGROUND_DEADLINE_MS = 5_000L
        const val SERVICE_ALIVE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
