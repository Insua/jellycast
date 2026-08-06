# 上次播放记录缺失季集与封面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让冷启动恢复出来的播放信息是完整的 —— 有剧名、有季集、有封面。

**Architecture:** 两件独立的事。一是把结构化季集字段补进持久化记录并在恢复时填回;二是让迷你条封面不再是 `init` 时刻的一次性快照,而是能在 baseUrl 就绪后补上。

**Tech Stack:** Kotlin · DataStore + kotlinx.serialization · JUnit 5(JVM)

**设计文档:** `docs/superpowers/specs/2026-08-06-last-played-metadata-design.md`

## Global Constraints

- **恢复过程绝不发网络请求、绝不启动前台服务、绝不自动播放。** 断网 / 未登录 / 服务器不可达时同样能恢复。既有用例已经钉住这三条,不得破坏。
- **`LastPlayed` 的每个新字段都必须带默认值。** 没有默认值,字段引入之前写盘的旧记录会在读取的 `runCatching` 里整条解析失败被静默丢弃,用户升级后冷启动的迷你条会凭空消失一次。本仓库已经因此被复审打回过一次。
- 不为恢复去服务端重新拉元数据。
- 不改 `displaySubtitle` 的拼接规则。
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**。
- JVM 单测跑 **JUnit 5**(断言参数顺序 `(condition, message)`)。
- **Commit 格式:gitmoji + Conventional Commits**,`<emoji> <type>(<scope>): <subject>`(本仓库 `feat` 用 ✨,修复用 🐛,文档用 📝)。
- JVM 单测:`./gradlew :<module>:testDebugUnitTest`(`:<module>:test` 只是聚合任务,不接受 `--tests`)。
- **每条新测试都必须做变异验证**:把对应实现改回缺陷状态,确认该条测试变红,记录真实输出,再恢复并确认 `git status --porcelain` 只剩预期文件。

---

### Task 1: 持久化并恢复结构化季集字段

**Files:**
- Modify: `core/datastore/src/main/java/dev/insua/jellycast/datastore/LastPlayedStore.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/LastPlayedRecordBuilder.kt`
- Modify: `app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt`
- Test: `core/datastore/.../LastPlayedStoreTest.kt`、`core/player/.../LastPlayedRecordBuilderTest.kt`、`app/.../AppSessionViewModelTest.kt`

**Interfaces:**
- Consumes: 既有的 `LastPlayed` / `LastPlayedStore` / `buildLastPlayedRecord` / `restoreLastPlayed`
- Produces: `LastPlayed` 新增五个带默认值的字段:`seriesName: String? = null`、`seasonNumber: Int? = null`、`episodeNumber: Int? = null`、`seriesId: String? = null`、`seasonId: String? = null`。Task 2 不依赖它们。

- [ ] **Step 1: 写失败的测试**

三个模块各自补用例。先读各文件既有的写法(构造 store 的方式、构造 ViewModel 的方式),沿用,不要另造一套。

要覆盖:

- **存储层**:五个新字段逐一往返;**旧格式 JSON(不含任何新字段)仍能解出**,新字段落到 null 而不是整条丢弃
- **构造层**(`buildLastPlayedRecord`):剧集条目把五个字段原样带进记录;电影条目季集为 null
- **恢复层**(`restoreLastPlayed`):重建出的 `MediaItem` 五个字段与记录一致
- **回归**:既有的「恢复不发网络请求 / 不自动播放 / 不起前台服务」三条仍然绿

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:datastore:testDebugUnitTest :core:player:testDebugUnitTest :app:testDebugUnitTest`

Expected: 编译失败(新字段不存在)或断言失败。

- [ ] **Step 3: 写最小实现**

三处依次改:记录加字段(**带默认值**,并在 KDoc 里说明为什么必须有默认值)、构造时填入、恢复时填回。

- [ ] **Step 4: 跑测试,确认通过**

Run: 同 Step 2。三个模块全绿。

- [ ] **Step 5: 变异验证(必做,逐条)**

1. 给某个新字段去掉默认值 → 「旧格式 JSON 仍能解出」必须变红(若编译不过,改成把读取端的 `runCatching` 去掉后用旧 JSON 触发,任选其一能证明这条风险即可,并在报告里说明用的哪种)
2. 恢复时把 `seriesId` 写死成 null → 「重建的 MediaItem 字段与记录一致」必须变红
3. 构造记录时不填 `seasonNumber`/`episodeNumber` → 对应用例必须变红

逐条记录真实输出并恢复。

- [ ] **Step 6: Commit**

```bash
git add core/datastore core/player/src/main/java/dev/insua/jellycast/player/LastPlayedRecordBuilder.kt \
        core/player/src/test app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt app/src/test
