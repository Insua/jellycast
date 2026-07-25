package dev.insua.jellycast.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProgressReportEntity::class], version = 1, exportSchema = false)
abstract class JellyCastDatabase : RoomDatabase() {
    abstract fun progressReportDao(): ProgressReportDao
}
