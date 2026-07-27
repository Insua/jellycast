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

/**
 * "(serverId, bucket) 有没有被成功刷新过"这一位单独存放,独立于 [CachedItemEntity] 的行数。
 *
 * [CachedItemDao.observeBucket] 只能回答"这个 bucket 现在有几行",回答不了"零行是因为从没
 * 刷新过,还是刷新过、服务端就是没有内容"——这两种情况对调用方的意义完全不同(前者该显示
 * "无法连接服务器 + 重试",后者该显示"这里还没有内容"),仅凭 `cached_item` 表分不出来。
 *
 * 这张表只回答"有没有刷新过"这一个问题,不管刷新完是不是空的。[refreshedAt] 目前只用于
 * 调试和未来可能的 TTL,读缓存的判断只看这一行**存不存在**,不看它的值。
 */
@Entity(tableName = "cache_bucket_meta", primaryKeys = ["serverId", "bucket"])
data class CacheBucketMetaEntity(
    val serverId: String,
    val bucket: String,
    val refreshedAt: Long,
)

@Dao
interface CachedItemDao {

    @Query("SELECT * FROM cached_item WHERE serverId = :serverId AND bucket = :bucket ORDER BY position ASC")
    fun observeBucket(serverId: String, bucket: String): Flow<List<CachedItemEntity>>

    /** true 表示 (serverId, bucket) 至少成功刷新过一次——哪怕刷新完是空的。 */
    @Query("SELECT EXISTS(SELECT 1 FROM cache_bucket_meta WHERE serverId = :serverId AND bucket = :bucket)")
    suspend fun hasRefreshedBucket(serverId: String, bucket: String): Boolean

    /**
     * 整体替换一个 bucket:先删掉该 serverId+bucket 下的所有旧行,再插入新行,并把
     * [CacheBucketMetaEntity] 标记为"这一刻刷新过"——即使 [items] 是空的,这一位也要写,
     * 这样"服务端确实没有"和"从没刷新过"才分得清楚。用事务包裹,避免观察者在删除和插入之间
     * 看到一个空 bucket 的瞬间状态。
     */
    @Transaction
    suspend fun replaceBucket(
        serverId: String,
        bucket: String,
        items: List<CachedItemEntity>,
        refreshedAt: Long = System.currentTimeMillis(),
    ) {
        deleteBucket(serverId, bucket)
        if (items.isNotEmpty()) insertAll(items)
        upsertBucketMeta(CacheBucketMetaEntity(serverId, bucket, refreshedAt))
    }

    @Query("DELETE FROM cached_item WHERE serverId = :serverId AND bucket = :bucket")
    suspend fun deleteBucket(serverId: String, bucket: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBucketMeta(meta: CacheBucketMetaEntity)

    /** 清空某台服务器的全部缓存,含 [CacheBucketMetaEntity]——否则残留的"刷新过"标记会让
     *  下次(比如换了新服务器复用同一 serverId 的极端场景下)把从未刷新过的 bucket 误判成
     *  "刷新过但是空的"。 */
    @Transaction
    suspend fun clearServer(serverId: String) {
        deleteItemsForServer(serverId)
        deleteMetaForServer(serverId)
    }

    @Query("DELETE FROM cached_item WHERE serverId = :serverId")
    suspend fun deleteItemsForServer(serverId: String)

    @Query("DELETE FROM cache_bucket_meta WHERE serverId = :serverId")
    suspend fun deleteMetaForServer(serverId: String)

    @Transaction
    suspend fun clearAll() {
        deleteAllItems()
        deleteAllMeta()
    }

    @Query("DELETE FROM cached_item")
    suspend fun deleteAllItems()

    @Query("DELETE FROM cache_bucket_meta")
    suspend fun deleteAllMeta()
}
