# 离线缓存、系统集成与浏览层对齐 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 让 JellyCast 断网可用、系统媒体面板完整、浏览体验对齐 Jellyfin Web mobile,并补上收藏/排序/合集/已看标记。

**Architecture:** 先在 `:core:database` 建条目缓存,`:core:network` 之上加 stale-while-revalidate 仓储层(阶段 1,后续都依赖)。播放器的上下一集通过 `ForwardingPlayer` 暴露命令实现,**不伪造 Media3 播放列表**。

**Tech Stack:** Kotlin · Compose · Room · Media3 · Hilt · Coil 3 · JUnit5 + MockK(JVM)· JUnit4 + AndroidJUnit4(仪器)

## Global Constraints

- 设计文档:`docs/superpowers/specs/2026-07-27-offline-cache-system-integration-design.md` —— 需求以它为准。
- 🔴 **凭据铁律:真实服务器地址、用户名、密码不得出现在代码、测试、文档、提交信息、日志或任何报告中。** 端到端测试从已 gitignore 的 `testing.properties` 读;缺失时**跳过**而非失败。每个 Task 收尾跑 `git status --short`(不得出现 `testing.properties`)。
- 🔴 **网络不可用绝不允许崩溃。** 所有网络失败路径必须收敛到设计文档 §3.2 的四种状态之一。
- 🔴 **主题保持浅色。** 参照 Web mobile 的是结构与信息层级,不是配色。
- 🔴 **播放页形态不变** —— 大封面 + 歌词式字幕。
- **禁止凭记忆写 Jellyfin API。** 每个路径与参数用 `jq` 核对 `docs/jellyfin-openapi.json`。**Jellyfin 对错误大小写的查询参数静默忽略、不报错**,v1 期间已查出 6 处。
- **永远不渲染视频**:不引入 `media3-ui`、不建 `PlayerView`、不绑 `Surface`。
- **不允许全局关闭 TLS 校验。**
- **ticks 只在网络映射层换算一次**(`ms = ticks / 10_000`)。
- 不得削弱既有保证:seek 走重新 resolve 而非 `player.seekTo` / 首个成功探测胜出 / 落选探测真取消 / `CancellationException` 传播 / L3 恒可播 / 字幕失败不影响播放 / 进度上报不抛错 / 前台服务进入不依赖网络延迟 / L1 探测结果按 mediaSource 缓存 / 无路径可把会话钉死在 L3。
- 版本走 `gradle/libs.versions.toml` 别名,**不写死**。
- **AGP 9 内置 Kotlin:绝不 apply `org.jetbrains.kotlin.android`;没有 `kotlinOptions { }`。**
- 源集:`src/test/` = JUnit5 + MockK;`src/androidTest/` = JUnit4 + AndroidJUnit4。**不混用。**
- TDD:先写测试、跑、**确认失败**、再实现。
- 跑 gradle 带 `--no-daemon`。模拟器 `emulator-5554` 可用。
- 每个 Task 结束 commit,Conventional Commits。
- **中途不打包**,全部完成后统一出包。

---

# 阶段 1:缓存与离线(后续全部依赖)

### Task 1:Room 条目缓存表

**Files:**
- Create: `core/database/src/main/java/dev/insua/jellycast/database/CachedItemEntity.kt`
- Create: `core/database/src/main/java/dev/insua/jellycast/database/CachedItemDao.kt`
- Modify: `core/database/src/main/java/dev/insua/jellycast/database/JellyCastDatabase.kt`
- Test: `core/database/src/androidTest/java/dev/insua/jellycast/database/CachedItemDaoTest.kt`

