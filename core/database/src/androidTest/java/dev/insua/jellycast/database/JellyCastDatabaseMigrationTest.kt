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
}
