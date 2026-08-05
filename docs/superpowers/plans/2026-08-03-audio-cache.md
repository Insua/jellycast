# 音频缓存子系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WiFi 下自动整集预缓存当前及后续集数,让下一集不卡、已缓存的集能断网播放并即时 seek,同时受存储上限与驱逐规则约束。

**Architecture:** 决策(缓存哪几集、删哪几个)是纯 Kotlin 函数,放在新建的 `:core:cache`,脱离设备与服务器单测;文件存储与下载薄到几乎没有决策;播放侧以装饰器的形式在既有 `PlaybackSourceProvider` 外面包一层缓存感知,命中则返回本地文件源并让引擎走本地 seek。

**Tech Stack:** Kotlin · Room(`:core:database` 新增一张表)· OkHttp(下载)· DataStore(上限偏好)· Jetpack Compose(设置项)· JUnit 5(JVM)· AndroidJUnit4(设备)

**设计文档:** `docs/superpowers/specs/2026-08-03-audio-cache-design.md`

## Global Constraints

- **分块 / 断点续传不存在。** 转码流 `Accept-Ranges: none` 且字节流取决于 `startTimeTicks`,只能整集从 0 下载;中断即丢弃重来。不要试图实现 Range 续传。
- **仅 WiFi、串行一次一集。** 任何时刻最多一个预取下载在跑 —— 服务器是 J4125,并行转码会拖垮正在听的那一集。
- **正在播放的那一集永不被驱逐**,哪怕它单独就超了存储上限。
- **不完整的文件绝不参与播放。** 下载写临时路径,完成并校验后才登记进索引;索引有记录才算可播。
- **未缓存条目的行为必须与现在完全一致** —— L1/L3 降级链、`startTimeTicks` 语义、seek 走重新解析,全都不变。
- 缓存按**服务器**隔离;换服务器即失效。
- 缓存相关的任何失败都**静默降级为「没有缓存」**,绝不打断播放(项目铁律的同一条精神)。`CancellationException` 必须重抛,**不得用 `runCatching` 包住 suspend 调用**(它会连 `CancellationException` 一起吞;仓库里已经因此返工过一次)。
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**。
- JVM 单测跑 **JUnit 5**(断言参数顺序 `(condition, message)`);`androidTest` 跑 **JUnit 4**(顺序相反)。
- **Commit 格式:gitmoji + Conventional Commits**,`<emoji> <type>(<scope>): <subject>`,正文说明为什么。各 Task 给出的提交信息照抄。
- JVM 单测:`./gradlew :<module>:testDebugUnitTest`(`:<module>:test` 只是聚合任务,不接受 `--tests`)。设备测试:`./gradlew :<module>:connectedDebugAndroidTest`(`--tests` 无效,用 `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`)。
- **每条新测试都必须做变异验证**:把对应实现改回缺陷状态,确认该条测试变红,记录真实输出,再恢复并确认 `git status --porcelain` 只剩预期文件。这个子系统规则密度最高,最容易写出测不出东西的测试。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `core/cache/` | **新模块**:策略、计划、存储、下载、网络类型 | 创建 |
| `core/cache/src/main/.../CachePlan.kt` | **纯函数**:目标集合 + 驱逐决策 | 创建 |
| `core/cache/src/main/.../AudioCacheStore.kt` | 文件读写、临时文件、原子登记 | 创建 |
| `core/cache/src/main/.../AudioCacheDownloader.kt` | 整集下载(OkHttp) | 创建 |
| `core/cache/src/main/.../NetworkTypeMonitor.kt` | 是否 WiFi | 创建 |
| `core/database/src/main/.../CachedAudioEntity.kt` | 缓存索引表与 DAO | 创建 |
| `core/datastore/src/main/.../PreferencesStore.kt` | 存储上限偏好 | 修改 |
| `core/player/src/main/.../CacheAwareSourceProvider.kt` | 命中缓存则返回本地源 | 创建 |
| `core/player/src/main/.../AudioPlaybackEngine.kt` | 本地源的绝对位置与本地 seek | 修改 |
| `core/model/src/main/.../Playback.kt` | `PlaybackSource` 增加本地标记 | 修改 |
| `core/player/src/main/.../CachePrefetchController.kt` | 预取驱动(串行、仅 WiFi) | 创建 |
| `feature/settings/src/main/.../SettingsScreen.kt` | 上限选择 UI | 修改 |

