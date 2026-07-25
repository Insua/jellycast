package dev.insua.jellycast.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 一条待补报的播放进度。当实时上报 Jellyfin 失败(离线、服务器不可达、endpoint 切换中)时
 * 入队,联网后由 Task 12 的 ProgressReporter 取出重放。
 */
@Entity(tableName = "progress_report")
data class ProgressReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val itemId: String,
    val playSessionId: String?,
    val positionMs: Long,
    val kind: String, // "start" | "progress" | "stop"
    val createdAt: Long,
)

@Dao
interface ProgressReportDao {
    @Insert
    suspend fun enqueue(e: ProgressReportEntity)

    @Query("SELECT * FROM progress_report ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<ProgressReportEntity>

    @Query("DELETE FROM progress_report WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
