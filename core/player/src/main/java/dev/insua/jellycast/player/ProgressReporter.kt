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

    /**
     * 设计文档 §2.3 规则 1:一个条目的上报生命周期以 `stop` 终结。见 [FinishedItemRegistry]
     * 的类注释 —— 那里写着为什么服务端逼得客户端必须自己记这件事。
     */
    private val finishedItems = FinishedItemRegistry()

    override suspend fun start(itemId: String, sessionId: String?, positionMs: Long) =
        dispatch(KIND_START, itemId, sessionId, positionMs)

    override suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) =
        dispatch(KIND_PROGRESS, itemId, sessionId, positionMs)

    override suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) =
        dispatch(KIND_STOP, itemId, sessionId, positionMs)

    /**
     * 一次实时上报:先按生命周期规则决定要不要发,再发。
     *
     * 三个 `when` 分支就是设计文档 §2.3 的规则 1 和规则 2:
     * - `start` 清除终结标记(重看语义,和服务端一致)
     * - `stop` 打上终结标记,并清空该条目在补报队列里的所有旧记录
     * - `progress` 遇到已终结的条目直接丢弃 —— 不发、不入队
     */
    private suspend fun dispatch(kind: String, itemId: String, sessionId: String?, positionMs: Long) {
        when (kind) {
            KIND_START -> finishedItems.clearFinished(itemId)
            KIND_STOP -> {
                finishedItems.markFinished(itemId)
                // 铁律:上报路径上的任何失败都不得打断播放,但 CancellationException 必须重抛
                // ——`runCatching` 会连它也吞掉,导致取消信号在这里被吸收、协程无法正常unwind。
                // 清队列失败(数据库损坏/迁移失败)本身不例外:顶多是漏掉一条旧记录,而重放侧
                // 还有一层同样的判断兜着。
                try {
                    dao.deleteForItem(serverId, itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 静默:见上方注释。
                }
            }
            KIND_PROGRESS -> if (finishedItems.isFinished(itemId)) return
        }
        reportOrEnqueue(kind, itemId, sessionId, positionMs)
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

        /** 成功补报的 + 主动跳过的 + 重试次数用尽被放弃的 —— 三者都要从队列里删掉。 */
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

    /**
     * 重放一条补报记录。
     *
     * 🔴 设计文档 §1.2 根因 A:补报重放是「每次源就绪」都会跑的,而自动连播的顺序恰好是
     * 「上一集 stop → 推进 → 下一集就绪 → flushPending」—— 队列里那条上一集的旧心跳正好在
     * 它已经被服务端标记播完之后被重放回去,把进度写了回去。所以已终结条目的旧 `start`/
     * `progress` 一律作废(它们必然早于那条 `stop`);唯独 `stop` 要放行,它是这一集真正的收尾。
     *
     * 跳过 = 正常返回 = 被 [flushPendingLocked] 当作已了结从队列里删掉,这是有意的:
     * 这条记录已经没有任何意义,留着只会让后面每一次 seek 都白白重试一遍。
     */
    private suspend fun replay(entry: ProgressReportEntity) {
        if (entry.kind != KIND_STOP && finishedItems.isFinished(entry.itemId)) return
        send(entry.kind, entry.itemId, entry.playSessionId, entry.positionMs)
    }

    private suspend fun reportOrEnqueue(
        kind: String,
        itemId: String,
        sessionId: String?,
        positionMs: Long,
    ) {
        try {
            send(kind, itemId, sessionId, positionMs)
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

    /**
     * 实时上报和补报重放唯一的出口。ticks 换算(项目铁律)只在这一处发生:
     * `PositionTicks = positionMs * 10_000`。
     */
    private suspend fun send(kind: String, itemId: String, sessionId: String?, positionMs: Long) {
        val ticks = positionMs * TICKS_PER_MS
        when (kind) {
            KIND_START -> api.reportStart(PlaybackStartInfoDto(itemId, sessionId, ticks))
            KIND_PROGRESS -> api.reportProgress(PlaybackProgressInfoDto(itemId, sessionId, ticks))
            KIND_STOP -> api.reportStop(PlaybackStopInfoDto(itemId, sessionId, ticks))
            else -> Unit // 未知 kind:静默丢弃,不阻塞其余记录的补报
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
