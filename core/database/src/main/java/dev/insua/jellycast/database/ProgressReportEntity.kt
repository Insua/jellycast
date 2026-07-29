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

    @Query("SELECT * FROM progress_report WHERE serverId = :serverId ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(serverId: String, limit: Int = 100): List<ProgressReportEntity>

    @Query("DELETE FROM progress_report WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /**
     * 设计文档 §2.3 规则 2:一个条目的上报生命周期以 `stop` 终结。发出 `stop` 之前先把该条目
     * 在队列里的所有旧记录删掉 —— 它们必然早于这条 `stop`,重放回去只会把服务端已经正确的
     * 「播完」状态冲掉(实测:已 `Played` 的条目再收到一条 `progress`,位置就会被写回去,
     * 勾还留着,于是这一集永远赖在「继续观看」里)。
     *
     * 这条保证了**持久队列里不可能残留已终结条目的旧记录**,因此终结标记本身不需要持久化。
     *
     * 必须同时按 `serverId` 过滤:多服务器是 v1 明确支持的场景,不同服务器上的条目 id 没有
     * 任何关系,只按 itemId 删会误删另一台服务器的补报记录。
     */
    @Query("DELETE FROM progress_report WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteForItem(serverId: String, itemId: String)
}
