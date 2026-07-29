# 播放进度上报生命周期 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「已播完的剧集」在 Jellyfin 上真正保持播完状态 —— 打完勾之后不再被任何更旧的上报把播放位置写回去。

**Architecture:** 把「一个条目的上报生命周期以 `stop` 终结」这条规则落到 `ProgressReporter`(实时上报与队列重放的唯一收口)。三条子规则:终结标记后丢弃更旧的上报、发 `stop` 前清空该条目的补报队列、所有上报串行经过同一把锁保证全序。另给 `PlaybackProgressCoordinator` 的可变状态加锁,消除 `onDestroy`(IO 线程)与其余入口(主线程)之间缺失的 happens-before。

**Tech Stack:** Kotlin · kotlinx.coroutines `Mutex` · Room(仅新增查询,无 schema 变更)· JUnit5 + MockK(JVM)· AndroidJUnit4 + 真 Jellyfin 服务器(E2E)

**设计文档:** `docs/superpowers/specs/2026-07-29-progress-report-lifecycle-design.md` —— 服务端行为的实测结论在 §1.1,三条根因在 §1.2。

## Global Constraints

- **铁律:进度上报的任何失败都不得打断播放。** 新增代码同样适用 —— 不向上抛错,`CancellationException` 必须重抛。
- **ticks 换算只在 `ProgressReporter` 一处做:** `PositionTicks = positionMs * 10_000`。ticks 不得逃逸到别处。
- **不得引入 Room schema 变更。** 只允许新增 `@Query`,不得改 `@Entity`,不得写迁移。
- **凭据绝不出现在任何输出里:** 不许 log、不许拼进断言消息、不许写进 commit message 或任务报告。E2E 的失败信息一律经 `TestCredentials.redact` 脱敏。
- **不得改动 `ProgressSink` 接口的方法签名** —— `PlaybackService`、`PlaybackProgressCoordinator` 及其 17 个既有用例都依赖它。
- **每个 Task 结束必须 commit**,用 Conventional Commits(`feat:` / `fix:` / `test:` / `chore:` / `docs:`)。
- **JVM 单测命令:** `./gradlew :core:player:test`。**设备测试命令:** `./gradlew :core:database:connectedDebugAndroidTest` / `./gradlew :app:connectedDebugAndroidTest`。
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `core/database/src/main/java/dev/insua/jellycast/database/ProgressReportEntity.kt` | 补报队列的实体 + DAO | 新增 `deleteForItem` 查询 |
| `core/database/src/androidTest/java/dev/insua/jellycast/database/ProgressReportDaoTest.kt` | 用真实 Room 验证 DAO 语义 | 新增用例 |
| `core/player/src/main/java/dev/insua/jellycast/player/FinishedItemRegistry.kt` | **新建。** 「哪些条目的上报生命周期已终结」,有界 LRU,纯 Kotlin | 创建 |
| `core/player/src/test/java/dev/insua/jellycast/player/FinishedItemRegistryTest.kt` | **新建。** 标记/清除/淘汰语义 | 创建 |
| `core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt` | 上报唯一收口:三条子规则在此落地 | 重构 |
| `core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt` | 上报器行为 | 新增用例 |
| `core/player/src/main/java/dev/insua/jellycast/player/PlaybackProgressCoordinator.kt` | 「什么时候报什么」的决策 | 加锁 |
| `core/player/src/test/java/dev/insua/jellycast/player/PlaybackProgressCoordinatorTest.kt` | 协调器行为 | 新增用例 |
| `app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt` | 真 ExoPlayer + 真 Service + 真服务器 | 新增场景 |

**为什么终结标记单独成文件:** `ProgressReporter` 已经承担了「发请求 + 失败入队 + 补报重放 + 失败计数」四件事。把「哪些条目已终结」这份状态连同它的淘汰策略塞进去会让这个类继续膨胀,而它本身是纯数据结构、可独立测试。

**任务顺序:** Task 1 → 2 → 3 → 4 → 5,**串行执行**。Task 1/2/3 依次修改同一批文件;Task 4 虽然文件不重叠,但与 1-3 提交到同一分支,并行会在 git 索引上打架,收益不抵风险。

---

### Task 1: 补报队列支持按条目删除

**Files:**
- Modify: `core/database/src/main/java/dev/insua/jellycast/database/ProgressReportEntity.kt:24-34`
- Test: `core/database/src/androidTest/java/dev/insua/jellycast/database/ProgressReportDaoTest.kt`

**Interfaces:**
- Consumes: 无(本计划的第一个任务)
- Produces: `ProgressReportDao.deleteForItem(serverId: String, itemId: String)`,`suspend`,无返回值。Task 2 会调用它。

- [ ] **Step 1: 写失败的测试**

在 `ProgressReportDaoTest.kt` 的最后一个 `@Test` 之后、类的右花括号之前追加:

```kotlin
    /**
     * 设计文档 §2.3 规则 2:发出 `stop` 之前要把该条目在队列里的所有旧记录清掉,
     * 否则一条早期失败的心跳会在这一集被标记播完之后被重放回去,把进度写回服务端。
     *
     * 这里同时钉死两条边界:**同一服务器的其它条目**不受影响,**其它服务器的同名条目**也不受影响。
     */
    @Test
    fun `deleteForItem只删除该server该条目的记录`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep1", playSessionId = null,
                positionMs = 2000, kind = "start", createdAt = 20
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sA", itemId = "ep2", playSessionId = null,
                positionMs = 3000, kind = "progress", createdAt = 30
            )
        )
        dao.enqueue(
            ProgressReportEntity(
                serverId = "sB", itemId = "ep1", playSessionId = null,
                positionMs = 4000, kind = "progress", createdAt = 40
            )
        )

        dao.deleteForItem(serverId = "sA", itemId = "ep1")

        assertEquals(listOf("ep2"), dao.pending(serverId = "sA").map { it.itemId })
        assertEquals(listOf("ep1"), dao.pending(serverId = "sB").map { it.itemId })
    }

    @Test
    fun `deleteForItem对不存在的条目是无操作`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                serverId = "s", itemId = "ep1", playSessionId = null,
                positionMs = 1000, kind = "progress", createdAt = 10
            )
        )

        dao.deleteForItem(serverId = "s", itemId = "nobody")

        assertEquals(1, dao.pending(serverId = "s").size)
    }
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:database:compileDebugAndroidTestKotlin`

Expected: 编译失败,`Unresolved reference: deleteForItem`。

> 这个任务的「确认失败」发生在**编译期**而不是运行期 —— DAO 方法不存在,测试连编译都过不去。这依然是有效的红,不要跳过这一步就去写实现。

- [ ] **Step 3: 写最小实现**

在 `ProgressReportEntity.kt` 的 `ProgressReportDao` 接口里,`delete` 之后追加:

```kotlin
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
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*ProgressReportDaoTest*'`

Expected: PASS,6 个用例全绿(既有 4 个 + 新增 2 个)。

> 需要已连接的设备或模拟器。用 `adb devices` 确认至少有一个 `device` 状态的目标;没有的话先启动模拟器再跑。

- [ ] **Step 5: Commit**

```bash
git add core/database/src/main/java/dev/insua/jellycast/database/ProgressReportEntity.kt \
        core/database/src/androidTest/java/dev/insua/jellycast/database/ProgressReportDaoTest.kt
git commit -m "feat(database): 补报队列支持按 (serverId, itemId) 删除

发 stop 之前要清掉该条目的旧补报记录,否则早期失败的心跳会在这一集
被标记播完之后重放回去,把播放位置写回服务端。纯新增查询,无 schema 变更。"
```

---

### Task 2: 终结标记 —— 已收尾的条目不再接受更旧的上报

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/FinishedItemRegistry.kt`
- Create: `core/player/src/test/java/dev/insua/jellycast/player/FinishedItemRegistryTest.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt`

**Interfaces:**
- Consumes: `ProgressReportDao.deleteForItem(serverId: String, itemId: String)`(Task 1)
- Produces:
  - `class FinishedItemRegistry(capacity: Int = 32)`,方法 `markFinished(itemId: String)`、`clearFinished(itemId: String)`、`isFinished(itemId: String): Boolean`,全部非 suspend、线程安全
  - `ProgressReporter` 的构造签名**不变**:`ProgressReporter(api: JellyfinApi, dao: ProgressReportDao, serverId: String)`。Task 3 会继续改这个类。

- [ ] **Step 1: 写失败的测试(注册表)**

创建 `core/player/src/test/java/dev/insua/jellycast/player/FinishedItemRegistryTest.kt`:

```kotlin
package dev.insua.jellycast.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「哪些条目的上报生命周期已经终结」。见设计文档 §2.3 规则 1 / §2.4。
 */
class FinishedItemRegistryTest {

    @Test fun `没标记过的条目不是已终结`() {
        assertFalse(FinishedItemRegistry().isFinished("ep1"))
    }

    @Test fun `标记之后是已终结`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        assertTrue(registry.isFinished("ep1"))
    }

    @Test fun `标记只影响被标记的那个条目`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        assertFalse(registry.isFinished("ep2"))
    }

    /** 重看语义:`start` 会清除标记,和服务端一致(实测:已 Played 的条目收到 start 会被取消 Played)。 */
    @Test fun `清除之后不再是已终结`() {
        val registry = FinishedItemRegistry()
        registry.markFinished("ep1")
        registry.clearFinished("ep1")
        assertFalse(registry.isFinished("ep1"))
    }

    /**
     * 有界:标记只需覆盖「刚 stop 的条目仍可能有在飞/排队的上报」这个秒级窗口,
     * 不能无限增长。超出容量时淘汰最久未被访问的。
     */
    @Test fun `超出容量时淘汰最旧的标记`() {
        val registry = FinishedItemRegistry(capacity = 2)
        registry.markFinished("ep1")
        registry.markFinished("ep2")
        registry.markFinished("ep3")

        assertFalse(registry.isFinished("ep1"), "ep1 是最旧的,应该被淘汰")
        assertTrue(registry.isFinished("ep2"))
        assertTrue(registry.isFinished("ep3"))
    }

    /** LRU 而不是 FIFO:被查询过的标记算「新鲜」,不该先于没被碰过的被淘汰。 */
    @Test fun `被访问过的标记比未访问的更晚被淘汰`() {
        val registry = FinishedItemRegistry(capacity = 2)
        registry.markFinished("ep1")
        registry.markFinished("ep2")
        registry.isFinished("ep1")      // 让 ep1 变成最近访问
        registry.markFinished("ep3")    // 触发淘汰

        assertTrue(registry.isFinished("ep1"), "ep1 刚被访问过,不该被淘汰")
        assertFalse(registry.isFinished("ep2"), "ep2 是最久未访问的")
        assertTrue(registry.isFinished("ep3"))
    }
}
```

> ⚠️ `:core:player` 的 JVM 单测跑在 **JUnit 5** 上,断言参数顺序是 **(condition, message)**。
> Task 5 的 `androidTest` 跑在 **JUnit 4** 上,顺序相反,是 **(message, condition)**。两边不要串。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:player:test --tests '*FinishedItemRegistryTest*'`