**任务顺序:全部串行。** Task 1 → 2 → 3 → 4 → 5 → 6 → 7。前三个不碰播放链路,风险低;Task 4 起开始动播放,是这批里最需要小心的部分。

---

### Task 1: 缓存索引表

**Files:**
- Create: `core/database/src/main/java/dev/insua/jellycast/database/CachedAudioEntity.kt`
- Test: `core/database/src/androidTest/java/dev/insua/jellycast/database/CachedAudioDaoTest.kt`
- Modify: `core/database/src/main/java/dev/insua/jellycast/database/JellyCastDatabase.kt`(注册实体、**升版本号**)

**Interfaces:**
- Consumes: 无
- Produces:
  ```kotlin
  @Entity(tableName = "cached_audio", primaryKeys = ["serverId", "itemId"])
  data class CachedAudioEntity(
      val serverId: String,
      val itemId: String,
      val seriesId: String?,      // 电影为 null
      val seasonNumber: Int?,     // 用于排序;缺失时排在最后
      val episodeNumber: Int?,
      val filePath: String,
      val sizeBytes: Long,
      val completedAt: Long,
      val lastAccessAt: Long,
  )
  ```
  DAO 需要:按 serverId 查全部、按 (serverId,itemId) 查单条、插入、按主键删、按 serverId 清空、更新 `lastAccessAt`、求 `SUM(sizeBytes)`。Task 2 起都会用到。

- [ ] **Step 1: 写失败的测试**

先读 `core/database/src/androidTest/java/dev/insua/jellycast/database/` 下既有的 DAO 测试(`ProgressReportDaoTest.kt`、`CachedItemDaoTest.kt` 之类),**沿用它们建 in-memory 数据库的方式**。要覆盖:

- 插入后能按 (serverId, itemId) 查回,字段逐一对得上
- 同主键再插入是**覆盖**而不是重复(用 `OnConflictStrategy.REPLACE`)
- 按 serverId 隔离:查 A 服务器查不到 B 服务器的行
- `SUM(sizeBytes)` 只统计指定 serverId,且表空时返回 0 或 null(明确断言是哪一种,别含糊)
- 更新 `lastAccessAt` 只改那一行,不动其它字段
- 按 serverId 清空只清该服务器

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:database:compileDebugAndroidTestKotlin`

Expected: 编译失败,`Unresolved reference: CachedAudioEntity`。

- [ ] **Step 3: 写最小实现**

创建实体与 DAO。**必须同时把 `JellyCastDatabase` 的 `version` 加一并注册新实体。**

> ⚠️ 这个数据库既有的表都是**缓存性质**的(可重建)。查一下 `JellyCastDatabase` 现在用的是不是 `fallbackToDestructiveMigration`;是的话新表不需要写迁移,但**必须在 KDoc 里写清楚这一点**,免得后来人以为迁移被漏了。如果不是,就要按既有方式补迁移。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.database.CachedAudioDaoTest`

Expected: PASS。再跑一次不带过滤的 `./gradlew :core:database:connectedDebugAndroidTest`,确认既有 DAO 测试没被数据库升版本搞挂。

- [ ] **Step 5: 变异验证(必做)**

把 `pending`/查询里的 `serverId` 条件去掉 → 「按 serverId 隔离」那条必须变红。记录真实输出,恢复,确认 `git status --porcelain` 只剩预期文件。

- [ ] **Step 6: Commit**

```bash
git add core/database/src/main/java/dev/insua/jellycast/database/CachedAudioEntity.kt \
        core/database/src/main/java/dev/insua/jellycast/database/JellyCastDatabase.kt \
        core/database/src/androidTest/java/dev/insua/jellycast/database/CachedAudioDaoTest.kt
git commit -m "✨ feat(database): 新增音频缓存索引表

驱逐策略要按剧集顺序、最后访问时间、总占用来决策,这些都得能查询,
所以缓存文件必须有索引而不能只靠扫目录。按 serverId 隔离 —— 缓存
属于某一台服务器,换服务器即失效。"
```

