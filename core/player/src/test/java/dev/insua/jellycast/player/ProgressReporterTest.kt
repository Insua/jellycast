package dev.insua.jellycast.player

import dev.insua.jellycast.database.ProgressReportDao
import dev.insua.jellycast.database.ProgressReportEntity
import dev.insua.jellycast.network.JellyfinApi
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProgressReporterTest {
    @Test fun `上报失败时入队等待补报`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportProgress(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 1) { dao.enqueue(match { it.itemId == "ep1" && it.positionMs == 5000L }) }
    }

    @Test fun `上报成功时不入队`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 0) { dao.enqueue(any()) }
    }

    @Test fun `flushPending 成功后删除已补报记录`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { dao.pending(any(), any()) } returns listOf(
            ProgressReportEntity(1, "s1", "ep1", null, 1000, "progress", 1)
        )
        ProgressReporter(api, dao, "s1").flushPending()
        coVerify { dao.delete(listOf(1L)) }
    }

    // ---- 铁律:start / stop 也不得向上抛错,失败必须入队(不只是 progress) ----

    @Test fun `start 上报失败时入队且不抛出`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportStart(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").start("ep1", "sess", 1000)   // 不应抛出
        coVerify(exactly = 1) {
            dao.enqueue(match { it.itemId == "ep1" && it.kind == "start" && it.positionMs == 1000L })
        }
    }

    @Test fun `stop 上报失败时入队且不抛出`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportStop(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").stop("ep1", "sess", 9000)   // 不应抛出
        coVerify(exactly = 1) {
            dao.enqueue(match { it.itemId == "ep1" && it.kind == "stop" && it.positionMs == 9000L })
        }
    }

    @Test fun `progress 上报体的 PositionTicks 是毫秒乘以 10000`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5_000)
        coVerify { api.reportProgress(match { it.positionTicks == 50_000_000L }) }
    }

    // ---- Task 7 实现者提出的并发隐患:flushPending 必须串行化,不能重复上报 ----

    @Test fun `并发调用 flushPending 不会重复上报`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>()
        var remaining = listOf(ProgressReportEntity(1, "s1", "ep1", null, 1000, "progress", 1))

        // 用一个真实的 delay 制造挂起点:如果 flushPending 没有被串行化,两次并发调用都会在
        // delay 期间读到同一份"未被删除"的 remaining,导致重复上报。
        coEvery { dao.pending(any(), any()) } coAnswers { delay(10); remaining }
        coEvery { dao.delete(any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            remaining = remaining.filterNot { it.id in ids }
        }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            launch { reporter.flushPending() }
            launch { reporter.flushPending() }
        }

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "ep1" }) }
    }

    // ---- 复审 Finding 2:多服务器场景下,补报队列必须按 serverId 隔离 ----

    /**
     * 内存版 [ProgressReportDao]:故意模仿修复前的 bug——`pending()` 不按 serverId 过滤,
     * 直接返回所有服务器的行。先用这个版本证明新测试真的会失败(TDD 的"确认失败"步骤),
     * 再改成真实语义(按 serverId 过滤)使测试转绿。
     */
    private class FakeProgressReportDao : ProgressReportDao {
        val store = mutableListOf<ProgressReportEntity>()
        private var nextId = 1L

        override suspend fun enqueue(e: ProgressReportEntity) {
            store += e.copy(id = nextId++)
        }

        override suspend fun pending(serverId: String, limit: Int): List<ProgressReportEntity> =
            store.filter { it.serverId == serverId }.sortedBy { it.createdAt }.take(limit)

        override suspend fun delete(ids: List<Long>) {
            store.removeAll { it.id in ids }
        }

        override suspend fun deleteForItem(serverId: String, itemId: String) {
            store.removeAll { it.serverId == serverId && it.itemId == itemId }
        }
    }

    @Test fun `flushPending 只重放和删除属于自己 serverId 的记录,不影响其他 server`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = FakeProgressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "sA", itemId = "a1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 1
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "sB", itemId = "b1", playSessionId = null,
                positionMs = 2000, kind = "progress", createdAt = 2
            )
        )

        ProgressReporter(api, dao, "sA").flushPending()

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "a1" }) }
        coVerify(exactly = 0) { api.reportProgress(match { it.itemId == "b1" }) }
        assertEquals(listOf("b1"), dao.store.map { it.itemId })
    }

    // ---- 复审 Minor:补报必须有失败上限,不能让一行毒记录永远占着队列 ----

    /**
     * 缺陷现场:`flushPending` 只删除**成功**的行。一条永远失败的记录(最典型的是条目已在服务端
     * 被删除 → 404)会永久留在队列里,而 `flushPending` 是"每次源就绪"都调的 —— 也就是**每次 seek
     * 都重试一遍**,而且顺带拖慢每一次 seek。
     *
     * 修正:补报是"尽力而为"。连续失败达到上限就丢弃这一行。为什么这个上限是安全的:
     * `flushPending` 只在一次 resolve **成功**之后被调用,也就是"服务器此刻可达"已经被证明过了 ——
     * 在服务器可达的前提下连着失败 3 次,基本只能是这条记录本身有问题,不是网络问题。
     */
    @Test fun `连续失败达到上限后丢弃该补报记录,不再无限重试`() = runTest {
        val api = mockk<JellyfinApi>()
        coEvery { api.reportProgress(any()) } throws java.io.IOException("item deleted")
        val dao = FakeProgressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "s1", itemId = "gone", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 1
            )
        )
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.flushPending()
        assertEquals(1, dao.store.size)     // 第 1 次失败:留着,可能只是一次网络抖动
        reporter.flushPending()
        assertEquals(1, dao.store.size)     // 第 2 次失败:还留着
        reporter.flushPending()
        assertEquals(0, dao.store.size)     // 第 3 次失败:放弃,别再拖累后面每一次 seek

        coVerify(exactly = 3) { api.reportProgress(any()) }
        reporter.flushPending()
        coVerify(exactly = 3) { api.reportProgress(any()) }   // 已经不在队列里了,不会再重试
    }

    /** 铁律:进度上报失败绝不打断播放。`dao` 本身抛异常(数据库损坏/迁移失败)也不得向上抛。 */
    @Test fun `flushPending 在 dao 抛异常时不向上抛错`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>()
        coEvery { dao.pending(any(), any()) } throws IllegalStateException("database corrupted")

        ProgressReporter(api, dao, "s1").flushPending()   // 不应抛出
    }

    // ---- 设计文档 §2.3 规则 1 / 规则 2:条目的上报生命周期以 stop 终结 ----

    /**
     * 🔴 现场缺陷的核心。实测的服务端行为(设计文档 §1.1):已被标记播完的条目再收到一条
     * `progress`,播放位置就会被写回去,而已播放的勾不会撤销 —— 这一集于是永远赖在
     * 「继续观看」里,还带着一条进度条。
     */
    @Test fun `stop 之后同一条目的 progress 不发出也不入队`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.stop("ep1", "sess", 2_400_000)
        reporter.progress("ep1", "sess", 60_000)

        coVerify(exactly = 0) { api.reportProgress(any()) }
        coVerify(exactly = 0) { dao.enqueue(match { it.kind == "progress" }) }
    }

    /** 终结标记必须只作用于被终结的那个条目。 */
    @Test fun `stop 之后其它条目的 progress 照常发出`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.stop("ep1", "sess", 2_400_000)
        reporter.progress("ep2", "sess", 60_000)

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "ep2" }) }
    }

    /**
     * `stop` 本身必须放行 —— `PlaybackService.onDestroy` 的兜底 stop 和 `STATE_ENDED` 的 stop
     * 可能落在同一条目上,重发一条位置相同的 stop 对服务端无害,把它挡掉反而会丢掉真正的收尾。
     */
    @Test fun `stop 之后同一条目的 stop 仍然发出`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.stop("ep1", "sess", 2_400_000)
        reporter.stop("ep1", "sess", 2_400_000)

        coVerify(exactly = 2) { api.reportStop(any()) }
    }

    /** 重看:`start` 清除终结标记,之后的心跳恢复正常。 */
    @Test fun `start 清除终结标记,之后的 progress 恢复发出`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val reporter = ProgressReporter(api, dao, "s1")

        reporter.stop("ep1", "sess", 2_400_000)
        reporter.start("ep1", "sess2", 0)
        reporter.progress("ep1", "sess2", 60_000)

        coVerify(exactly = 1) { api.reportProgress(match { it.itemId == "ep1" }) }
    }

    /** 规则 2:发 stop 之前先清空该条目在队列里的旧记录。 */
    @Test fun `stop 之前先删除该条目在补报队列里的旧记录`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)

        ProgressReporter(api, dao, "s1").stop("ep1", "sess", 2_400_000)

        coVerify(exactly = 1) { dao.deleteForItem("s1", "ep1") }
    }

    /** 铁律:上报失败不得打断播放 —— 连清队列这一步自己失败了,stop 也必须照发。 */
    @Test fun `清空队列失败不影响 stop 的发出`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { dao.deleteForItem(any(), any()) } throws IllegalStateException("database corrupted")

        ProgressReporter(api, dao, "s1").stop("ep1", "sess", 2_400_000)   // 不应抛出

        coVerify(exactly = 1) { api.reportStop(any()) }
    }

    /**
     * 🔴 §1.2 根因 A 的直接复现。补报重放是「每次源就绪」都跑的,而自动连播的顺序是
     * 「上一集 stop → 推进到下一集 → 下一集就绪 → flushPending」——
     * 队列里那条上一集的旧心跳正好在它已经被标记播完之后被重放回去。
     */
    @Test fun `重放时跳过已终结条目的旧 progress,并把它从队列里清掉`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        // spyk 而不是 FakeProgressReportDao():stop 自己的 deleteForItem 会在发 stop 之前
        // 就把这条旧记录清掉,导致队列在 flushPending 之前已经是空的 —— 那样 replay() 里
        // 的终结标记判断根本不会被走到,测出来的是「清队列」这一层,不是这条测试真正要盯的
        // 「重放」这一层。这里把 deleteForItem 打桩成失败,让旧记录活着进入 flushPending(),
        // 真正验证 replay() 的 isFinished 判断兜住了它。
        val dao = spyk(FakeProgressReportDao())
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "s1", itemId = "ep1", playSessionId = null,
                positionMs = 60_000, kind = "progress", createdAt = 1
            )
        )
        coEvery { dao.deleteForItem(any(), any()) } throws IllegalStateException("database corrupted")
        val reporter = ProgressReporter(api, dao, "s1")

        // 这一集播完了,但队列清理失败(模拟数据库故障)—— 旧的 progress 记录活着留在队列里,
        // 全靠 replay() 的终结标记判断这第二层防御把它挡掉。
        reporter.stop("ep1", "sess", 2_400_000)
        reporter.flushPending()

        coVerify(exactly = 0) { api.reportProgress(any()) }
        assertEquals(emptyList<String>(), dao.store.map { it.itemId })
    }

    /** 已终结条目的**队列里的 stop** 仍然要重放 —— 它是这一集真正的收尾,丢了就白播了。 */
    @Test fun `重放时不跳过已终结条目的 stop`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = FakeProgressReportDao()
        val reporter = ProgressReporter(api, dao, "s1")

        // stop 上报失败 → 入队;此时该条目已被标成终结。
        coEvery { api.reportStop(any()) } throws java.io.IOException("offline")
        reporter.stop("ep1", "sess", 2_400_000)
        clearMocks(api, answers = false, recordedCalls = true)
        coEvery { api.reportStop(any()) } returns Unit

        reporter.flushPending()

        coVerify(exactly = 1) { api.reportStop(match { it.itemId == "ep1" }) }
        assertEquals(emptyList<String>(), dao.store.map { it.itemId })
    }

    // ---- 设计文档 §2.3 规则 3:所有上报串行,保证到达服务端的先后就是发出的先后 ----

    /**
     * 🔴 设计文档 §1.2 根因 B。10 秒心跳的 `progress` 是一个挂起的网络请求;它在飞的这两三秒里
     * 如果播放正好结束,`stop` 会另起一条连接发出去 —— 两条请求谁先到服务端**没有任何保证**。
     * 心跳后到,服务端就把位置写成了心跳那个值,而已播放的勾还留着。
     *
     * 判别办法:让 `reportProgress` 卡住不返回,在这期间发一条 `stop`,断言 `stop` **没有**
     * 在心跳完成之前被发出去。串行化之前 stop 会立刻穿过去,这条断言就红。
     */
    @Test fun `stop 必须等在飞的 progress 完成之后才发出`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val order = mutableListOf<String>()
        val progressGate = CompletableDeferred<Unit>()

        coEvery { api.reportProgress(any()) } coAnswers {
            progressGate.await()
            order += "progress"
        }
        coEvery { api.reportStop(any()) } coAnswers { order += "stop" }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            val heartbeat = launch { reporter.progress("ep1", "sess", 60_000) }
            runCurrent()
            val ending = launch { reporter.stop("ep1", "sess", 2_400_000) }
            runCurrent()

            assertEquals(
                emptyList<String>(),
                order,
                "心跳还卡在网络里,stop 不该抢先发出去",
            )

            progressGate.complete(Unit)
            heartbeat.join()
            ending.join()
        }

        assertEquals(listOf("progress", "stop"), order)
    }

    /**
     * 反过来的交错:`stop` 先拿到锁,排在后面的心跳醒来时该条目已经终结 —— 必须被丢弃,
     * 而不是等 stop 发完再补一条上去。终结判定因此**必须在锁内**做:如果判定被挪到锁外,
     * 一条在 `stop` 真正执行 `markFinished` 之前就完成判定(读到"未终结")的心跳,会绕过
     * 判定直接排队等锁,锁轮到它时不会再复查,照样把 progress 发出去。
     *
     * 要让这条测试真正区分"判定在锁内 / 判定在锁外"这两种实现,必须制造一个时间窗口,让心跳
     * 的判定**先于** `stop` 的 `markFinished` 执行,然后才轮到心跳去抢锁。只让 `stop` 和
     * `progress` 两个角色错开 `runCurrent()` 是不够的 —— `markFinished` 是锁内代码,不会在
     * `stop` 拿到锁之前跑,所以心跳无论判定在锁内还是锁外,读到的都会是"未终结"这同一个值,
     * 两种实现测出来的结果没有差别(用变异验证过,见 fix report)。
     *
     * 因此这里引入第三个角色:一条无关条目 `other` 的心跳先卡在网络里、占住 `reportMutex`。
     * 这样当 `stop("ep1", …)` 发出时,它连锁都还没排上,`markFinished("ep1")` 自然也没执行;
     * 紧接着 `progress("ep1", …)` 入场——如果判定在锁外,它此刻就会读到"未终结"并直接排队
     * 等锁;如果判定在锁内,它同样排队,但轮到它时会在锁内重新判定。占位的心跳一放行,
     * `stop` 先拿到锁执行 `markFinished` 并发出,`ep1` 的心跳随后拿到锁——锁内判定版本此时
     * 才会把它丢弃,锁外判定版本则会照样把它发出去。
     */
    @Test fun `stop 先拿到锁时,排在后面的 progress 在锁内被终结判定丢弃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val otherGate = CompletableDeferred<Unit>()
        coEvery { api.reportProgress(match { it.itemId == "other" }) } coAnswers { otherGate.await() }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            // 占住 reportMutex,制造"markFinished 还没跑"的窗口。
            val holder = launch { reporter.progress("other", "sess", 1000) }
            runCurrent()

            // stop 此刻连锁都还没排上,markFinished("ep1") 尚未执行。
            val ending = launch { reporter.stop("ep1", "sess", 2_400_000) }
            runCurrent()

            // 心跳入场:锁外判定的话,它现在就会读到"未终结"。
            val heartbeat = launch { reporter.progress("ep1", "sess", 60_000) }
            runCurrent()

            otherGate.complete(Unit)
            holder.join()
            ending.join()
            heartbeat.join()
        }

        coVerify(exactly = 1) { api.reportStop(match { it.itemId == "ep1" }) }
        coVerify(exactly = 0) { api.reportProgress(match { it.itemId == "ep1" }) }
    }

    /**
     * 补报重放必须**逐条**获取上报锁,不能整批持有:队列上限是 100 条,整批持有会把一条
     * 实时 `stop` 堵在一次满队列重放的后面。
     *
     * 光看"最终"调用次数测不出这一点——批量持锁和逐条持锁在 `flush.join(); ending.join()`
     * 之后的最终调用计数是一样的,差别只在于 `stop` **能在虚拟时间的哪一刻**发出去(用变异
     * 验证过,见 fix report:把锁挪到包住整个 for 循环,原本的断言依然全绿)。
     *
     * 所以断言必须卡在中途的一个检查点:给重放批次里的三条记录各配一个独立的网关,只放行
     * 第一条。逐条持锁的话,第一条一发完就会释放锁,而排在锁队列里的实时 `stop`(先于第二条
     * 重放开始等锁)会先于第二条抢到锁并发出去——这时第二、三条还各自卡在网关里没有执行。
     * 整批持锁的话,锁要等整个 for 循环跑完才会释放,这个检查点上 `stop` 必然一次都没发出去。
     */
    @Test fun `补报重放逐条持锁,实时 stop 能在第一条重放完成后立刻插队`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = FakeProgressReportDao()
        repeat(3) { index ->
            dao.enqueue(
                ProgressReportEntity(
                    id = 0, serverId = "s1", itemId = "old$index", playSessionId = null,
                    positionMs = 1000, kind = "progress", createdAt = index.toLong()
                )
            )
        }
        val gates = List(3) { CompletableDeferred<Unit>() }
        var replayed = 0
        coEvery { api.reportProgress(any()) } coAnswers { gates[replayed++].await() }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            val flush = launch { reporter.flushPending() }
            runCurrent() // 第一条重放拿到锁,卡在自己的网关里

            val ending = launch { reporter.stop("ep9", "sess", 2_400_000) }
            runCurrent() // 实时 stop 排队等锁,此时第一条重放还没放行

            gates[0].complete(Unit)
            runCurrent()

            // 检查点:第一条重放刚完成。逐条持锁的话,锁已经被释放又被排队中的 stop
            // 抢先拿走并发完了;整批持锁的话,锁还攥在 flush 手里,第二条正在跑,
            // stop 一次都还没发出去。
            coVerify(exactly = 1) { api.reportStop(match { it.itemId == "ep9" }) }

            gates[1].complete(Unit)
            gates[2].complete(Unit)
            flush.join()
            ending.join()
        }
    }
}
