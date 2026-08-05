package dev.insua.jellycast.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 给预取控制器(Task 6)做闸门:自动缓存只在 WiFi 下发生——出门走蜂窝、或者公网 HTTPS 走的是
 * 家宽上行带宽,流量这件事绝不能替用户做主。
 *
 * 主动查询而不是订阅回调:调用方只在"要不要发起下一个预取"这个决策点问一次,不需要为了这一层
 * 持续订阅系统的网络变化广播——换网时机由调用方自己的换集/心跳节奏驱动。
 *
 * 任何查询失败(拿不到系统服务、拿不到 activeNetwork/能力集)一律当作"不在 WiFi",不抛异常——
 * 这是安全默认值:查不清楚的时候宁可不预取,也不该在不确定的时候消耗用户的流量。
 */
class NetworkTypeMonitor(private val context: Context) {

    fun isOnWifi(): Boolean {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