---

### Task 2: 驱逐与预取的纯决策

**Files:**
- Create: `core/cache/`(新模块:`build.gradle.kts`、`src/main/AndroidManifest.xml` 视需要)
- Create: `core/cache/src/main/java/dev/insua/jellycast/cache/CachePlan.kt`
- Create: `core/cache/src/test/java/dev/insua/jellycast/cache/CachePlanTest.kt`
- Modify: `settings.gradle.kts`(注册新模块)

**Interfaces:**
- Consumes: 无(纯函数,不依赖 Room 实体 —— 用自己的窄输入类型,避免把存储结构耦合进策略)
- Produces:
  ```kotlin
  /** 策略的输入:一个已缓存条目的最小描述。 */
  data class CachedEntry(val itemId: String, val order: Int, val sizeBytes: Long, val lastAccessAt: Long)
  /** 策略的输入:剧集顺序里的一个位置。`order` 是全剧唯一且递增(跨季连续)。 */
  data class SeriesSlot(val itemId: String, val order: Int)

  data class CacheDecision(val toPrefetch: List<String>, val toEvict: List<String>)

  fun planCache(
      currentItemId: String,
      seriesOrder: List<SeriesSlot>,   // 电影传只含自己的单元素列表
      cached: List<CachedEntry>,
      maxEpisodes: Int,
      maxBytes: Long?,                 // null = 不限制
  ): CacheDecision
  ```
  Task 5、Task 6 会调它。

- [ ] **Step 1: 建模块**

参照 `core/datastore/build.gradle.kts` 建 `core/cache/build.gradle.kts`,在 `settings.gradle.kts` 注册 `:core:cache`。这一步先只要能编过、能跑空测试。

Run: `./gradlew :core:cache:testDebugUnitTest` — 应当 BUILD SUCCESSFUL(没有测试也算成功)。

- [ ] **Step 2: 写失败的测试**

创建 `CachePlanTest.kt`,把设计文档 §7.1 的清单逐条落成用例:

- 锚点之前的进 `toEvict`;锚点及之后的不进
- 跨季推进:`seriesOrder` 里 S2 最后一集之后就是 S3E01,目标集合按 `order` 连续取
- 取满 `maxEpisodes` 就停,`toPrefetch` 不超过这个数(含当前这一集)
- 超 `maxBytes` 时按 `lastAccessAt` **从老到新**删 —— 用一个「写入晚但访问早」的样例把它和 `completedAt` 区分开,否则这条测不出真正的排序依据
- `maxBytes = null` 时:窗口规则与 `maxEpisodes` **照常**生效,只是不因总量再删
- **当前这一集在任何情况下都不在 `toEvict` 里**,包括它自己就超了 `maxBytes` 的样例
- 电影(`seriesOrder` 只有自己):`toPrefetch` 最多就是它自己,没有「之后」
- 优先级:构造一个同时触发三条规则的样例,断言删除顺序是「窗口外 → 超集数 → 超容量」
- 已经缓存好的不重复出现在 `toPrefetch` 里

- [ ] **Step 3: 跑测试,确认失败**

Run: `./gradlew :core:cache:testDebugUnitTest`

Expected: 编译失败,`Unresolved reference: planCache`。

- [ ] **Step 4: 写最小实现**

实现 `planCache`。它必须是**纯函数**:不读文件、不发请求、不碰 Android、不看时钟(「现在几点」若需要就作为参数传进来)。

KDoc 里写清楚三条规则的优先级,以及「正在播的那一集永不驱逐」这条为什么存在(删掉正在放的文件会直接中断播放)。

- [ ] **Step 5: 跑测试,确认通过**

Run: `./gradlew :core:cache:testDebugUnitTest`

Expected: PASS。

- [ ] **Step 6: 变异验证(必做,逐条)**

至少做这四个,每个都要让**指定的那一条**变红:

1. 把「排除当前条目」那一步去掉 → 「当前这一集永不被驱逐」变红
2. 把按 `lastAccessAt` 排序改成按 `completedAt` → 「按最后访问时间删」变红
3. 把 `maxBytes == null` 的分支改成「直接返回不驱逐任何东西」 → 「不限制时窗口规则照常生效」变红
4. 把 `maxEpisodes` 的截断去掉 → 「取满 10 集就停」变红