git commit -m "🐛 fix(app): 上次播放记录补上结构化季集字段

记录里原先只有 title/subtitle 两个拼好的显示字符串,而播放页读的是
seriesName/seasonNumber/episodeNumber —— 于是冷启动恢复后顶栏剧名和
S01E02 副标题都是空的,点了播放也不会补上(engine.play 不回写 PlayQueue)。

seriesId/seasonId 还有功能用途:自动连播与缓存预取都靠它们找本剧集序,
拿不到时会回退到一次网络查询,断网时那次兜底也失败,于是既不连播也不预取。

五个新字段全部带默认值 —— 否则字段引入前写盘的旧记录会整条解析失败被
静默丢弃,用户升级后冷启动的迷你条会凭空消失一次。"
```

---

### Task 2: 封面随 baseUrl 就绪而补上

**Files:**
- Modify: `app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt`
- Test: `app/src/test/java/dev/insua/jellycast/navigation/AppSessionViewModelTest.kt`

**Interfaces:**
- Consumes: 既有的 `_miniPlayer` / `baseUrl` / `MediaItem.posterUrl(baseUrl)`
- Produces: 无

- [ ] **Step 1: 写失败的测试**

- 恢复时 baseUrl 还是空 → 迷你条 `posterUrl` 为 null(**当前行为,先钉住**)
- baseUrl 随后变为有值 → 迷你条 `posterUrl` **不再是 null**,且指向恢复出来的那个条目
- 恢复期间**仍然不发网络请求**(既有那条用例保持绿)

第二条是这个任务的核心,必须能对着未修的代码失败。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :app:testDebugUnitTest`

- [ ] **Step 3: 写最小实现**

让封面不再是 `init` 那一刻的快照。具体形态自己定(观察 `baseUrl` 后补写、或把 `posterUrl` 做成派生),但必须满足:

- **不引入网络请求** —— `refreshBaseUrl()` 已有三级兜底,后两级不联网
- 播放真正开始后,迷你条由既有的 `observeMiniPlayer()` 接管,**不得与之打架**(两条路径同时写 `_miniPlayer` 会闪)。读一下 `observeMiniPlayer()` 再决定,并在 KDoc 里写明两者的边界。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest`

- [ ] **Step 5: 变异验证(必做)**

把新增的「baseUrl 就绪后补写封面」整段去掉 → 「baseUrl 变为有值后 posterUrl 不再是 null」必须变红。记录真实输出,恢复,确认 `git status --porcelain` 只剩预期文件。

- [ ] **Step 6: 全量回归**

```bash
./gradlew test
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt app/src/test
git commit -m "🐛 fix(app): 恢复出来的迷你条补上封面

restoreLastPlayed 在 init 里跑,读 baseUrl 时它还是初始空串(refreshBaseUrl
是另一条独立异步),于是 posterUrl 算出来是 null;而 _miniPlayer 是一次性
快照,baseUrl 后来解析出来了也没人重算 —— 结果就是恢复出来的迷你条永远
没有封面。

imageTag 本身是存了的,缺的是拼 URL 需要的服务器地址。恢复仍然不发网络
请求:refreshBaseUrl 的后两级兜底(本进程上次成功的地址、激活服务器优先级
最高的 endpoint)都不联网,而 URL 只是 Coil 的缓存键。"
```

---

## 计划自查

**1. Spec 覆盖**

| 设计文档要求 | 落点 |
|---|---|
| §3.1 持久化结构化字段 + 默认值 | Task 1 |
| §3.2 封面随 baseUrl 就绪补上 | Task 2 |
| §3.3 不为恢复拉元数据 / 不改 displaySubtitle | 两个 Task 都不触及 |
| §4 旧记录仍能解出 | Task 1 Step 1 第二条 |
| §4 恢复仍不发网络请求 | Task 1、Task 2 各自的回归断言 |
| §5 测试策略与变异验证 | 各 Task 的 Step 5 |

无遗漏。

**2. 占位符扫描**

两个 Task 的 Step 1 给的是**要覆盖的行为清单而非可粘贴代码**,原因写明了(必须先读既有测试才能确定构造方式)。Task 2 Step 3 刻意不规定实现形态,只给硬约束 —— 因为正确形态取决于 `observeMiniPlayer()` 的既有行为,应由实现者读过之后决定。

**3. 类型一致性**

- 五个新字段的名字与 `MediaItem` 既有字段同名(`seriesName` / `seasonNumber` / `episodeNumber` / `seriesId` / `seasonId`)✓
- `MediaItem.posterUrl(baseUrl)` 与既有扩展函数签名一致 ✓
- `miniPlayerProgress(positionMs, durationMs)` 未被本计划改动 ✓