Expected: 编译失败,`Unresolved reference: FinishedItemRegistry`。

- [ ] **Step 3: 写最小实现(注册表)**

创建 `core/player/src/main/java/dev/insua/jellycast/player/FinishedItemRegistry.kt`:

```kotlin
package dev.insua.jellycast.player

/**
 * 「哪些条目的上报生命周期已经终结」—— 设计文档 §2.3 规则 1 的状态载体。
 *
 * ## 为什么需要它
 *
 * Jellyfin 的实测行为(设计文档 §1.1):一条 `stop` 上报的位置 ≥ 90% 时,服务端把条目标记
 * 为已播放**并把播放位置清零**。但**此后**再收到该条目的任何 `progress`、或一条位置低于 90%
 * 的 `stop`,位置就会被重新写上,而已播放的勾**不会**被撤销。用户看到的是:一集明明播完了、
 * 打了勾,却还带着进度条赖在「继续观看」里。
 *
 * 所以客户端必须自己记住「这一集已经收尾了」,并据此丢弃所有更旧的上报。
 *
 * ## 为什么只在内存里
 *
 * `ProgressReporter` 在发出 `stop` 之前会先 `deleteForItem` 清空该条目的补报队列
 * (设计文档 §2.3 规则 2),因此**持久队列里不可能残留已终结条目的旧记录**。这份标记只需要
 * 覆盖「stop 已发出,但同一条目还有在飞或排队的上报」这个**秒级窗口**,进程重启后无需恢复。
 *
 * ## 为什么有界
 *
 * 一个长时间运行的播放进程会连播很多集,无界的话这张表只增不减。容量 [capacity] 条、按
 * LRU 淘汰:窗口是秒级的,32 条远超任何真实场景下同时处于窗口内的条目数,被淘汰的那些
 * 条目的窗口早已关闭。
 *
 * ## 线程契约
 *
 * 生产上的调用方来自主线程(`PlaybackService` 的 main-looper scope)和 `Dispatchers.IO`
 * (`PlaybackService.onDestroy` 的收尾 stop),所以每个方法都必须自带同步 ——
 * `LinkedHashMap` 本身不是线程安全的。
 */
class FinishedItemRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    /** `accessOrder = true` 即 LRU:`get` 和 `put` 都会把条目移到最新端。 */
    private val finished = object : LinkedHashMap<String, Unit>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > capacity
    }

    /** 该条目已收尾:此后它的 `progress` 与队列里的旧记录一律作废。 */
    @Synchronized
    fun markFinished(itemId: String) {
        finished[itemId] = Unit
    }

    /**
     * 该条目重新开始播放。对应服务端的重看语义 —— 实测已 `Played` 的条目收到一条 `start`
     * 之后,`Played` 会被取消、位置归零,客户端这边的终结标记也必须跟着失效。
     */
    @Synchronized
    fun clearFinished(itemId: String) {
        finished.remove(itemId)
    }

    @Synchronized
    fun isFinished(itemId: String): Boolean = finished[itemId] != null

    private companion object {
        /** 见类注释「为什么有界」。 */
        const val DEFAULT_CAPACITY = 32
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:test --tests '*FinishedItemRegistryTest*'`

Expected: PASS,6 个用例全绿。

- [ ] **Step 5: 写失败的测试(上报器应用规则)**

在 `ProgressReporterTest.kt` 的最后一个 `@Test` 之后、类的右花括号之前追加:

```kotlin
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
        val dao = FakeProgressReportDao()
        dao.enqueue(
            ProgressReportEntity(
                id = 0, serverId = "s1", itemId = "ep1", playSessionId = null,
                positionMs = 60_000, kind = "progress", createdAt = 1
            )
        )
        val reporter = ProgressReporter(api, dao, "s1")

        // 这一集播完了。注意:stop 自己会先 deleteForItem 清队列,所以这里用
        // FakeProgressReportDao 走真实语义,验证「清了」和「就算没清,重放也会跳过」两层防御。
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
```

`FakeProgressReportDao` 需要补上新方法。把该类改成:

```kotlin
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
```

- [ ] **Step 6: 跑测试,确认失败**

Run: `./gradlew :core:player:test --tests '*ProgressReporterTest*'`

Expected: 新增用例中至少 `stop 之后同一条目的 progress 不发出也不入队`、`stop 之前先删除该条目在补报队列里的旧记录`、`重放时跳过已终结条目的旧 progress,并把它从队列里清掉` 三条 FAIL。既有 10 条保持绿。

- [ ] **Step 7: 写最小实现**

把 `ProgressReporter.kt` 的类体改成下面这样。**注意这次顺带把 DTO 构造从 `start`/`progress`/`stop`/`replay` 四处的重复收敛成一个 `send`** —— 重放和实时上报本来就是同一件事的两条入口,规则必须对两条都生效,分开写迟早会漏掉一条。

