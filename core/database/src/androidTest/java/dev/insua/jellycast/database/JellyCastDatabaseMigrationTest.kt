package dev.insua.jellycast.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

/**
 * 验证 1 -> 2 迁移(新增 cached_item 表)不会销毁 progress_report 里尚未上报的观看进度。
 * 用 [MigrationTestHelper] 在真实 SQLite 上跑 Room 生成的 `MIGRATION_1_2`,而不是
 * `fallbackToDestructiveMigration()` —— 后者会静默清空这张表。
 */
@RunWith(AndroidJUnit4::class)
class JellyCastDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JellyCastDatabase::class.java,
    )

    @Test
    fun `迁移1到2后progress_report里的行原封不动`() {
        val v1: SupportSQLiteDatabase = helper.createDatabase(TEST_DB, 1)
        v1.execSQL(
            """
            INSERT INTO progress_report (id, serverId, itemId, playSessionId, positionMs, kind, createdAt)
            VALUES (1, 'sA', 'item-1', NULL, 12345, 'progress', 1000)
            """.trimIndent()
        )
        v1.close()

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        val cursor = v2.query("SELECT serverId, itemId, positionMs, kind, createdAt FROM progress_report")
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("sA", it.getString(0))
            assertEquals("item-1", it.getString(1))
            assertEquals(12345L, it.getLong(2))
            assertEquals("progress", it.getString(3))
            assertEquals(1000L, it.getLong(4))
        }
    }

    /**
     * 迁移 2 -> 3 只新增 `cache_bucket_meta` 表(见 [MIGRATION_2_3]),不得触碰
     * `progress_report`(尚未上报的观看进度)或 `cached_item`(离线缓存)里已有的任何一行——
     * 这两张表在 v2 就已经存在,`fallbackToDestructiveMigration()` 会把它们连带清空。
     */
    @Test
    fun `迁移2到3后progress_report和cached_item里的行原封不动`() {
        val v2: SupportSQLiteDatabase = helper.createDatabase(TEST_DB, 2)
        v2.execSQL(
            """
            INSERT INTO progress_report (id, serverId, itemId, playSessionId, positionMs, kind, createdAt)
            VALUES (1, 'sA', 'item-1', NULL, 12345, 'progress', 1000)
            """.trimIndent()
        )
        v2.execSQL(
            """
            INSERT INTO cached_item (serverId, bucket, itemId, position, payloadJson, updatedAt)
            VALUES ('sA', 'home.resume', 'item-1', 0, '{}', 1000)
            """.trimIndent()
        )
        v2.close()

        val v3 = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        val progressCursor = v3.query("SELECT serverId, itemId, positionMs FROM progress_report")
        progressCursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("sA", it.getString(0))
            assertEquals("item-1", it.getString(1))
            assertEquals(12345L, it.getLong(2))
        }

        val cachedItemCursor = v3.query("SELECT serverId, bucket, itemId, position FROM cached_item")
        cachedItemCursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("sA", it.getString(0))
            assertEquals("home.resume", it.getString(1))
            assertEquals("item-1", it.getString(2))
            assertEquals(0, it.getInt(3))
        }

        // 新表存在且可查询(空表,这条 INSERT 之前 v2 里没有这张表)。
        val metaCursor = v3.query("SELECT COUNT(*) FROM cache_bucket_meta")
        metaCursor.use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    /**
     * 迁移 3 -> 4 只新增 `cached_audio` 表(音频缓存索引,见 [MIGRATION_3_4]),不得触碰
     * `progress_report`/`cached_item`/`cache_bucket_meta` 里已有的任何一行——这三张表在 v3
     * 就已经存在,这个数据库没有用 `fallbackToDestructiveMigration()`,漏写迁移会导致用户
     * 升级时 Room 直接抛异常,而不是静默清空数据,但仍然要用真实迁移验证已有数据不受影响。
     */
    @Test
    fun `迁移3到4后progress_report和cached_item里的行原封不动_cached_audio表可查询`() {
        val v3: SupportSQLiteDatabase = helper.createDatabase(TEST_DB, 3)
        v3.execSQL(
            """
            INSERT INTO progress_report (id, serverId, itemId, playSessionId, positionMs, kind, createdAt)
            VALUES (1, 'sA', 'item-1', NULL, 12345, 'progress', 1000)
            """.trimIndent()
        )
        v3.execSQL(
            """
            INSERT INTO cached_item (serverId, bucket, itemId, position, payloadJson, updatedAt)
            VALUES ('sA', 'home.resume', 'item-1', 0, '{}', 1000)
            """.trimIndent()
        )
        v3.close()

        val v4 = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        val progressCursor = v4.query("SELECT serverId, itemId, positionMs FROM progress_report")
        progressCursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("sA", it.getString(0))
            assertEquals("item-1", it.getString(1))
            assertEquals(12345L, it.getLong(2))
        }

        val cachedItemCursor = v4.query("SELECT serverId, bucket, itemId, position FROM cached_item")
        cachedItemCursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("sA", it.getString(0))
            assertEquals("home.resume", it.getString(1))
            assertEquals("item-1", it.getString(2))
            assertEquals(0, it.getInt(3))
        }

        // 新表存在且可查询(空表,这条 INSERT 之前 v3 里没有这张表)。
        val cachedAudioCursor = v4.query("SELECT COUNT(*) FROM cached_audio")
        cachedAudioCursor.use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }
}