逐条记录真实输出并恢复。

- [ ] **Step 7: Commit**

```bash
git add core/cache settings.gradle.kts
git commit -m "✨ feat(cache): 缓存目标与驱逐的纯决策函数

「缓存哪几集、删哪几个」是这个子系统里规则最密、最容易出错的部分,
把它做成不碰文件系统 / 网络 / Android 的纯函数,才能真正测透。

三条驱逐规则按优先级:窗口外 → 超集数上限 → 超存储上限(按最后访问
时间,不是写入时间)。正在播放的那一集在任何规则下都不删 —— 删掉正在
放的文件会直接中断播放。"
```

---

### Task 3: 文件存储、下载与网络类型

**Files:**
- Create: `core/cache/src/main/java/dev/insua/jellycast/cache/AudioCacheStore.kt`
- Create: `core/cache/src/main/java/dev/insua/jellycast/cache/AudioCacheDownloader.kt`
- Create: `core/cache/src/main/java/dev/insua/jellycast/cache/NetworkTypeMonitor.kt`
- Test: `core/cache/src/androidTest/java/dev/insua/jellycast/cache/AudioCacheStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的 DAO
- Produces:
  - `AudioCacheStore`:`suspend fun pathIfComplete(serverId, itemId): String?`、`suspend fun store(serverId, itemId, meta, source): Boolean`(下载完成后原子登记)、`suspend fun delete(serverId, itemId)`、`suspend fun totalBytes(serverId): Long`、`suspend fun touch(serverId, itemId)`
  - `AudioCacheDownloader`:`suspend fun download(url: String, into: File): Boolean`
  - `NetworkTypeMonitor`:`val isUnmetered: Flow<Boolean>` 或 `fun isOnWifi(): Boolean`
  - Task 4、5、6 会用到

- [ ] **Step 1: 写失败的测试**

`androidTest`(需要真实文件系统与 `ConnectivityManager`)。覆盖:

- 下载完成 → 文件存在、大小与索引里记录的一致、`pathIfComplete` 返回该路径
- **下载中途取消 → 临时文件被清掉,索引里没有这条记录,`pathIfComplete` 返回 null**(这是「不完整的文件绝不参与播放」的核心保证)
- 索引里有记录但**文件被外部删除** → `pathIfComplete` 返回 null 并把这条脏记录清掉,不抛异常
- `delete` 同时删文件与索引
- `touch` 更新最后访问时间

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:cache:compileDebugAndroidTestKotlin`

Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

要点:

- 存储位置:优先 `context.getExternalFilesDir("audio-cache")`,拿不到则回退到 `context.filesDir`。**不要用 `cacheDir`** —— 系统会在存储紧张时清掉它,而这里最大可能放 10 GB,被无声清空会让「已缓存」的承诺失效。
- 下载写到 `<name>.part`,**完全成功后才** rename 成正式名并写索引。rename 与写索引之间进程被杀会留下孤儿文件 —— 由启动时的一次孤儿清扫兜底(扫目录,索引里没有的 `.part` 和无主文件删掉)。
- 失败一律静默返回 false,`CancellationException` 重抛。**不要用 `runCatching` 包 suspend 调用。**

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:cache:connectedDebugAndroidTest`

- [ ] **Step 5: 变异验证(必做)**

把「完成后才 rename + 写索引」改成「一开始就写索引」→ 「中途取消后 `pathIfComplete` 返回 null」必须变红。记录输出并恢复。

- [ ] **Step 6: Commit**

```bash
git add core/cache
git commit -m "✨ feat(cache): 文件存储、整集下载与网络类型判定

转码流 Accept-Ranges: none,无法断点续传 —— 中断即丢弃重来,所以
下载写 .part 临时文件,完全成功后才 rename 并写索引。索引里有记录
才算可播,半个文件被当成完整的播表现就是播到一半突然结束。