先在文件顶部补一条 import:

```kotlin
import dev.insua.jellycast.database.ProgressReportDao
```
(已存在则跳过)

类体:

```kotlin
class ProgressReporter(
    private val api: JellyfinApi,
    private val dao: ProgressReportDao,
    private val serverId: String,
) : ProgressSink {

    private val flushMutex = Mutex()

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
                // 铁律:上报路径上的任何失败都不得打断播放。清队列失败(数据库损坏/迁移失败)
                // 也不例外 —— 顶多是漏掉一条旧记录,而重放侧还有一层同样的判断兜着。
                runCatching { dao.deleteForItem(serverId, itemId) }
            }
            KIND_PROGRESS -> if (finishedItems.isFinished(itemId)) return
        }
        reportOrEnqueue(kind, itemId, sessionId, positionMs)
    }

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
```

`replayFailures` 的既有 KDoc 原样保留,不要删。

- [ ] **Step 8: 跑测试,确认通过**

Run: `./gradlew :core:player:test`

Expected: PASS。`ProgressReporterTest` 18 条(既有 10 + 新增 8)、`FinishedItemRegistryTest` 6 条,以及 `:core:player` 其余用例全绿。

- [ ] **Step 9: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/FinishedItemRegistry.kt \
        core/player/src/test/java/dev/insua/jellycast/player/FinishedItemRegistryTest.kt \
        core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt \
        core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt
git commit -m "fix(player): 已收尾的条目不再接受更旧的上报

服务端实测:已标记播完的条目再收到一条 progress,播放位置会被写回去,
而已播放的勾不会撤销 —— 这一集于是永远赖在「继续观看」里还带着进度条。

以 stop 为生命周期终点:标记终结、清空该条目的补报队列、重放时跳过
已终结条目的旧 start/progress。stop 本身始终放行。"
```

---

### Task 3: 上报全序 —— 在飞的心跳不许排到 stop 后面

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `dispatch` / `reportOrEnqueue` / `replay` / `send` 私有方法,以及 `FinishedItemRegistry`
- Produces: 无新的公开接口。`ProgressReporter` 的对外契约不变。

- [ ] **Step 1: 写失败的测试**

在 `ProgressReporterTest.kt` 末尾追加。这两条测试用一个可控的挂起点制造「progress 正在飞」的窗口:

```kotlin
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
     * 而不是等 stop 发完再补一条上去。终结判定因此**必须在锁内**做,锁外判定会让一条
     * 已经通过判定、正在等锁的心跳排到 stop 后面发出去,那正是要防的竞态。
     */
    @Test fun `stop 先拿到锁时,排在后面的 progress 被丢弃`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        val stopGate = CompletableDeferred<Unit>()
        coEvery { api.reportStop(any()) } coAnswers { stopGate.await() }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            val ending = launch { reporter.stop("ep1", "sess", 2_400_000) }
            runCurrent()
            val heartbeat = launch { reporter.progress("ep1", "sess", 60_000) }
            runCurrent()

            stopGate.complete(Unit)
            ending.join()
            heartbeat.join()
        }

        coVerify(exactly = 0) { api.reportProgress(any()) }
    }

    /**
     * 补报重放必须**逐条**获取上报锁,不能整批持有:队列上限是 100 条,整批持有会把一条
     * 实时 `stop` 堵在一次满队列重放的后面。
     */
    @Test fun `补报重放期间实时 stop 不必等待整批完成`() = runTest {
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
        val firstReplayGate = CompletableDeferred<Unit>()
        var replayed = 0
        coEvery { api.reportProgress(any()) } coAnswers {
            if (replayed++ == 0) firstReplayGate.await()
        }

        val reporter = ProgressReporter(api, dao, "s1")
        coroutineScope {
            val flush = launch { reporter.flushPending() }
            runCurrent()
            val ending = launch { reporter.stop("ep9", "sess", 2_400_000) }
            runCurrent()

            // 第一条重放还卡着,整批一共 3 条 —— 如果整批持有锁,stop 到这里必然一次都没发出。
            firstReplayGate.complete(Unit)
            flush.join()
            ending.join()
        }

        coVerify(exactly = 1) { api.reportStop(match { it.itemId == "ep9" }) }
    }
```

顶部 import 补上:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:player:test --tests '*ProgressReporterTest*'`

Expected: `stop 必须等在飞的 progress 完成之后才发出` FAIL —— 断言消息为「心跳还卡在网络里,stop 不该抢先发出去」,实际 `order` 是 `[stop]`。`stop 先拿到锁时,排在后面的 progress 被丢弃` 也可能 FAIL(取决于调度),两条都要转绿。

- [ ] **Step 3: 写最小实现**

在 `ProgressReporter.kt` 里,`flushMutex` 声明之后加上上报锁:

