package dev.insua.jellycast.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProgressReportEntity::class], version = 1, exportSchema = false)
abstract class JellyCastDatabase : RoomDatabase() {
    abstract fun progressReportDao(): ProgressReportDao
}

/**
 * 唯一的生产 [JellyCastDatabase] 构造点,供 DI 装配调用——放在这个模块里而不是 DI 层,
 * 这样 DI 模块不需要各自直接依赖 `androidx.room:room-runtime` 只为了拼一行 `Room.databaseBuilder`。
 */
fun buildJellyCastDatabase(context: Context): JellyCastDatabase =
    Room.databaseBuilder(context, JellyCastDatabase::class.java, "jellycast.db").build()

