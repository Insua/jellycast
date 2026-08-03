package dev.insua.jellycast.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.insua.jellycast.model.MediaKind
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

    private fun sample(itemId: String = "item-1", kind: String = MediaKind.MOVIE.name) = LastPlayed(
        itemId = itemId,
        positionMs = 12_345L,
        title = "第 3 集:测试用例",
        subtitle = "第 1 季",
        imageTag = "tag-abc",
        runTimeMs = 1_800_000L,
        updatedAt = 1_700_000_000_000L,
        kind = kind,
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
        assertEquals(record.kind, loaded.kind)
    }

    // ---- 复审 Task 5 Important 2:kind 字段必须带默认值,老记录才不会被 runCatching 整条吞掉 ----

    @Test
    fun `字段引入前写盘的旧记录_JSON里没有kind字段_仍能正常解出来,kind落到EPISODE默认值`() = runTest {
        // 手写这个字段引入之前的 JSON 形状(没有 "kind" key),模拟"用户升级 App 前就存在的记录"。
        val key = stringPreferencesKey("last_played")
        dataStore.edit {
            it[key] = """
                {"itemId":"old-item","positionMs":5000,"title":"旧记录","subtitle":"旧副标题",
                "imageTag":"tag-x","runTimeMs":1000000,"updatedAt":123}
            """.trimIndent()
        }

        val loaded = store.lastPlayed.first()

        assertNotNull(loaded, "缺 kind 字段的旧记录不该被 runCatching 整条丢弃")
        assertEquals("old-item", loaded!!.itemId, "旧记录的其它字段应该照常解出来")
        assertEquals(MediaKind.EPISODE.name, loaded.kind, "kind 缺省应落到字段引入前的硬编码行为——EPISODE")
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
