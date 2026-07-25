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
) {
    /**
     * [ProgressReportDao.pending] 没有取走/加锁标记:如果 [flushPending] 被并发触发(例如两次
     * 网络恢复回调连续到达),两次调用会读到重叠的行、重复上报。用 [Mutex] 把 flushPending 串行
     * 化——后一次调用必然在前一次完全提交(报完 + 删完)之后才读 pending(),不会重叠。
     */
    private val flushMutex = Mutex()

    suspend fun start(itemId: String, sessionId: String?, positionMs: Long) {
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

    suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) {
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

    suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) {
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

    suspend fun flushPending(): Unit = flushMutex.withLock {
        val pending = dao.pending(serverId)
        if (pending.isEmpty()) return@withLock

        val succeededIds = mutableListOf<Long>()
        for (entry in pending) {
            val result = runCatching { replay(entry) }
            if (result.isSuccess) succeededIds += entry.id
        }
        if (succeededIds.isNotEmpty()) dao.delete(succeededIds)
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
        const val TICKS_PER_MS = 10_000L
        const val KIND_START = "start"
        const val KIND_PROGRESS = "progress"
        const val KIND_STOP = "stop"
    }
}