**Interfaces:**
```kotlin
@Entity(tableName = "cached_item", primaryKey = ["serverId","bucket","itemId"])
data class CachedItemEntity(
    val serverId: String, val bucket: String, val itemId: String,
    val position: Int,            // 保序:同一 bucket 内的展示顺序
    val payloadJson: String,      // 序列化后的 MediaItem
    val updatedAt: Long,
)
@Dao interface CachedItemDao {
    fun observeBucket(serverId: String, bucket: String): Flow<List<CachedItemEntity>>
    suspend fun replaceBucket(serverId: String, bucket: String, items: List<CachedItemEntity>)
    suspend fun clearServer(serverId: String)
    suspend fun clearAll()
}
```
`bucket` 是逻辑分区键,如 `home.resume` / `home.nextup` / `home.recent` / `views` /
`library.series` / `series.<seriesId>.seasons` / `season.<seasonId>.episodes`。

- [ ] **Step 1: 写失败的仪器测试**

覆盖:`replaceBucket` 全量替换(旧的不残留)、`observeBucket` 按 `position` 返回且顺序正确、
**不同 serverId 互不干扰**(多服务器是核心需求)、`clearServer` 只清该服务器、空 bucket 返回空列表。

用 `Room.inMemoryDatabaseBuilder`,`@Before` 建 `@After` 关。
**FIFO/顺序断言必须让 `position` 与插入顺序、与 itemId 字典序都不一致**,否则丢了 `ORDER BY` 也能通过。

- [ ] **Step 2: 跑,确认失败**

Run: `./gradlew --no-daemon :core:database:connectedDebugAndroidTest`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 Entity / Dao,并给 Database 加迁移**

`JellyCastDatabase` 版本从 1 升到 2。**必须写 `Migration(1,2)`,不得用
`fallbackToDestructiveMigration()`** —— 那张表里存着用户尚未上报的观看进度,销毁式迁移会丢数据。

- [ ] **Step 4: 跑,确认通过;补一个迁移测试**

用 `MigrationTestHelper` 验证 1→2 迁移后 `progress_report` 里的数据仍在。

- [ ] **Step 5: 泄漏复查 + Commit**:`feat(database): add item cache table with per-server buckets`

---

### Task 2:stale-while-revalidate 仓储(纯逻辑)

**Files:**
- Create: `core/model/src/main/java/dev/insua/jellycast/model/Cached.kt`
- Create: `core/network/src/main/java/dev/insua/jellycast/network/repository/CachePolicy.kt`
- Test: `core/network/src/test/java/dev/insua/jellycast/network/repository/CachePolicyTest.kt`

**Interfaces:**
```kotlin
/** 一次取数的结果:数据 + 它是不是旧的 + 本次刷新是否失败 */
data class Cached<T>(val data: T, val isStale: Boolean, val refreshFailed: Boolean = false)

/**
 * 纯函数,不做 IO:给定"缓存有无"与"网络结果",决定发射什么。
 * 之所以抽成纯函数,是因为这几条分支正是断网崩溃的根源,必须能离线穷举。
 */
object CachePolicy {
    fun <T> resolve(cache: T?, network: Result<T>?): List<Cached<T>>
}
```

- [ ] **Step 1: 写失败的测试(JUnit5)**

穷举四种组合,断言**发射序列**:

| 缓存 | 网络 | 期望发射序列 |
|---|---|---|
| 有 | 成功 | `[Cached(cache, stale=true), Cached(net, stale=false)]` |
| 有 | 失败 | `[Cached(cache, stale=true), Cached(cache, stale=true, refreshFailed=true)]` |
| 无 | 成功 | `[Cached(net, stale=false)]` |
| 无 | 失败 | `[]` —— 由调用方转成错误态,**不得抛异常** |

再加:网络成功但返回空列表时,**不得把缓存清空后又发射空**(要能区分"服务器确实没有"与"请求失败")。

- [ ] **Step 2: 跑确认失败 → 实现 → 跑确认通过**

Run: `./gradlew --no-daemon :core:network:test --tests '*CachePolicyTest*'`

- [ ] **Step 3: Commit**:`feat(network): add stale-while-revalidate cache policy`

---

### Task 3:仓储接入 + ViewModel 改为缓存优先