存储放 getExternalFilesDir 而不是 cacheDir:后者会被系统在存储紧张
时清掉,而这里最大可能放 10 GB,被无声清空会让「已缓存」的承诺失效。"
```

---

### Task 4: `PlaybackSource` 的本地标记与引擎的本地 seek

**Files:**
- Modify: `core/model/src/main/java/dev/insua/jellycast/model/Playback.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/AudioPlaybackEngine.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/`(扩充既有引擎测试)

**Interfaces:**
- Consumes: 无
- Produces: `PlaybackSource` 新增 `val isLocalFile: Boolean = false`(**加在末尾并给默认值** —— 各模块测试里有大量既有构造调用)。Task 5 会构造 `isLocalFile = true` 的源。

**这是这批里风险最高的一个任务** —— 它动的是已经反复修过的播放引擎。既有行为一步都不能变。

- [ ] **Step 1: 写失败的测试**

在既有引擎测试里追加。必须覆盖**两条路径各自的行为**:

- **远端源(`isLocalFile = false`)行为完全不变**:`absolutePositionMs` 仍是「流起点 + 流内位置」;`seekTo` 仍然**重新解析** URL(断言 provider 被再次调用)
- **本地源(`isLocalFile = true`)**:`absolutePositionMs` **就是**播放器位置(不加偏移);`seekTo` **不重新解析**(断言 provider 没有被再次调用),而是直接让播放器 seek
- 本地源首次 `play(startPositionMs = X)`:prepare 之后播放器被 seek 到 X

第一条是**防回归的核心** —— 缓存功能绝不能把非缓存路径搞坏。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:player:testDebugUnitTest`

- [ ] **Step 3: 写最小实现**

`PlaybackSource` 加字段;引擎在三处分支:`absolutePositionMs`、`resolveAndPrepare`(本地源 prepare 后 seek,且流起点基准置 0)、`seekTo`(本地源直接 `playerControl.seekTo`)。

`ExoPlayerControl` 若还没有 `seekTo`,一并补上并保持接口最小。

KDoc 里写清楚**为什么**本地源可以直接 seek(本地完整文件支持 Range,而转码流 `Accept-Ranges: none`),以及这正是「拖进度条不跟手」在缓存路径上被根治的原因。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:testDebugUnitTest` — 全模块绿(既有 180+ 条一条都不能挂)。

- [ ] **Step 5: 变异验证(必做)**

1. 把本地源的 `seekTo` 改回「重新解析」→ 「本地源 seek 不重新解析」变红
2. 把 `absolutePositionMs` 的本地分支去掉(统一加偏移)→ 「本地源绝对位置」变红
3. 把远端源也改成直接 seek → **「远端源行为不变」那条必须变红** —— 这条是防回归的证据

逐条记录输出并恢复。

- [ ] **Step 6: Commit**

```bash
git add core/model core/player
git commit -m "✨ feat(player): 本地缓存源支持直接 seek

转码流 Accept-Ranges: none,所以 seek 只能靠「带新的 startTimeTicks
重新解析 URL + 等服务端起转码」—— 这就是拖进度条不跟手的根源。
本地完整文件没有这个限制,可以直接 seekTo,响应是即时的。

PlaybackSource 增加 isLocalFile 标记,引擎据此分两条路:本地源的
播放器位置就是绝对位置、seek 不重新解析;远端源行为一字不变。"
```

---

### Task 5: 缓存感知的播放源

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/CacheAwareSourceProvider.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/CacheAwareSourceProviderTest.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/di/PlayerModule.kt`

**Interfaces:**
- Consumes: Task 3 的 `AudioCacheStore.pathIfComplete` / `touch`;Task 4 的 `PlaybackSource.isLocalFile`
- Produces: 一个 `PlaybackSourceProvider` 的装饰器实现;Task 6 不依赖它

- [ ] **Step 1: 写失败的测试**

- 有完整缓存 → 返回的 `PlaybackSource.streamUrl` 指向本地文件、`isLocalFile == true`、**且底层解析器一次都没被调用**(断言没有发生网络解析,这是缓存生效的实质证据)
- 没有缓存 → 原样委派,返回的源与底层解析器返回的**完全一致**(逐字段断言,别只断言非空)
- 缓存查询自身抛异常 → **静默降级为委派**,不向上抛(铁律)
- 命中缓存时更新最后访问时间(`touch` 被调用)

