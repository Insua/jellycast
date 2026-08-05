package dev.insua.jellycast.cache

import android.content.Context
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.database.CachedAudioEntity
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * [AudioCacheStore.store] 需要的、DAO 之外的最小元数据。排序信息([seasonNumber]/[episodeNumber])
 * 只是原样透传给索引行——这一层不做任何排序假设,那是 `:core:cache` 里 `planCache`(Task 2)的事。
 */
data class AudioCacheMeta(
    val seriesId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/**
 * 音频缓存的文件系统真相来源:哪个条目"已经缓存完成、可以离线播放",最终由这里说了算,
 * 而不是随便哪里查一下文件存不存在。
 *
 * ## 铁律:不完整的文件绝不能被当成可播放的
 *
 * [store] 下载先写 `<itemId>.part` 临时文件,**只有整个传输成功之后**才 rename 成正式文件、
 * 再写索引行——这个顺序不能变。半个文件如果被当成完整的,播放表现就是"播到一半突然断掉",
 * 且很难在事后诊断出根因;而先写索引再下载,一旦进程中途被杀,索引里就会有一行指向根本不存在
 * (或不完整)的文件,后续任何一次 [pathIfComplete] 都会把这行脏数据当真。
 *
 * rename 和写索引这两步之间(概率很低,但不是零)如果进程被杀,会留下"文件已经是正式名、但
 * 索引里没有它"的孤儿——这类孤儿不会骗到 [pathIfComplete](它只信索引,不会去扫目录把陌生文件
 * 当成已缓存),但会一直占地盘,由 [sweepOrphans] 在启动时兜底清掉。
 *
 * ## 存储位置
 *
 * 优先 `context.getExternalFilesDir("audio-cache")`,拿不到才退回 `context.filesDir` 下的同名
 * 子目录。**绝不用 `cacheDir`**——系统会在设备存储紧张时无声清空它,而这里最大可能放到 10 GB,
 * 一旦被系统悄悄清空,"已缓存、可离线播放"这个承诺就无声无息地失效了,用户毫无察觉直到真的
 * 断网或走弱网时才会发现。
 *
 * ## 失败即静默降级
 *
 * 缓存是锦上添花,不是播放的必要条件——这里任何一个方法遇到 DAO 异常或文件 I/O 异常都不向上
 * 抛错,统一退化成"当作没缓存"(`pathIfComplete` 返回 null / `store` 返回 false / 其余方法
 * 什么都不做)。唯独 [CancellationException] 必须重抛,不能被兜底的 `catch (e: Exception)` 吞掉
 * ——因此这里不用 `runCatching` 包 suspend 调用,而是显式 `catch (e: CancellationException) { throw e }`
 * 再 `catch (e: Exception)`,和 `:core:player` 的 `ProgressReporter` 是同一个形状。
 */
class AudioCacheStore(
    context: Context,
    private val dao: CachedAudioDao,
    private val downloader: AudioCacheDownloader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rootDir: File =
        (context.getExternalFilesDir(CACHE_DIR_NAME) ?: File(context.filesDir, CACHE_DIR_NAME))
            .apply { mkdirs() }

    /**
     * 索引里有记录只是"曾经缓存完成过"的声明,这里额外校验文件真的还在——文件可能被用户手动
     * 清了 app 私有目录、或者别的什么方式被外部删掉了。校验失败时顺手把这条脏记录也从索引里
     * 清掉,不让它继续骗后续的容量统计([totalBytes])和驱逐决策([dao.findByServer] 的调用方)。
     */
    suspend fun pathIfComplete(serverId: String, itemId: String): String? {
        val entity = findSafely(serverId, itemId) ?: return null
        if (File(entity.filePath).exists()) return entity.filePath
        deleteSafely(serverId, itemId)
        return null
    }

    /**
     * 下载 [sourceUrl] 到临时 `.part` 文件,完全成功之后才 rename 成正式文件并写索引行。
     * 任何一步失败(下载失败/取消、rename 失败、索引写入失败)都清理掉半成品并返回 false,
     * 不抛错——但 [CancellationException] 除外,必须重抛。
     *
     * ⚠️ "先 rename 再写索引"这个顺序是本类唯一的正确性核心,变异测试(Task 3 brief Step 5)
     * 就是把这个顺序倒过来验证:一旦索引在下载开始时就写入,取消下载后索引里会残留一行指向
     * 还没完工的文件,直接破坏"不完整文件不可播"这条铁律。
     */
    suspend fun store(
        serverId: String,
        itemId: String,
        meta: AudioCacheMeta,
        sourceUrl: String,
    ): Boolean {
        val dir = File(rootDir, serverId).apply { mkdirs() }
        val finalFile = File(dir, itemId)
        val partFile = File(dir, "$itemId$PART_SUFFIX")

        val downloaded = try {
            downloader.download(sourceUrl, partFile)
        } catch (e: CancellationException) {
            partFile.delete()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            false
        }
        if (!downloaded) {
            partFile.delete()
            return false
        }

        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            return false
        }

        val now = clock()
        return try {
            dao.insert(
                CachedAudioEntity(
                    serverId = serverId,
                    itemId = itemId,
                    seriesId = meta.seriesId,
                    seasonNumber = meta.seasonNumber,
                    episodeNumber = meta.episodeNumber,
                    filePath = finalFile.absolutePath,
                    sizeBytes = finalFile.length(),
                    completedAt = now,
                    lastAccessAt = now,
                )
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 文件已经落地成正式名,但索引没写进去——是个孤儿,但不在这里删文件:这次 DAO 调用
            // 可能只是偶发失败(比如数据库正在迁移),真正的兜底交给 sweepOrphans,这里删了反而
            // 白白扔掉一次本来已经成功的下载。
            false
        }
    }

    /** 同时删文件与索引;索引找不到对应记录时只是安静地什么都不做,不是错误。 */
    suspend fun delete(serverId: String, itemId: String) {
        val entity = findSafely(serverId, itemId)
        deleteSafely(serverId, itemId)
        if (entity != null) {
            try {
                File(entity.filePath).delete()
            } catch (e: Exception) {
                // 静默:索引已经清空,文件删不掉顶多是白占一点空间,不影响"这条不算已缓存"这个结论。
            }
        }
    }

    suspend fun totalBytes(serverId: String): Long = try {
        dao.totalSizeBytes(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0L
    }

    suspend fun touch(serverId: String, itemId: String) {
        try {
            dao.touch(serverId, itemId, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:touch 只是访问时间这一个元数据字段,丢一次不影响缓存本身是否可用这个结论。
        }
    }

    /**
     * 启动时的孤儿清扫:某个服务器目录下,索引里查不到对应记录的文件,以及任何 `.part` 临时
     * 文件,一律删掉。
     *
     * `.part` 文件无条件删,不做"是不是很新、可能还在下载"之类的判断——调用这个方法的前提就是
     * "进程刚启动",而一个真正在下载的 `.part` 只可能在本进程存活期间由 [store] 持有,进程重启
     * 后遗留的 `.part` 只可能是上次异常退出留下的半成品,没有第三种可能。
     *
     * 查索引本身失败(数据库损坏/迁移中)时整个跳过,不做任何删除——查不清楚"哪些文件有主"的
     * 时候乱删,风险比"这次没清成孤儿"更大。
     */
    suspend fun sweepOrphans(serverId: String) {
        val dir = File(rootDir, serverId)
        val files = dir.listFiles() ?: return

        val knownPaths = try {
            dao.findByServer(serverId).mapTo(mutableSetOf()) { it.filePath }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }

        for (file in files) {
            if (file.name.endsWith(PART_SUFFIX) || file.absolutePath !in knownPaths) {
                file.delete()
            }
        }
    }

    private suspend fun findSafely(serverId: String, itemId: String): CachedAudioEntity? = try {
        dao.findByItemId(serverId, itemId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun deleteSafely(serverId: String, itemId: String) {
        try {
            dao.delete(serverId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:见类注释——DAO 异常一律降级为"当作没这条记录",不打断调用方。
        }
    }

    private companion object {
        const val CACHE_DIR_NAME = "audio-cache"
        const val PART_SUFFIX = ".part"
    }
}
