package dev.insua.jellycast.feature.settings

import dev.insua.jellycast.model.AudioDeliveryLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * 复审 Task 5 Finding 1:命中音频缓存的 `PlaybackSource.level` 仍然是
 * `AudioDeliveryLevel.SERVER_AUDIO_ONLY`(`CacheAwareSourceProvider` 的实现细节),但那条流其实
 * 一次请求都没发给服务端。「开发者信息」面板存在的唯一目的就是让人看清这次到底走的是哪条网络
 * 路径——如果不看 `isLocalFile` 只看 `level`,播本地缓存文件时会显示「L1 · 服务端纯音频」,
 * 是假话,且和几乎为 0 的「本次会话已传输字节数」自相矛盾。
 */
class SettingsScreenTest {

    @Test fun `本地缓存播放时显示本地缓存标签而不是 L1`() {
        val label = deliveryLevelDisplayLabel(AudioDeliveryLevel.SERVER_AUDIO_ONLY, isLocalFile = true)

        assertEquals("本地缓存 · 未发起网络请求", label, "命中缓存不该显示成「L1 · 服务端纯音频」——那条流没发过网络请求")
    }

    @Test fun `远端 L1 源正常显示服务端纯音频`() {
        val label = deliveryLevelDisplayLabel(AudioDeliveryLevel.SERVER_AUDIO_ONLY, isLocalFile = false)

        assertEquals("L1 · 服务端纯音频", label)
    }

    @Test fun `远端 L3 源正常显示客户端禁用视频轨`() {
        val label = deliveryLevelDisplayLabel(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, isLocalFile = false)

        assertEquals("L3 · 客户端禁用视频轨(兜底)", label)
    }

    @Test fun `没有在播放时显示未在播放`() {
        val label = deliveryLevelDisplayLabel(null, isLocalFile = false)

        assertEquals("未在播放", label)
    }

    // ---- Task 7:缓存最大占用存储选项文案 ----

    @Test fun `1GB 5GB 10GB 显示为对应的 GB 数`() {
        assertEquals("1 GB", formatCacheMaxBytesOption(1L * 1024 * 1024 * 1024))
        assertEquals("5 GB", formatCacheMaxBytesOption(5L * 1024 * 1024 * 1024))
        assertEquals("10 GB", formatCacheMaxBytesOption(10L * 1024 * 1024 * 1024))
    }

    /**
     * task-7-brief 明确要防的坑:「不限制」和「1 GB」必须在展示层也能被区分出来,不能显示成同一
     * 个文案(比如都显示成"1 GB"或都显示成空字符串)。
     */
    @Test fun `不限制显示为不限制而不是和 1GB 显示成同一个文案`() {
        val unlimited = formatCacheMaxBytesOption(null)
        val oneGb = formatCacheMaxBytesOption(1L * 1024 * 1024 * 1024)

        assertEquals("不限制", unlimited)
        assertNotEquals(oneGb, unlimited)
    }
}