**Files:**
- Create: `core/network/src/main/java/dev/insua/jellycast/network/repository/MediaRepository.kt`
- Modify: `feature/home/.../HomeViewModel.kt`、`feature/library/.../LibraryViewModel.kt`
- Test: 对应 ViewModel 单测

**Interfaces:**
```kotlin
class MediaRepository(
    private val api: JellyfinApi, private val dao: CachedItemDao,
    private val session: JellyfinSession, private val json: Json,
) {
    fun bucket(bucket: String, fetch: suspend () -> List<MediaItem>): Flow<Cached<List<MediaItem>>>
}
```

- [ ] **Step 1: 写失败的测试**

用 MockK 假 api + 内存假 dao(不要在 JVM 单测里拉真 Room),断言:
- 有缓存时**第一次发射就是缓存**,不等网络
- 网络成功后发射新数据且**写回了缓存**
- 网络失败时保留缓存并置 `refreshFailed`,**不抛异常**
- 无缓存 + 网络失败 → ViewModel 进入错误态,**不崩溃**

- [ ] **Step 2: 跑确认失败 → 实现 → 确认通过**

`HomeViewModel` 三个分区、`LibraryViewModel` 的剧集/电影/剧集详情全部改走 `MediaRepository`。
**保持既有行为不变:三个分区并发加载互不阻塞、单个分区失败不影响其余、分页语义不变。**

- [ ] **Step 3: 全量回归 + Commit**:`feat(network): serve lists from cache first, refresh in background`

---

### Task 4:离线态 UI 契约 + 端到端断网场景

**Files:**
- Modify: `feature/home/.../HomeScreen.kt`、`feature/library/.../LibraryScreen.kt`、`core/designsystem`(离线提示条组件)
- Modify: `app/src/androidTest/.../e2e/PlaybackE2eTest.kt` 或新增 `OfflineE2eTest.kt`

- [ ] **Step 1: 写失败的端到端测试**

用 `adb shell svc wifi disable / svc data disable`(或测试内切换)模拟断网:

| 场景 | 断言 |
|---|---|
| 有缓存 + 断网启动 | 进程存活、列表显示缓存内容、出现离线提示 |
| 无缓存 + 断网启动 | 进程存活、显示可重试错误页 |
| 断网时点播放 | 进程存活、给出明确提示 |

**断言必须包含"进程仍存活"** —— 用户报告的就是闪退。

> 注意:测试结束务必恢复网络,否则污染后续测试。用 `@After` 保证。

- [ ] **Step 2: 跑确认失败 → 实现 → 确认通过**

离线提示条:顶部一条可关闭的横幅「离线,显示的是上次内容」。**不要用弹窗打断浏览。**

- [ ] **Step 3: Commit**:`feat(app): keep browsing usable while offline`

---

### Task 5:封面图磁盘缓存

**Files:** Modify: `app/src/main/java/dev/insua/jellycast/JellyCastApp.kt`

- [ ] **Step 1: 给 Coil 的 `ImageLoader` 显式配置磁盘缓存**

`diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(...).build() }`,
容量上限取一个明确值并写进注释说明依据。**必须复用既有的 `@TrustAwareHttpClient`**
(v1 已修:Coil 默认自建 OkHttpClient 不共享证书信任,自签服务器下封面加载不出来)。

- [ ] **Step 2: 装机验证**:断网后封面仍显示。截图为证。
- [ ] **Step 3: Commit**:`feat(app): cache cover art on disk for offline browsing`

---

# 阶段 2:播放可靠性收尾

### Task 6:L3 路径下的 seek

**先复现,再改。** 已知:L3 URL 带 `static=true`,服务端**静默忽略 `startTimeTicks`** ——
进度条跳了、音频从头放。v2 已加了能检出它的断言(强制走 L3 时 seek 场景会红)。

- [ ] **Step 1: 对真实服务器实测**

