package dev.insua.jellycast.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置页"开发者信息 → 本次会话已传输字节数"的数据来源。进程级单例,不落盘——语义就是
 * "这次进程存活期间下载了多少字节",重启 App 归零。
 *
 * 由 ExoPlayer 的 `AnalyticsListener.onBandwidthEstimate`(累计的 `totalBytesLoaded`)驱动,
 * 见 `PlaybackService` 里的装配——这里刻意不依赖 Media3/Android,保持纯 Kotlin 可离线单测。
 */
@Singleton
class SessionByteCounter @Inject constructor() {
    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    /** 用累计值(而不是增量)覆盖当前值——调用方传入的是"迄今为止总共加载了多少字节"。 */
    fun update(cumulativeBytes: Long) {
        if (cumulativeBytes > _totalBytes.value) _totalBytes.value = cumulativeBytes
    }
}
