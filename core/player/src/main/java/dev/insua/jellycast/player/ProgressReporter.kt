package dev.insua.jellycast.player

import dev.insua.jellycast.database.ProgressReportDao
import dev.insua.jellycast.database.ProgressReportEntity
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.PlaybackProgressInfoDto
import dev.insua.jellycast.network.dto.PlaybackStartInfoDto
import dev.insua.jellycast.network.dto.PlaybackStopInfoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 播放进度双向同步(设计文档 §8):实时上报 Jellyfin,失败(离线 / 服务器不可达 / endpoint
 * 切换中)时静默入队本地补报队列,[flushPending] 在联网后重放。
 *
 * 硬要求(设计文档 §8):任何 `IOException` / 非 2xx 都不得向上抛错、不得打断播放——
 * `start` / `progress` / `stop` 三个方法都要遵守,不只是 `progress`。Retrofit 的 suspend 函数对
 * 非 2xx 响应会抛 `HttpException`,和 `IOException` 一样在这里统一被捕获。
 *
 * ticks 换算只在这一层做一次:`PositionTicks = positionMs * 10_000`,项目铁律,不让 ticks
 * 逃逸到上层。
 *
 * 请求体用 Task 3 已修复的 [PlaybackStartInfoDto] / [PlaybackProgressInfoDto] /
 * [PlaybackStopInfoDto],不用 `Map<String, Any>`(kotlinx.serialization 没有 `Any` 的序列化器,
 * 会在运行时抛异常)。
 */
class ProgressReporter(
    private val api: JellyfinApi,
    private val dao: ProgressReportDao,
    private val serverId: String,
) : ProgressSink {
    /**
     * [ProgressReportDao.pending] 没有取走/加锁标记:如果 [flushPending] 被并发触发(例如两次
     * 网络恢复回调连续到达),两次调用会读到重叠的行、重复上报。用 [Mutex] 把 flushPending 串行
     * 化——后一次调用必然在前一次完全提交(报完 + 删完)之后才读 pending(),不会重叠。
     */
    private val flushMutex = Mutex()

    /**
     * 每一行补报记录连续失败了几次(复审 Minor)。
     *
     * 缺陷现场:[flushPending] 只删除**成功**的行,于是一条永远失败的记录(最典型的是条目已在服务端
     * 被删除 → 404)会永久留在队列里;而 [flushPending] 是"每次源就绪"都调的,也就是**每次 seek 都
     * 重试一遍**,还顺带拖慢每一次 seek。
     *
     * 为什么"连续 [MAX_REPLAY_ATTEMPTS] 次就丢弃"是安全的:[flushPending] 只在一次 resolve **成功**
     * 之后被调用,"服务器此刻可达"已经被证明过了——在这个前提下连着失败三次,基本只能是这条记录本身
     * 有问题(条目没了 / 会话过期),不是网络问题。补报本来就是尽力而为,丢一条几分钟前的进度远好过
     * 让它无限期地拖累每一次 seek。
     *
     * 计数放在内存里而不是加一列到 Room:免掉一次 schema 迁移,而"进程活着的这段时间内不要反复重试
     * 同一条毒记录"正是要解决的问题;进程重启后重来一遍最多再试三次,依然是有界的。
     */
    private val replayFailures = mutableMapOf<Long, Int>()

    override suspend fun start(itemId: String, sessionId: String?, positionMs: Long) {
        reportOrEnqueue(KIND_START, itemId, sessionId, positionMs) {
            api.reportStart(
                PlaybackStartInfoDto(
                    itemId = itemId,
                    playSessionId = sessionId,
                    positionTicks = positionMs * TICKS_PER_MS,
                )
            )
        }
    }

    override suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) {
        reportOrEnqueue(KIND_PROGRESS, itemId, sessionId, positionMs) {
            api.reportProgress(
                PlaybackProgressInfoDto(
                    itemId = itemId,
                    playSessionId = sessionId,
                    positionTicks = positionMs * TICKS_PER_MS,
                )
            )
        }
    }

    override suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) {
        reportOrEnqueue(KIND_STOP, itemId, sessionId, positionMs) {
            api.reportStop(
                PlaybackStopInfoDto(
                    itemId = itemId,
                    playSessionId = sessionId,
                    positionTicks = positionMs * TICKS_PER_MS,
                )
            )
        }
    }

    /**
     * 铁律:进度上报失败绝不打断播放。这里连 [dao] 自己抛异常(数据库损坏 / 迁移失败)都得吞掉——
     * 调用方是 `PlaybackService` 里那条收集引擎状态的协程,一个异常逃出去就会把整条进度上报链路
     * 连带打死。
     */
    override suspend fun flushPending(): Unit = flushMutex.withLock {
        try {
            flushPendingLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默:补报是尽力而为,下一次源就绪还会再试。
        }
    }

    private suspend fun flushPendingLocked() {
        val pending = dao.pending(serverId)
        if (pending.isEmpty()) {
            replayFailures.clear()
            return
        }

        /** 成功补报的 + 重试次数用尽被放弃的 —— 两者都要从队列里删掉。 */
        val settledIds = mutableListOf<Long>()
        for (entry in pending) {
            if (runCatching { replay(entry) }.isSuccess) {
                replayFailures.remove(entry.id)
                settledIds += entry.id
                continue
            }
            val failures = (replayFailures[entry.id] ?: 0) + 1
            if (failures >= MAX_REPLAY_ATTEMPTS) {
                replayFailures.remove(entry.id)
                settledIds += entry.id       // 放弃这一行,见 replayFailures 的 KDoc
            } else {
                replayFailures[entry.id] = failures
            }
        }
        if (settledIds.isNotEmpty()) dao.delete(settledIds)
        // 已经不在队列里的计数没有意义(补报成功、被放弃、或者被别的路径删掉了),别让这个表长胖。
        replayFailures.keys.retainAll(pending.mapTo(mutableSetOf()) { it.id })
    }

    private suspend fun replay(entry: ProgressReportEntity) {
        when (entry.kind) {
            KIND_START -> api.reportStart(
                PlaybackStartInfoDto(entry.itemId, entry.playSessionId, entry.positionMs * TICKS_PER_MS)
            )
            KIND_PROGRESS -> api.reportProgress(
                PlaybackProgressInfoDto(entry.itemId, entry.playSessionId, entry.positionMs * TICKS_PER_MS)
            )
            KIND_STOP -> api.reportStop(
                PlaybackStopInfoDto(entry.itemId, entry.playSessionId, entry.positionMs * TICKS_PER_MS)
            )
            else -> Unit // 未知 kind:静默丢弃,不阻塞其余记录的补报
        }
    }

    private suspend fun reportOrEnqueue(
        kind: String,
        itemId: String,
        sessionId: String?,
        positionMs: Long,
        call: suspend () -> Unit,
    ) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.enqueue(
                ProgressReportEntity(
                    serverId = serverId,
                    itemId = itemId,
                    playSessionId = sessionId,
                    positionMs = positionMs,
                    kind = kind,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private companion object {
        /** 见 [replayFailures]:同一行连续失败到这个次数就放弃。 */
        const val MAX_REPLAY_ATTEMPTS = 3

        const val TICKS_PER_MS = 10_000L
        const val KIND_START = "start"
        const val KIND_PROGRESS = "progress"
        const val KIND_STOP = "stop"
    }
}