- [ ] **Step 2–3: 确认失败 → 实现**

装饰器要薄:查缓存、命中就构造本地源、否则委派。**元数据(音轨、字幕轨、mediaSourceId)从哪来**是个真问题 —— 本地文件没有这些。决定并在 KDoc 写明:命中缓存时仍然需要这些信息来渲染字幕,所以要么一并缓存元数据,要么仍走一次 `PlaybackInfo` 查询但用本地 URL 播放。**推荐后者**:元数据查询很轻(不起转码),而缓存元数据会引入一致性问题。断网时这次查询失败 → 退化为「无字幕但能播」,符合铁律。

把这个决定写进 KDoc 并加对应用例(断网时仍返回本地源,只是字幕为空)。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:testDebugUnitTest`

- [ ] **Step 5: 变异验证(必做)**

1. 让命中缓存时也调用底层解析器取流 URL → 「命中缓存时不发生网络解析」变红
2. 把缓存查询的异常改成向上抛 → 「查询失败静默降级」变红

- [ ] **Step 6: Commit**

```bash
git add core/player
git commit -m "✨ feat(player): 命中缓存时改播本地文件

以装饰器的形式包在既有解析器外面,未命中就原样委派 —— L1/L3 降级链
一字不动。命中时不再向服务端要流,也就不再起转码作业。

缓存查询失败一律静默降级为「没有缓存」,绝不打断播放。"
```

---

### Task 6: 预取控制器

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/CachePrefetchController.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/CachePrefetchControllerTest.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackService.kt`(接上驱动时机)

**Interfaces:**
- Consumes: Task 2 的 `planCache`;Task 3 的 `AudioCacheStore` / `AudioCacheDownloader` / `NetworkTypeMonitor`;既有的剧集顺序查询(参照 `AutoPlayNextController` 怎么问服务端要季/集列表)
- Produces: 无

- [ ] **Step 1: 写失败的测试**

用假的下载器/存储/网络监听,断言**编排**行为:

- **非 WiFi 时一个下载都不发起**
- WiFi 下按 `planCache` 给的顺序下载
- **同一时刻只有一个下载在跑** —— 用一个可控的挂起点造出「第一个还没完成」的窗口,断言第二个没有开始。这条是保护 NAS 的关键,必须真能失败
- 已缓存的不重复下载
- 驱逐在预取之前执行(先腾地方再下载)
- 下载失败不影响后续条目
- 换集时重新计算计划,并**取消**上一次尚未完成的预取

- [ ] **Step 2–3: 确认失败 → 实现**

驱动时机接在 `PlaybackService` 上。**注意 `PlaybackService` 在 JVM 单测里造不出来**(仓库既有经验),所以编排逻辑全部放在 `CachePrefetchController` 里,Service 只负责在换集时调它一次。

- [ ] **Step 4: 跑测试,确认通过**

- [ ] **Step 5: 变异验证(必做)**

1. 去掉 WiFi 判定 → 「非 WiFi 不下载」变红
2. 把串行改成并发 → 「同一时刻只有一个下载」变红
3. 把「先驱逐后下载」的顺序调换 → 对应用例变红

- [ ] **Step 6: Commit**

```bash
git add core/player
git commit -m "✨ feat(player): 预取控制器,仅 WiFi 串行缓存后续集数

每个预取请求都是服务端的一个转码作业,而服务器是 J4125 —— 并行拉
多集会把 NAS 打爆,首先受害的是用户正在听的那一集。所以任何时刻
最多一个下载在跑,且只在 WiFi 下发起。

编排逻辑全在控制器里:PlaybackService 在 JVM 单测里造不出来,把决策
留在它内部等于没有测试。"
```

---

### Task 7: 存储上限设置项

**Files:**
- Modify: `core/datastore/src/main/java/dev/insua/jellycast/datastore/PreferencesStore.kt`
- Modify: `feature/settings/src/main/java/dev/insua/jellycast/feature/settings/SettingsScreen.kt`
- Modify: `feature/settings/src/main/java/dev/insua/jellycast/feature/settings/SettingsViewModel.kt`
- Test: `core/datastore` 与 `feature/settings` 的既有测试

