package dev.insua.jellycast.cache

import android.content.Context
import dev.insua.jellycast.database.CachedAudioDao
import dev.insua.jellycast.database.CachedAudioEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

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
     * 复审 Important:[sweepOrphans] 只信"索引里有没有"，对它自己没有任何"这个文件是不是正在
     * 下载"的概念——它的正确性原本完全靠一条没有代码强制的约定"只在启动时调用"。但 Task 6
     * 马上就会真的接一个调用方,以后"清除缓存"这种用户触发的操作也会调它,这条约定迟早会被
     * 违反:[sweepOrphans] 如果和 [store] 并发跑在同一个 serverId 上,会把正在写的 `.part`
     * 文件从文件系统里 unlink 掉——写入 fd 仍然有效、能继续写完,但后续 `renameTo` 按路径找不到
     * 源文件,下载会莫名其妙地失败。
     *
     * 用一个内存态的"正在下载中"登记表堵住这个口子:只有 [store] 知道哪些下载正在飞,所以这个
     * 登记表就放在 [store] 里维护,而不是指望调用方自己排队/加锁。[ConcurrentHashMap] 的 key set
     * 是线程安全的——下载本身跑在 `Dispatchers.IO`,`sweepOrphans` 可能从任意调度器被调用,
     * 不能假设两者在同一个线程上。
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun inFlightKey(serverId: String, itemId: String) = "$serverId/$itemId"

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
     *
     * @param shouldContinue 复审 I4(Important):每读完一个缓冲块就问一次"还该不该继续"——
     *   生产环境([CachePrefetchController])接的是 `{ networkTypeMonitor.isOnWifi() }`,把设计
     *   文档 §3「断网/切到蜂窝时立即停止当前下载」下沉到字节拷贝这一层,而不是只在**下一个**
     *   下载开始前查一次(那样已经在传的这一集会传完整整一集蜂窝流量)。见 [AudioCacheDownloader]
     *   类注释「I4 复审」——中止时按普通下载失败处理(清 `.part`、不落索引),不是取消。
     */
    suspend fun store(
        serverId: String,
        itemId: String,
        meta: AudioCacheMeta,
        sourceUrl: String,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        val dir = File(rootDir, serverId).apply { mkdirs() }
        val finalFile = File(dir, itemId)
        val partFile = File(dir, "$itemId$PART_SUFFIX")

        // 登记"这个 itemId 正在下载",让并发跑的 sweepOrphans 别把 .part 文件当孤儿删掉。
        // finally 保证无论走哪条退出路径(成功/失败/取消)都会摘牌——finally 里只是同步的
        // 集合操作,不会挂起,协程被取消时照样会执行,不用担心这里漏掉摘牌导致这个 itemId
        // 从此再也扫不到。
        val key = inFlightKey(serverId, itemId)
        inFlight += key
        try {
            val downloaded = try {
                downloader.download(sourceUrl, partFile, shouldContinue)
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
        } finally {
            inFlight -= key
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

    /**
     * 删服务器时整体清空该服务器的音频缓存:索引里这台服务器的全部行 + 整个
     * `audio-cache/<serverId>/` 目录,递归删除。
     *
     * 复审 I2:[CachedAudioDao.clearServer] 在这个方法存在之前写好了、也测过,却从没有任何生产
     * 调用方——`ServerViewModel.confirmDeleteServer` 只清了 `CachedItemDao`(库浏览缓存)和
     * `LastPlayedStore`,音频缓存的索引行和文件目录被彻底遗漏。[sweepOrphans] 救不了这个场景:
     * 它只在**当前激活**服务器的目录上跑(见 `CachePrefetchController` 调用方),被删掉的服务器
     * 不再是激活服务器,从此再也没有任何代码会去扫它的目录——文件会一直占着地方,直到用户手动
     * 清 App 数据。
     *
     * 和这个类其余方法同一种"失败即静默降级"纪律:索引清不掉、文件删不掉都不向上抛错
     * (删除服务器这个操作本身的成败由 [CancellationException] 之外的异常单独判断,音频缓存
     * 顶多是清理不干净,不应该让删服务器这个更重要的操作失败)——但 [CancellationException]
     * 仍然必须重抛,不用 `runCatching` 包 suspend 调用,和类注释「失败即静默降级」是同一个理由。
     */
    suspend fun clearServer(serverId: String) {
        try {
            dao.clearServer(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:索引清不掉不阻止文件目录清理,也不阻止调用方(删服务器)本身的其余步骤。
        }
        try {
            File(rootDir, serverId).deleteRecursively()
        } catch (e: Exception) {
            // 静默:文件删不掉顶多是白占一点空间,不影响"这台服务器不再有已缓存内容"这个结论。
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
     * 文件,一律删掉——**除了** [inFlight] 里登记着的、[store] 正在下载中的那个 itemId。
     *
     * ⚠️ 复审 Important:文档上"只在启动时调用"这句话本身挡不住任何代码——Task 6 的预取控制器、
     * 以及未来"清除缓存"这类用户触发的操作,都会让这个方法和正在跑的 [store] 撞在同一个
     * serverId 上。不做这个判断的话,一个正在写的 `.part` 文件会被这里 unlink 掉:写入的
     * fd 还有效、能继续写完,但 [store] 之后按路径 `renameTo` 时源文件已经找不到了,下载会
     * 莫名其妙地失败——bug 现场和"孤儿清扫"这个功能本身完全对不上号,很难联想到根因。
     *
     * `.part` 文件（不在 [inFlight] 里的）无条件删,不做"是不是很新"之类的时间判断——一个不在
     * 登记表里的 `.part` 只可能是上次异常退出留下的半成品,没有第三种可能。
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
            val itemId = file.name.removeSuffix(PART_SUFFIX)
            if (inFlightKey(serverId, itemId) in inFlight) continue
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
