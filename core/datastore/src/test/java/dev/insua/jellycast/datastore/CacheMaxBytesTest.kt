package dev.insua.jellycast.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val ONE_GB = 1024L * 1024L * 1024L
private const val FIVE_GB = 5L * 1024L * 1024L * 1024L
private const val TEN_GB = 10L * 1024L * 1024L * 1024L

/**
 * [PreferencesStore.cacheMaxBytes] 读写两侧的编解码逻辑抽成纯函数,离线可单测——DataStore 本身
 * 需要 Android Context,不适合直接放 JVM 单测,和 [ServerUpsertTest] 同一种取舍(见 [ServerStore]
 * 的 KDoc)。
 *
 * 设计文档 §4.3 / task-7-brief:缓存存储上限默认 1 GB,可选 5 GB / 10 GB / 不限制。这里重点盯的
 * 是 brief 点名的坑——「不限制」和「1 GB」如果在存储层折叠成同一个值,「不限制」会在读回时静默
 * 退化成「1 GB」,用户设置的东西是假的。
 */
class CacheMaxBytesTest {

    @Test fun `没设置过时默认是 1 GB`() {
        assertEquals(ONE_GB, decodeCacheMaxBytes(null))
    }

    @Test fun `1GB 5GB 10GB 不限制 四个选项都能编码后原样解回`() {
        assertEquals(ONE_GB, decodeCacheMaxBytes(encodeCacheMaxBytes(ONE_GB)))
        assertEquals(FIVE_GB, decodeCacheMaxBytes(encodeCacheMaxBytes(FIVE_GB)))
        assertEquals(TEN_GB, decodeCacheMaxBytes(encodeCacheMaxBytes(TEN_GB)))
        assertNull(decodeCacheMaxBytes(encodeCacheMaxBytes(null)))
    }

    @Test fun `不限制和 1GB 在存储层是两个不同的值,不会被混淆`() {
        val unlimitedStored = encodeCacheMaxBytes(null)
        val oneGbStored = encodeCacheMaxBytes(ONE_GB)

        assertNotEquals(
            oneGbStored,
            unlimitedStored,
            "「不限制」和「1 GB」编码后必须是不同的存储值,否则读回时无法区分,「不限制」会退化成「1 GB」",
        )
        assertNull(decodeCacheMaxBytes(unlimitedStored), "「不限制」解码回来必须是 null,不能变成任何具体字节数")
        assertNotEquals(ONE_GB, decodeCacheMaxBytes(unlimitedStored))
    }
}