```kotlin
    /**
     * 设计文档 §2.3 规则 3:**所有**上报(实时 + 重放)串行经过这一把锁,每次只持有
     * **一个 HTTP 调用**的时长。
     *
     * 为什么必须有它:10 秒心跳的 `progress` 是挂起的网络请求,它在飞的时候如果播放正好结束,
     * `stop` 会另起一条连接 —— 两条请求到达服务端的先后**没有任何保证**。心跳后到,服务端就把
     * 位置写成了心跳那个值,而已播放的勾不会撤销(设计文档 §1.2 根因 B)。
     *
     * 和终结标记叠加之后,两种交错都被覆盖:
     * - `progress` 先拿到锁 → 它先落地,`stop` 随后落地 → 终态正确
     * - `stop` 先拿到锁 → `progress` 随后在锁内被终结判定丢弃 → 终态正确
     *
     * ⚠️ 终结判定必须**在锁内**做。放在锁外的话,一条已经通过判定、正在等锁的心跳会排到
     * `stop` 后面发出去 —— 那正是这把锁要防的东西。
     *
     * **已知取舍:** 一次实时 `stop` 最多等待一个在飞请求的时长(受 OkHttp 超时上界约束)。
     * 因此补报重放必须**逐条**获取这把锁,而不是整批持有 —— 队列上限 100 条,整批持有会把
     * 实时 `stop` 堵在一次满队列重放的后面。
     */
    private val reportMutex = Mutex()
```

把 `dispatch` 整个方法体包进锁里:

```kotlin
    private suspend fun dispatch(
        kind: String,
        itemId: String,
        sessionId: String?,
        positionMs: Long,
    ) = reportMutex.withLock {
        when (kind) {
            KIND_START -> finishedItems.clearFinished(itemId)
            KIND_STOP -> {
                finishedItems.markFinished(itemId)
                runCatching { dao.deleteForItem(serverId, itemId) }
            }
            KIND_PROGRESS -> if (finishedItems.isFinished(itemId)) return@withLock
        }
        reportOrEnqueue(kind, itemId, sessionId, positionMs)
    }
```

在 `flushPendingLocked` 里,把重放那一行改成逐条获取上报锁:

```kotlin
            if (runCatching { reportMutex.withLock { replay(entry) } }.isSuccess) {
```

> 锁序:`flushMutex` → `reportMutex`,而 `dispatch` 只取 `reportMutex`,没有反向路径,不会死锁。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:test`

Expected: PASS,`ProgressReporterTest` 21 条(18 + 新增 3)全绿,`:core:player` 其余用例保持绿。

- [ ] **Step 5: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt \
        core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt
git commit -m "fix(player): 上报串行化,在飞的心跳不再排到 stop 后面

心跳是挂起的网络请求,它在飞时如果播放正好结束,两条请求到达服务端的
先后没有任何保证 —— 心跳后到就会把位置写回已播完的条目。

所有上报串行经过一把锁,每次只持有一个 HTTP 调用的时长;终结判定移到
锁内。补报重放逐条获取,不整批持有,避免堵住实时 stop。"
```

---

### Task 4: 协调器状态加锁

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackProgressCoordinator.kt:101-182`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/PlaybackProgressCoordinatorTest.kt`

**Interfaces:**
- Consumes: 无(与 Task 1-3 文件不重叠)
- Produces: `PlaybackProgressCoordinator` 的三个 `suspend` 入口签名不变:`onSourceReady(source, startPositionMs, trigger)`、`onTick()`、`onPlaybackStopped(positionMs)`。

- [ ] **Step 1: 写失败的测试**

在 `PlaybackProgressCoordinatorTest.kt` 末尾追加:

```kotlin
    /**
     * 🔴 设计文档 §1.2 根因 C。`PlaybackService.onDestroy` 在 `Dispatchers.IO` 上调
     * [PlaybackProgressCoordinator.onPlaybackStopped],而其余入口(源就绪、10 秒心跳)全在
     * 主线程 —— `currentItemId` 等可变字段是普通 `var`,两者之间没有 happens-before 边。
     *
     * 判别办法:让 sink 的 `stop` 在里面卡住,这期间并发调一次 `onTick`。三个入口互斥的话,
     * 心跳必须等 stop 走完,那时 `currentItemId` 已经被清空 —— 心跳什么都不该报。
     * 没有互斥的话,心跳会读到还没被清掉的 `currentItemId`,给一个正在收尾的条目补一条
     * `progress` 上去,而那正是把进度写回已播完条目的另一条路径。
     */
    @Test fun `收尾期间并发的心跳不会给正在收尾的条目补上 progress`() = runTest {
        val stopGate = CompletableDeferred<Unit>()
        val sink = object : ProgressSink {
            val calls = mutableListOf<String>()
            override suspend fun start(itemId: String, sessionId: String?, positionMs: Long) {
                calls += "start $itemId"
            }
            override suspend fun progress(itemId: String, sessionId: String?, positionMs: Long) {
                calls += "progress $itemId"
            }
            override suspend fun stop(itemId: String, sessionId: String?, positionMs: Long) {
                stopGate.await()
                calls += "stop $itemId"
            }
            override suspend fun flushPending() = Unit
        }
        val c = coordinator(sink) { 2_400_000L }
        c.onSourceReady(source("ep1"), startPositionMs = 0L)

        launch { c.onPlaybackStopped() }
        runCurrent()
        launch { c.onTick() }
        runCurrent()

        stopGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("start ep1", "stop ep1"), sink.calls)
    }
```

顶部 import 补上:

```kotlin
import kotlinx.coroutines.CompletableDeferred
```