**Interfaces:**
- Consumes: 既有的 `PreferencesStore` 形态、设置页既有的 `ChoiceRow`
- Produces: 一个存储上限偏好(**默认 1 GB**),Task 6 的计划计算读它

- [ ] **Step 1: 写失败的测试**

- 没设置过时默认是 1 GB
- 四个选项都能存下并读回:1 GB / 5 GB / 10 GB / 不限制
- 「不限制」在模型层是一个明确的值(建议 `null` 或 0),**断言它和 1 GB 能被区分开** —— 混淆的话「不限制」会退化成「1 GB」

- [ ] **Step 2–3: 确认失败 → 实现**

设置页沿用既有 `ChoiceRow`(音频码率那一行的形态),放在「网络」分组附近或新起一个「缓存」分组 —— 读一下既有分组结构再定,并在报告里说明选了哪种。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:datastore:testDebugUnitTest :feature:settings:testDebugUnitTest`

- [ ] **Step 5: 变异验证(必做)**

把默认值从 1 GB 改成别的 → 「默认 1 GB」变红。把「不限制」和 1 GB 映射成同一个值 → 「两者可区分」变红。

- [ ] **Step 6: 全量回归 + 设备验证**

```bash
./gradlew test
./gradlew connectedDebugAndroidTest
```

再在模拟器上装包,按设计文档 §7.3 走一遍:播一集 → 等预取 → 断网 → 看下一集能不能播。**能做到哪一步就报到哪一步**,做不到的(比如模拟器上没配服务器)如实说明,不要暗示验证过。

- [ ] **Step 7: Commit**

```bash
git add core/datastore feature/settings
git commit -m "🔧 feat(settings): 新增缓存最大占用存储设置

默认 1 GB,可选 5 GB / 10 GB / 不限制。「不限制」只是解除总量约束,
窗口规则(只留当前及之后)与 10 集上限照常生效 —— 否则一部长剧会
把手机存储吃干净。"
```

---

## 计划自查

**1. Spec 覆盖**

| 设计文档要求 | 落点 |
|---|---|
| §2 整集缓存(非分块) | Task 3 的下载器 |
| §3 仅 WiFi、串行一次一集 | Task 6 |
| §4.1 窗口:当前及之后 | Task 2 |
| §4.2 最多 10 集 | Task 2 |
| §4.3 存储上限与「不限制」 | Task 2(策略)+ Task 7(偏好与 UI) |
| §4.4 三条规则优先级 + 正在播的不删 | Task 2 |
| §5.2 缓存感知播放源 | Task 5 |
| §5.2 本地 seek / 绝对位置 | Task 4 |
| §5.3 预取驱动 | Task 6 |
| §7.1 纯函数单测清单 | Task 2 Step 2 |
| §7.2 设备测试清单 | Task 1、Task 3 |
| §7.3 端到端 | Task 7 Step 6 |
| §8 验收标准 | 各 Task 的验收 + Task 7 Step 6 |

无遗漏。

**2. 占位符扫描**

Task 1/2/3/5/6/7 的 Step 1 给的是**要覆盖的行为清单而非可粘贴代码**,并各自写明了原因(必须先读既有测试才能确定构造方式与模块惯例)。这是有意的:这个仓库的既有测试写法差异较大,照抄一段猜出来的代码比给清单更容易出错。所有**改实现**的步骤都给了明确的要点与约束。

**3. 类型一致性**

- `CachedAudioEntity` 字段 —— Task 1 定义,Task 2 的 `CachedEntry` **刻意不复用它**(策略不耦合存储结构),两者的映射发生在 Task 6 ✓
- `planCache(currentItemId, seriesOrder, cached, maxEpisodes, maxBytes)` —— Task 2 定义,Task 6 调用 ✓
- `AudioCacheStore.pathIfComplete / store / delete / totalBytes / touch` —— Task 3 定义,Task 5、6 使用 ✓
- `PlaybackSource.isLocalFile` —— Task 4 定义(末尾 + 默认值),Task 5 构造 ✓
- `maxBytes: Long?`(null = 不限制)与 Task 7 的偏好表示必须一致 —— Task 7 Step 1 明确要求断言两者可区分 ✓
