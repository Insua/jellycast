package dev.insua.jellycast.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 一条已完整下载到本地的音频文件索引。驱逐策略(按剧集顺序、最后访问时间、总占用来决策)
 * 全靠查询这张表,不扫描文件系统——文件本身只是内容,这张表才是"缓存了哪些东西"的真相来源。
 *
 * - [seriesId] 电影为 null;[seasonNumber]/[episodeNumber] 用于按剧集顺序排序,
 *   缺失时(电影、或元数据不全的条目)由调用方排在最后,这张表本身不做排序假设。
 * - 主键含 [serverId]:缓存按服务器隔离,换服务器即视为失效——多服务器是 v1 明确支持的场景,
 *   不同服务器上的 itemId 没有任何关系。
 */
@Entity(tableName = "cached_audio", primaryKeys = ["serverId", "itemId"])
data class CachedAudioEntity(
    val serverId: String,
    val itemId: String,
    val seriesId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val filePath: String,
    val sizeBytes: Long,
    val completedAt: Long,
    val lastAccessAt: Long,
)

@Dao
interface CachedAudioDao {

    @Query("SELECT * FROM cached_audio WHERE serverId = :serverId")
    suspend fun findByServer(serverId: String): List<CachedAudioEntity>

    @Query("SELECT * FROM cached_audio WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun findByItemId(serverId: String, itemId: String): CachedAudioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CachedAudioEntity)

    @Query("DELETE FROM cached_audio WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)

    /** 换服务器/删服务器时清空该服务器的全部缓存索引。 */
    @Query("DELETE FROM cached_audio WHERE serverId = :serverId")
    suspend fun clearServer(serverId: String)

    @Query("UPDATE cached_audio SET lastAccessAt = :lastAccessAt WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun touch(serverId: String, itemId: String, lastAccessAt: Long)

    /**
     * 该服务器已缓存音频的总占用字节数,用于总量封顶的驱逐决策。
     *
     * 表为空(或该 serverId 一行都没有)时,SQLite 的裸 `SUM()` 返回 NULL,这里用 `COALESCE`
     * 显式收敛成 0——调用方(驱逐决策)拿到的永远是一个确定的 `Long`,不需要在每个调用点
     * 判空;否则"没缓存"和"查询失败"在类型上区分不出来,一次遗漏的判空就会在驱逐逻辑里
     * 崩溃,或者更隐蔽地把 null 当成"无限空间"处理。
     */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_audio WHERE serverId = :serverId")
    suspend fun totalSizeBytes(serverId: String): Long
}
