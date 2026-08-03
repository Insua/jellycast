package dev.insua.jellycast.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [LastPlayedStore] 靠一个真实的 `DataStore<Preferences>`(临时文件落盘,非 Context 依赖)驱动,
 * 这样才能在纯 JVM 单测里真正走一遍读写 Flow,而不是只测被抽出来的纯函数。
 */
class LastPlayedStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: LastPlayedStore

    @BeforeEach
    fun setUp(@TempDir tempDir: File) {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir, "last_played_test.preferences_pb") }
        )
        store = LastPlayedStore(dataStore)
    }

    private fun sample(itemId: String = "item-1") = LastPlayed(
        itemId = itemId,
        positionMs = 12_345L,
        title = "第 3 集:测试用例",
        subtitle = "第 1 季",
        imageTag = "tag-abc",
        runTimeMs = 1_800_000L,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `没存过任何东西时发射 null`() = runTest {
        assertNull(store.lastPlayed.first())
    }

    @Test
    fun `save 之后能读回完全相同的记录`() = runTest {
        val record = sample()

        store.save(record)
        val loaded = store.lastPlayed.first()

        assertNotNull(loaded, "save 之后应能读回记录")
        assertEquals(record.itemId, loaded!!.itemId)
        assertEquals(record.positionMs, loaded.positionMs)
        assertEquals(record.title, loaded.title)
        assertEquals(record.subtitle, loaded.subtitle)
        assertEquals(record.imageTag, loaded.imageTag)
        assertEquals(record.runTimeMs, loaded.runTimeMs)
        assertEquals(record.updatedAt, loaded.updatedAt)
    }

    @Test
    fun `再 save 一次会覆盖而不是追加`() = runTest {
        store.save(sample(itemId = "item-1"))
        store.save(sample(itemId = "item-2"))

        val loaded = store.lastPlayed.first()

        assertEquals("item-2", loaded?.itemId)
    }

    @Test
    fun `clear 之后回到 null`() = runTest {
        store.save(sample())

        store.clear()

        assertNull(store.lastPlayed.first())
    }

    @Test
    fun `存储里是损坏的内容时发射 null 而不抛异常`() = runTest {
        val key = stringPreferencesKey("last_played")
        dataStore.edit { it[key] = "{ 这不是合法的 JSON" }

        val loaded = store.lastPlayed.first()

        assertNull(loaded)
    }
}