用 curl 试非 static 的 L3 转码 URL 是否接受 `startTimeTicks`。**参数组合以 `docs/jellyfin-openapi.json`
的 `/Videos/{itemId}/stream` 参数表为准,逐个核对。** 把实测结果(URL 模板、HTTP 状态、
`ffprobe` 输出、是否真从目标位置开始)写进报告。

- [ ] **Step 2: 按实测结果二选一**

- 若非 static URL 支持 `startTimeTicks` → L3 改用它,seek 真生效
- 若确实不支持 → **在 L3 下明确禁用 seek 并在 UI 说明**(进度条不可拖、快进按钮置灰),
  **绝不允许"假装成功"**

**选哪条以实测为准,在报告中给出证据。**

- [ ] **Step 3: 强制走 L3 跑端到端 seek 场景,确认由红转绿(或按方案二正确禁用)**
- [ ] **Step 4: Commit**

### Task 7:快速连续 seek 的防抖

已知偶发(约 1/7)报「该条目无法播放」,音频其实还在正常放 —— 是竞态,不是崩溃。

- [ ] **Step 1: 写能稳定复现的测试** —— 端到端里连续快速发多次 seek,断言不出现错误态。
  若难以稳定复现,先加**可控的假 resolver** 在 JVM 层复现竞态。
- [ ] **Step 2: 实现防抖**:UI 层拖动只在**松手时**发一次 seek(已有);
  引擎层对连续 seek 做合并,只有最后一次生效,**过期结果不得置错误态**。
- [ ] **Step 3: 确认由红转绿 + Commit**

---

# 阶段 3:系统集成

### Task 8:媒体元数据

**已确认全项目从未设过 `MediaMetadata`** —— 锁屏、通知、流体云因此是空白的。

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackMetadata.kt`
- Modify: 构造 Media3 `MediaItem` 处(`ExoPlayerControl` / `AudioPlaybackEngine`)
- Test: `core/player/src/test/java/dev/insua/jellycast/player/PlaybackMetadataTest.kt`

- [ ] **Step 1: 写失败的测试**

```kotlin
@Test fun `剧集带标题副标题与封面`() {
    val md = episodeItem.toMediaMetadata(posterUrl = "http://x/img")
    assertEquals("一旦约定誓死也要遵守", md.title)
    assertEquals("银魂 · S01E06", md.artist)     // 复用 displaySubtitle,不重写格式化
    assertEquals("http://x/img".toUri(), md.artworkUri)
}
@Test fun `无封面时 artworkUri 为 null 而不是空串`() { }
@Test fun `电影无剧名时副标题不出现多余分隔符`() { }
```

- [ ] **Step 2: 跑确认失败 → 实现 → 确认通过**

`setTitle` / `setArtist` / `setArtworkUri` / `setIsPlayable(true)` / `setMediaType(MEDIA_TYPE_MUSIC)`。

- [ ] **Step 3: 加端到端断言**:开始播放后 `MediaController.mediaMetadata.title` 等于集名。
- [ ] **Step 4: Commit**:`feat(player): publish media metadata for lockscreen and system surfaces`

---

### Task 9:上一集 / 下一集(通知 · 锁屏 · 蓝牙 · 车机)

**根因:** 播放器只持有单条目,`hasNextMediaItem()` 恒 false,Media3 因此不生成这些按钮。
**方案:不伪造播放列表**(会与"每次 seek 重新 resolve"冲突),改用 `ForwardingPlayer` 暴露命令。

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/SeekInterceptingPlayer.kt`
- Test: `core/player/src/test/.../SeekInterceptingPlayerTest.kt` + 端到端

- [ ] **Step 1: 写失败的测试**