（`launch` / `runCurrent` / `runTest` 该文件已经 import 过。)

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:player:test --tests '*PlaybackProgressCoordinatorTest*'`

Expected: FAIL —— 实际得到 `[start ep1, progress ep1, stop ep1]`,多出来的那条 `progress ep1` 就是缺陷本身。

- [ ] **Step 3: 写最小实现**

在 `PlaybackProgressCoordinator.kt` 顶部补 import:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

在类里、`currentItemId` 声明之前加:

```kotlin
    /**
     * 设计文档 §1.2 根因 C / §2.5:三个入口互斥。
     *
     * 缺陷现场:`PlaybackService.onDestroy` 在 `Dispatchers.IO` 上调 [onPlaybackStopped],
     * 其余入口(源就绪、10 秒心跳)全在主线程,而下面这三个字段是普通 `var` —— 两者之间
     * 没有 happens-before 边,IO 线程可能读到陈旧值。更要紧的是 [onPlaybackStopped] 的
     * 「清空 currentItemId」和 `sink.stop` 之间存在挂起点,并发的心跳会挤进这个窗口,给一个
     * 正在收尾的条目再补一条 `progress` 上去 —— 那正好又把进度写回了刚播完的这一集。
     *
     * 这把锁同时解决可见性和这个窗口。代价:心跳可能要等一次源就绪的网络往返(受 OkHttp
     * 超时上界约束)。心跳晚几秒没有任何用户可见后果,而窗口里那条 `progress` 有。
     */
    private val mutex = Mutex()
```

把三个入口的方法体包进锁里。`onSourceReady` 里原本的 `return`(状态重放那条)要改成 `return@withLock`:

```kotlin
    suspend fun onSourceReady(
        source: PlaybackSource,
        startPositionMs: Long,
        trigger: PlaybackReadyTrigger = PlaybackReadyTrigger.NEW_PLAYBACK,
    ) = mutex.withLock {
        if (trigger == PlaybackReadyTrigger.STATE_REPLAY) return@withLock

        val previousItemId = currentItemId
        val previousSessionId = currentSessionId
        val previousPositionMs = lastKnownPositionMs

        currentItemId = source.itemId
        currentSessionId = source.playSessionId

        sink.flushPending()

        when (previousItemId) {
            null -> {
                lastKnownPositionMs = startPositionMs
                sink.start(source.itemId, source.playSessionId, startPositionMs)
            }
            source.itemId -> {
                val positionMs = absolutePositionMs()
                lastKnownPositionMs = positionMs
                sink.progress(source.itemId, source.playSessionId, positionMs)
            }
            else -> {
                lastKnownPositionMs = startPositionMs
                sink.stop(previousItemId, previousSessionId, previousPositionMs)
                sink.start(source.itemId, source.playSessionId, startPositionMs)
            }
        }
    }

    suspend fun onTick() = mutex.withLock {
        val itemId = currentItemId ?: return@withLock
        val positionMs = absolutePositionMs()
        lastKnownPositionMs = positionMs
        sink.progress(itemId, currentSessionId, positionMs)
    }

    suspend fun onPlaybackStopped(positionMs: Long = absolutePositionMs()) = mutex.withLock {
        val itemId = currentItemId ?: return@withLock
        val sessionId = currentSessionId
        currentItemId = null
        currentSessionId = null
        lastKnownPositionMs = positionMs
        sink.stop(itemId, sessionId, positionMs)
    }
```

三处方法体上方的既有 KDoc 全部原样保留,不要删。

> `onPlaybackStopped` 的默认参数 `absolutePositionMs()` 在锁外求值 —— 这是对的:读播放器位置必须发生在调用方自己的线程上(ExoPlayer 的 application thread 约束),`PlaybackService.onDestroy` 正是先在主线程取好值再显式传进来。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:test`

Expected: PASS,`PlaybackProgressCoordinatorTest` 18 条(既有 17 + 新增 1)全绿,`:core:player` 全部用例保持绿。

- [ ] **Step 5: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/PlaybackProgressCoordinator.kt \
        core/player/src/test/java/dev/insua/jellycast/player/PlaybackProgressCoordinatorTest.kt
git commit -m "fix(player): 进度协调器三个入口互斥

onDestroy 在 IO 线程调 onPlaybackStopped,其余入口全在主线程,可变状态是
普通 var,两者之间没有 happens-before 边。更要紧的是「清空 currentItemId」
和 sink.stop 之间的挂起点会让并发心跳挤进来,给正在收尾的条目再补一条
progress —— 又一条把进度写回已播完条目的路径。"
```

---

### Task 5: 真服务器 E2E —— 播完之后服务端不留残余进度

**Files:**
- Modify: `app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ProgressReportDao.deleteForItem`(间接)、Task 2/3 的 `ProgressReporter` 行为、Task 4 的协调器加锁
- Produces: 无(测试是终点)

**背景:为什么必须有这个 E2E。** JVM 单测证明不了这个缺陷已修复 —— 判据在**服务端的 `UserData`** 上。这一条沿用 v4 立下的铁律:依赖平台/外部系统行为的部分必须在真机上跑过。这个测试同时也是 §1.2 根因 A 的自动化复现。

- [ ] **Step 1: 写失败的测试**

在 `PlaybackE2eTest.kt` 里,给已有的 `@Inject` 字段块追加两个注入(放在 `playerConnection` 那一行之后):

```kotlin
    @Inject lateinit var progressReportDao: dev.insua.jellycast.database.ProgressReportDao
