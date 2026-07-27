package dev.insua.jellycast.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProgressReportEntity::class, CachedItemEntity::class, CacheBucketMetaEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class JellyCastDatabase : RoomDatabase() {
    abstract fun progressReportDao(): ProgressReportDao
    abstract fun cachedItemDao(): CachedItemDao
}

/**
 * v1 -> v2:新增 `cached_item` 表(离线缓存)。`progress_report`(尚未上报的观看进度)原样保留——
 * 这里绝不能用 `fallbackToDestructiveMigration()`,那会在用户升级时静默清空这张表,
 * 丢掉他们还没听完的播放进度。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_item` (
                `serverId` TEXT NOT NULL,
                `bucket` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`serverId`, `bucket`, `itemId`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v2 -> v3:新增 `cache_bucket_meta` 表——记录"(serverId, bucket) 有没有被成功刷新过",用来把
 * "刷新过但服务端确实没有内容"和"从没刷新过"区分开(见 [CacheBucketMetaEntity] 的 KDoc)。
 * 同样绝不能用 `fallbackToDestructiveMigration()`:那会连带清空 `progress_report` 和已有的
 * `cached_item`,丢掉用户还没上报的播放进度和离线缓存。这条迁移只新增表,不触碰已有两张表
 * 的任何一行。
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cache_bucket_meta` (
                `serverId` TEXT NOT NULL,
                `bucket` TEXT NOT NULL,
                `refreshedAt` INTEGER NOT NULL,
                PRIMARY KEY(`serverId`, `bucket`)
            )
            """.trimIndent()
        )
    }
}

/**
 * 唯一的生产 [JellyCastDatabase] 构造点,供 DI 装配调用——放在这个模块里而不是 DI 层,
 * 这样 DI 模块不需要各自直接依赖 `androidx.room:room-runtime` 只为了拼一行 `Room.databaseBuilder`。
 */
fun buildJellyCastDatabase(context: Context): JellyCastDatabase =
    Room.databaseBuilder(context, JellyCastDatabase::class.java, "jellycast.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