- 队列有下一集时,`availableCommands` 含 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`;队列到底时不含
- 调 `seekToNextMediaItem()` 触发**队列前进 + 引擎播放新条目**,且**不落到底层 player**
- `seekToPreviousMediaItem()` 同理
- ⚠️ **`seekToPrevious()`(不带 MediaItem 后缀)语义不同** —— 非直播且位置超阈值时是
  "回到本集开头"。v1 已为它做过拦截,**断言其行为未被破坏**

- [ ] **Step 2: 跑确认失败 → 实现 → 确认通过**

覆写 `getAvailableCommands()` 时,在既有命令集上**增删**,不要整个重建(会丢掉 Media3 需要的其他命令)。

- [ ] **Step 3: 端到端验证**:从 `MediaController` 发 `seekToNextMediaItem()`,断言真的切到下一集并继续播放。
- [ ] **Step 4: Commit**:`feat(player): expose next/previous episode to system media controls`

---

### Task 10:迷你播放条加控制

**Files:** Modify: `core/designsystem/.../MiniPlayerBar.kt` 及其调用方与 UI 测试

- [ ] **Step 1: 写失败的 Compose 测试**

新增下一集按钮:点击触发 `onSkipToNext`;**队列到底时该按钮不显示或禁用**;
**已有行为不得破坏**:点播放/暂停不冒泡到展开、点整条触发展开。

- [ ] **Step 2: 实现 → 跑通** —— 64dp 高度与既有布局约束不变。
- [ ] **Step 3: Commit**:`feat(designsystem): add skip-to-next to the mini player bar`

---

### Task 11:应用图标

**已确认 `app/src/main/res/` 下只有 `values`,用的是 Android 默认图标。**

**Files:** `res/drawable/ic_launcher_foreground.xml`、背景、`res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`、Manifest

- [ ] **Step 1: 设计并实现矢量自适应图标**

方向:呼应"把剧当播客听" —— 音频/播放意象,**不用视频/胶片元素**(与产品定位冲突)。
前景内容必须落在 108dp 画布中心 66dp 安全区内,否则圆形遮罩会裁掉。用 `VectorDrawable`。

- [ ] **Step 2: 装机截图验证**(圆形遮罩下不裁切、不糊)。结论如实写进报告。
- [ ] **Step 3: Commit**:`feat(app): add adaptive launcher icon`

---

# 阶段 4:浏览层对齐 Web mobile(保持浅色)

### Task 12:「我的媒体」库入口

- [ ] **Step 1: 核对接口**

```bash
jq -r '.paths["/UserViews"].get.parameters[]?.name' docs/jellyfin-openapi.json
```
预期含 `userId`。**大小写以输出为准。**

- [ ] **Step 2: 写失败测试 → `JellyfinApi.userViews()` + 走 `MediaRepository` 缓存 → 首页新增分区**

与既有三个分区**并发加载、互不阻塞、单个失败不影响其余**。库卡片点击进入该库列表。

- [ ] **Step 3: Commit**:`feat(home): add my-media library entries`

### Task 13:继续收听改 16:9 + 已听进度

- [ ] 新增 16:9 卡片(不破坏既有 `PosterCard` 调用方),底部进度条按 `resumePositionMs / runTimeMs`,
      中央播放三角。Compose 测试覆盖:进度为 0 时不显示进度条;`runTimeMs` 为 null 时不崩。
- [ ] Commit:`feat(home): show continue-listening as wide cards with progress`

### Task 14:最近添加按库分组 + 未看角标

- [ ] 核对 `UserItemDataDto.UnplayedItemCount` 后,按库分组展示,标题右侧 `>` 进入该库;
      角标在计数为 0 时不显示。
- [ ] Commit:`feat(home): group recently added by library with unplayed badges`

### Task 15:顶部栏与标签

- [ ] 顶部:应用标识 + 搜索入口 + 账号入口;下方「首页 / 我的最爱」标签。
      **「我的最爱」在 Task 16 完成前不显示**,不做点进去是空的假入口。
- [ ] Commit:`feat(home): add top bar and section tabs`

---

# 阶段 5:功能缺口与工程清理

### Task 16:收藏

- [ ] **Step 1: 核对接口**

```bash
jq -r '.paths["/UserFavoriteItems/{itemId}"] | keys[]' docs/jellyfin-openapi.json
jq -r '.paths["/UserFavoriteItems/{itemId}"] | to_entries[] | "\(.key) \(.value.parameters[]?.name)"' docs/jellyfin-openapi.json
```
记录实际的 HTTP 方法与参数。

- [ ] **Step 2: 写失败测试 → 实现**

收藏/取消收藏;失败时**回滚乐观更新并提示**,不静默吞掉。首页「我的最爱」标签接上
(`/Items` + `isFavorite=true` 或 `filters=IsFavorite`,**以核对结果为准**)。

- [ ] **Step 3: Commit**:`feat(library): add favorites`

### Task 17:标记已看 / 未看

- [ ] 核对 `/UserPlayedItems/{itemId}` 的方法与参数 → 实现 → 列表与详情页可切换,乐观更新可回滚。
- [ ] Commit:`feat(library): mark items played or unplayed`

### Task 18:排序与筛选

- [ ] `/Items` 已有 `sortBy` / `sortOrder` / `filters` / `isPlayed` / `isFavorite`(已核对)。
      `ItemFilter` 枚举含 `IsUnplayed` / `IsPlayed` / `IsFavorite` / `IsResumable`。
      提供:名称 / 加入时间 / 播放时间排序,升降序,以及"仅未看""仅收藏"筛选。
      **筛选条件必须参与缓存 bucket 键**,否则不同筛选会互相覆盖缓存。
- [ ] Commit:`feat(library): add sorting and filtering`

### Task 19:合集(BoxSet)

- [ ] `BoxSet` 已确认是合法 `BaseItemKind`。媒体库增加合集浏览,进入后列出其内容。
- [ ] Commit:`feat(library): browse collections`

### Task 20:清理仓库中的服务器地址

**⚠️ 含历史重写,属破坏性操作。执行前必须再次向用户确认。**

- [ ] **Step 1: 先清理工作区**

替换 6 个已提交文件中的真实地址为占位符:2 个测试夹具、3 份 v1 文档
(`docs/jellyfin-openapi.json` 是服务器自己生成的,**单独询问用户是否也要处理**)。

- [ ] **Step 2: 历史重写(仅在用户明确同意后)**

用 `git filter-repo`。**执行前必须先备份仓库**。完成后全历史 grep 复查。

- [ ] **Step 3: Commit**

---

## Self-Review 记录

- **Spec 覆盖:** §3.1 缓存→Task 1-3;§3.2 离线 UI→Task 4;§3.3 上下一集→Task 9;§3.4 元数据→Task 8;§3.5 L3 seek→Task 6;§3.6 浏览层→Task 12-15;§3.7 新功能接口→Task 16-19;§4 测试→各 Task 内含端到端;§5 凭据→Global Constraints + Task 20。封面磁盘缓存→Task 5;seek 防抖→Task 7;图标→Task 11;迷你条→Task 10。
- **占位符:** Task 6 的最终方案依赖真实服务器实测,这是**刻意的** —— 预先写死修法就是猜。已给出二选一的判定标准与证据要求。Task 16/17 的 HTTP 方法要求实现时 `jq` 核对而非我在此断言,同理。
- **类型一致性:** `Cached<T>`(Task 2)被 Task 3/4 使用;`CachedItemDao`(Task 1)被 Task 3 的 `MediaRepository` 使用;`toMediaMetadata`(Task 8)在 Task 8 定义;`bucket` 键命名在 Task 1 定义,Task 3/18 沿用(Task 18 追加筛选条件进键)。
- **顺序依赖:** 阶段 1 是后续全部的基础;阶段 4、5 依赖阶段 1 的仓储层;阶段 2、3 相互独立可并行,但都排在阶段 1 之后以免在旧数据层上返工。
- **已知风险:** Task 3 会改动所有列表类 ViewModel 的取数方式,既有测试需同步调整 —— 已写明"保持既有行为不变",不得借机削弱断言。