```

> **不要**注入 `ProgressReporter`。它是 `@Singleton`,构造时会 `runBlocking` 快照一次当前激活服务器 id;在这里注入会让它在 `seedSession()` **之前**就被构造出来,拿到错误的 serverId,这个测试的预置记录就永远不会被它看见。让它按生产路径在 `PlaybackService` 启动时才被构造。

然后在最后一个 `@Test` 之后、私有 helper 区之前追加场景:

```kotlin
    // ---------------------------------------------------------------- 场景:上报生命周期

    /**
     * 🔴 **现场缺陷的自动化复现。**
     *
     * 用户报告:已播完、已打蓝勾的剧集,在 Jellyfin Web 上仍带着进度条,赖在「继续观看」里。
     * 服务端实测(设计文档 §1.1):已标记播完的条目再收到一条 `progress`,位置会被写回去,
     * 而已播放的勾**不会**撤销。
     *
     * 客户端这边的触发路径(设计文档 §1.2 根因 A):补报队列的重放时机是「每次源就绪」,
     * 而自动连播的顺序恰好是「上一集 stop → 推进 → 下一集就绪 → flushPending」——
     * 队列里那条上一集的旧心跳正好在它已经被标记播完之后被重放回去。
     *
     * 所以这个测试**预置一条陈旧的 progress 补报记录**,再让这一集真的播到结束。
     *
     * ## 断言纪律
     *
     * 不是「播完之后查一次位置是 0」就完事 —— 缺陷是**迟到的**写入,查得太早会假绿。
     * 这里分两段:先等服务端确认打了勾,再在一个明确的窗口内持续盯着位置,
     * **只要它在窗口内任何一刻变成非 0 就判定失败**。
     *
     * ## 为什么播得完
     *
     * 从距结尾 [TAIL_SEEK_MARGIN_MS] 处起播。转码流按 `startTimeTicks` 重新起流,
     * 这条流只剩 30 秒,几十秒内自然 `STATE_ENDED`(和 [awaitEnded] 依赖的是同一个事实)。
     */
    @Test
    fun `播完一集之后服务端不再残留播放位置`() {
        val item = playableItem
        val serverId = runBlocking { serverStore.activeServerId.first() }
        assertTrue(
            "拿不到激活服务器 id,补报记录会被预置到错误的分区里,这个测试就失去判别力了。",
            serverId != null,
        )

        // 预置:模拟「这一集开播不久时一次失败的心跳」。位置取靠前的值 —— 它一旦被重放回去,
        // 就是用户在 Jellyfin 上看到的那条残留进度条。
        runBlocking {
            progressReportDao.enqueue(
                ProgressReportEntity(
                    serverId = serverId!!,
                    itemId = item.id,
                    playSessionId = null,
                    positionMs = STALE_REPORT_POSITION_MS,
                    kind = "progress",
                    createdAt = System.currentTimeMillis(),
                )
            )
        }

        try {
            val latch = addEndedLatch()
            try {
                startPlayback(item, startPositionMs = tailSeekTargetMs(item))
                awaitEnded(latch, "播完一集(从距结尾 ${TAIL_SEEK_MARGIN_MS}ms 处起播)")
            } finally {
                removeEndedLatch(latch)
            }

            // 第一段:服务端确认这一集播完了。收不到这个,说明完播上报本身坏了 —— 那是另一个缺陷,
            // 要和「进度残留」区分开报出来。
            val marked = awaitServerCondition(REPORT_SETTLE_TIMEOUT_MS) { userData(item.id).played }
            assertTrue(
                "服务端始终没把这一集标记为已播放。完播上报(位置 ≥ 90% 的 stop)没有发出或没有生效," +
                    "这不是进度残留的问题。${diagnostics()}",
                marked,
            )

            // 第二段:在窗口内持续盯着位置。自动连播会在这期间起来并触发 flushPending ——
            // 缺陷存在的话,那条预置的旧心跳正是在这一刻被重放回去。
            val leaked = awaitServerCondition(REPORT_SETTLE_TIMEOUT_MS) {
                userData(item.id).positionTicks != 0L
            }
            assertFalse(
                "这一集已经打了已播放的勾,播放位置却在收尾之后被写回成 " +
                    "${userData(item.id).positionTicks} ticks。这就是用户看到的「已播完却还带着进度条、" +
                    "赖在继续观看里」。触发路径见设计文档 §1.2 根因 A/B。${diagnostics()}",
                leaked,
            )
        } finally {
            restoreUserData(item.id)
            restoreUserData(secondItem.id)
            runCatching { runBlocking { progressReportDao.deleteForItem(serverId!!, item.id) } }
        }
    }

    /** 服务端的 `UserData`;条目还没有任何播放记录时 Jellyfin 也会给一个全零的对象。 */
    private fun userData(itemId: String) =
        runBlocking { api.itemDetail(itemId, userId) }.userData
            ?: dev.insua.jellycast.network.dto.UserDataDto()

    /**
     * 条件等待的服务端版本:轮询间隔比 [POLL_INTERVAL_MS] 大得多,因为每次判定都是一次
     * 真实的 HTTP 往返 —— 按 250ms 轮询会把服务器打成筛子,还会把测试自己拖慢。
     */
    private fun awaitServerCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(SERVER_POLL_INTERVAL_MS)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * 把测试账号在这个条目上的观看记录复原:先把位置清零,再撤销已播放标记。
     *
     * 顺序要紧 —— 反过来的话那条位置为 0 的 `stop` 会被服务端当成一次新的观看记录留下来。
     * 失败不让测试变红:复原是清理,不是断言。
     */
    private fun restoreUserData(itemId: String) {
        runCatching {
            runBlocking {
                api.reportStop(
                    dev.insua.jellycast.network.dto.PlaybackStopInfoDto(
                        itemId = itemId,
                        playSessionId = null,
                        positionTicks = 0L,
                    )
                )
                api.markUnplayed(itemId, userId)
            }
        }
    }
