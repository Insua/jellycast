package dev.insua.jellycast.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 端到端测试里"把设备断网 / 恢复联网"的唯一开关。
 *
 * ## 为什么是飞行模式,不是 `svc wifi disable` / `svc data disable`
 *
 * 计划里写的是 `svc wifi/data`,但当前测试用的模拟器镜像上 `svc` 只剩
 * `help / power / usb / nfc / system-server` 五个子命令,`wifi` 和 `data` 已经不存在
 * (跑一次 `adb shell svc` 就能看到)。飞行模式是同一台设备上仍然可用、且**一次切换就同时
 * 关掉 WiFi 和蜂窝**的等价手段,恢复也只需要反向执行一次。
 *
 * ## 断网/恢复都要等到**真的生效**
 *
 * `cmd connectivity airplane-mode enable` 立刻返回,但连接的拆除是异步的;恢复更慢——WiFi 重新
 * 关联 + DHCP + 连通性校验要好几秒。如果不等,"断网测试"会在网还没断的时候跑完(假绿),
 * "恢复"会把一台还没连上的设备交给下一个测试(假红)。所以两个方向都轮询
 * [ConnectivityManager] 的 `NET_CAPABILITY_VALIDATED` 直到状态翻转。
 *
 * ## 🔴 恢复是无条件的
 *
 * [goOnline] 必须出现在每个用例的 `@After` 里,并且**不允许**被任何 `if` 挡住——一个把设备留在
 * 飞行模式里的用例会让它之后的每一个测试都莫名其妙地红,而失败信息里不会有任何线索指向它。
 */
object NetworkControl {

    /** 断开:等到系统确认没有可用网络为止。 */
    fun goOffline() {
        shell("cmd connectivity airplane-mode enable")
        check(awaitInternet(available = false, timeoutMs = OFFLINE_TIMEOUT_MS)) {
            "已执行断网命令,但 ${OFFLINE_TIMEOUT_MS / 1000}s 内设备仍报告有可用网络,断网场景无从验证"
        }
    }

    /** 恢复:等到系统确认网络已通过连通性校验为止。 */
    fun goOnline() {
        shell("cmd connectivity airplane-mode disable")
        check(awaitInternet(available = true, timeoutMs = ONLINE_TIMEOUT_MS)) {
            "已执行恢复联网命令,但 ${ONLINE_TIMEOUT_MS / 1000}s 内设备仍报告无可用网络。" +
                "⚠️ 设备可能被留在了断网状态,后续测试会因此连带失败"
        }
    }

    /**
     * 当前是否有一条声称能上网的默认网络。
     *
     * ⚠️ 刻意**不**要求 `NET_CAPABILITY_VALIDATED`:这台模拟器的默认网络(CELLULAR/eth0)从来
     * 拿不到 VALIDATED —— 系统的连通性校验探针(generate_204)在这个镜像上不成立,即使
     * `ping 8.8.8.8` 完全正常、真实 HTTP 请求也完全正常。用 VALIDATED 当判据的话
     * [goOnline] 会永远等到超时,把"网络其实早就恢复了"误报成"设备被留在断网状态",
     * 每个用例白白多花 90 秒还全红(第一次跑就是这么失败的)。
     *
     * 飞行模式会把默认网络整个撤掉(`activeNetwork == null`),所以"有没有默认网络"对本文件
     * 要区分的两种状态已经完全够用。
     */
    fun hasInternet(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun awaitInternet(available: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasInternet() == available) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return hasInternet() == available
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().decodeToString()
        }
    }

    private const val OFFLINE_TIMEOUT_MS = 30_000L

    /** 恢复比断开慢得多:WiFi 重新关联 + DHCP + 连通性校验。 */
    private const val ONLINE_TIMEOUT_MS = 90_000L

    private const val POLL_INTERVAL_MS = 500L
}
