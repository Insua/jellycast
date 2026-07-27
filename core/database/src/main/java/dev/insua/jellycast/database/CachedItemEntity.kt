package dev.insua.jellycast.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 一条缓存的 Jellyfin 条目(剧集/季/集/电影/库视图……),按 [serverId] + [bucket] 分区存放。
 *
 * - `bucket` 是逻辑分区键,例如 `home.resume` / `home.nextup` / `home.recent` / `views` /
 *   `library.series` / `series.<seriesId>.seasons` / `season.<seasonId>.episodes`。
 *   一次刷新替换整个 bucket(见 [CachedItemDao.replaceBucket]),不做增量合并,
 *   这样服务端删掉的条目不会在缓存里变成阴魂不散的僵尸行。
 * - `position` 保存服务端返回的展示顺序(如"最近续播"是按时间倒序、剧集列表是按季集号排序)。
 *   绝不能依赖 SQLite 的插入顺序/rowid 顺序——那是实现细节,不是承诺的排序语义。
 * - `payloadJson` 是序列化后的 `MediaItem`(领域模型),这一层不关心具体字段,只管存取。
 * - 主键含 `serverId`,这样多服务器场景下不同服务器的同名 bucket 天然不会互相覆盖或串号。
 */
@Entity(tableName = "cached_item", primaryKeys = ["serverId", "bucket", "itemId"])
data class CachedItemEntity(
    val serverId: String,
    val bucket: String,
    val itemId: String,
    val position: Int,
    val payloadJson: String,
    val updatedAt: Long,
)

@Dao
interface CachedItemDao {

    @Query("SELECT * FROM cached_item WHERE serverId = :serverId AND bucket = :bucket ORDER BY position ASC")
    fun observeBucket(serverId: String, bucket: String): Flow<List<CachedItemEntity>>

    /**
     * 整体替换一个 bucket:先删掉该 serverId+bucket 下的所有旧行,再插入新行。
     * 用事务包裹,避免观察者在删除和插入之间看到一个空 bucket 的瞬间状态。
     */
    @Transaction
    suspend fun replaceBucket(serverId: String, bucket: String, items: List<CachedItemEntity>) {
        deleteBucket(serverId, bucket)
        if (items.isNotEmpty()) insertAll(items)
    }

    @Query("DELETE FROM cached_item WHERE serverId = :serverId AND bucket = :bucket")
    suspend fun deleteBucket(serverId: String, bucket: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedItemEntity>)

    @Query("DELETE FROM cached_item WHERE serverId = :serverId")
    suspend fun clearServer(serverId: String)

    @Query("DELETE FROM cached_item")
    suspend fun clearAll()
}