```

在 `companion object` 里追加三个常量(放在 `POLL_INTERVAL_MS` 附近):

```kotlin
        /** 预置的陈旧心跳位置:靠前,一旦被重放回去就是用户看到的那条残留进度条。 */
        const val STALE_REPORT_POSITION_MS = 60_000L

        /**
         * 等服务端落定的窗口。要覆盖:完播 stop 的往返 + 自动连播解析下一集(两次网络往返)
         * + 下一集就绪触发的 flushPending。缺陷存在的话,残留写入就发生在这段时间里。
         */
        const val REPORT_SETTLE_TIMEOUT_MS = 60_000L

        /** 见 [awaitServerCondition]:每次判定都是一次真实 HTTP 往返,不能按 250ms 轮询。 */
        const val SERVER_POLL_INTERVAL_MS = 2_000L
```

顶部 import 追加:

```kotlin
import dev.insua.jellycast.database.ProgressReportEntity
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
```

（`ProgressReportDao`、`UserDataDto`、`PlaybackStopInfoDto` 在上面的代码里用了全限定名,不需要 import;`SystemClock`、`runBlocking`、`assertTrue` 该文件已经 import 过。)

- [ ] **Step 2: 跑测试,确认失败**

先把 Task 1-4 的提交**临时**回退到 Task 1 之前,确认这个测试能复现缺陷:

```bash
git stash push -- app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt
git log --oneline -5          # 记下 Task 1 之前那个 commit 的 sha,下面记作 <BASE>
git checkout <BASE> -- core/database core/player
git stash pop
adb devices                   # 必须至少有一个 device 状态的目标
./gradlew :app:connectedDebugAndroidTest --tests '*PlaybackE2eTest.播完一集之后服务端不再残留播放位置*'
```

Expected: FAIL,断言消息为「这一集已经打了已播放的勾,播放位置却在收尾之后被写回成 600000000 ticks」。

> **这一步是这个任务的全部价值所在。** 如果它是绿的,说明测试没有判别力(最可能的原因:预置记录的 serverId 和 `ProgressReporter` 实际用的对不上,或者自动连播没有起来因而 `flushPending` 根本没跑),必须先修测试,不要往下走。

恢复修复代码:

```bash
git checkout HEAD -- core/database core/player
```

- [ ] **Step 3: 跑测试,确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*PlaybackE2eTest.播完一集之后服务端不再残留播放位置*'
```

Expected: PASS。

- [ ] **Step 4: 跑全量回归**

```bash
./gradlew test
./gradlew :app:connectedDebugAndroidTest
```

Expected: JVM 单测全绿(既有 379 条 + 本计划新增的 18 条);设备测试全绿。

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt
git commit -m "test(e2e): 播完一集之后服务端不留残余进度

判据在服务端的 UserData 上,JVM 单测结构上证明不了。预置一条陈旧的
progress 补报记录,让这一集真的播到结束,再断言服务端打了勾且位置在
整个落定窗口内始终为 0。

修复前红(位置被重放回 60s),修复后绿。"
```

---

## 计划自查

**1. Spec 覆盖**

| 设计文档要求 | 落点 |
|---|---|
| §2.3 规则 1 终结标记 | Task 2 |
| §2.3 规则 2 入队清理 | Task 1(DAO)+ Task 2(调用) |
| §2.3 规则 3 全序 | Task 3 |
| §2.4 终结标记有界 LRU、容量 32、不引迁移 | Task 2 Step 3 |
| §2.5 协调器加锁 | Task 4 |
| §4.1 JVM 单测的 7 条关键用例 | Task 2 Step 5(前 5 条)+ Task 3 Step 1(重放逐条获取锁)+ Task 4 Step 1(三入口互斥) |
| §4.2 真服务器 E2E 五个步骤 | Task 5 |
| §5 验收标准 | Task 5 Step 3/4 |
| §3「不做」 | 无任务触及「下一集」按钮的位置来源 ✓ |

无遗漏。

**2. 占位符扫描**

无 TBD/TODO,每个改代码的步骤都带完整代码块,每条测试都有可运行的断言。

**3. 类型一致性**

- `deleteForItem(serverId: String, itemId: String)` —— Task 1 定义,Task 2(`dao.deleteForItem(serverId, itemId)`)、Task 2 的 `FakeProgressReportDao`、Task 5 的清理段用法一致 ✓
- `FinishedItemRegistry` 的 `markFinished` / `clearFinished` / `isFinished` —— Task 2 定义并使用,Task 3 的 `dispatch` 沿用同名 ✓
- `send(kind, itemId, sessionId, positionMs)` —— Task 2 引入,Task 3 不改签名 ✓
- `UserDataDto.played` / `UserDataDto.positionTicks` —— 与 `core/network` 既有 DTO 的属性名一致 ✓
- `api.markUnplayed(itemId, userId)` / `api.itemDetail(itemId, userId)` —— 与 `JellyfinApi` 既有签名一致 ✓
