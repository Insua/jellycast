# 崩溃修复与功能可用性 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 任何条目都不许崩;所有已做的功能都可点、可用。

**Architecture:** 崩溃走三层防御(根因 + 中间层捕获 + ViewModel 兜底),并补上纯 Kotlin 模块的真机冒烟测试 —— 这是本次最重要的结构性改动。其余是导航接线、队列构造、选轨与歌词定位。

**Tech Stack:** Kotlin · Compose · Media3 · Room · Hilt · JUnit5 + MockK(JVM)· JUnit4 + AndroidJUnit4(仪器)

## Global Constraints

- 设计文档:`docs/superpowers/specs/2026-07-28-crash-and-usability-design.md` —— 需求以它为准。
- 🔴 **任何条目都不许崩。** 这是本批的第一优先,压倒一切。
- 🔴 **新增铁律:凡运行在 Android 上、且依赖平台 API 行为(正则、`Uri`、时间/文本处理)的纯 Kotlin 模块,必须有真机冒烟测试。** JVM 单测结构上测不出平台差异 —— 本次崩溃就是明证。
- 🔴 **凭据铁律:** 真实服务器地址、用户名、密码不得出现在代码、测试、文档、提交信息、日志或任何报告中。端到端测试从已 gitignore 的 `testing.properties` 读;缺失时**跳过**而非失败。**提交信息里也不得出现用户的剧名。**
- **禁止凭记忆写 Jellyfin API。** 每个路径与参数用 `jq` 核对 `docs/jellyfin-openapi.json`。**Jellyfin 对错误大小写的查询参数静默忽略**,v1 期间已查出 6 处。
- **永远不渲染视频**:不引入 `media3-ui`、不建 `PlayerView`、不绑 `Surface`。
- **不允许全局关闭 TLS 校验。**
- **网络不可用绝不允许崩溃。**
- **ticks 只在网络映射层换算一次**(`ms = ticks / 10_000`)。
- 不得削弱既有保证:seek 走重新 resolve 而非 `player.seekTo` / 首个成功探测胜出 / 落选探测真取消 / `CancellationException` 传播 / L3 恒可播 / 字幕失败不影响播放 / 进度上报不抛错 / 前台服务进入不依赖网络延迟 / L1 探测按 mediaSource 缓存 / 无路径把会话钉死在 L3 / 排序筛选参与缓存 bucket 键 / 点某集把整季作为队列传出。
- 版本走 `gradle/libs.versions.toml` 别名,**不写死**。
- **AGP 9 内置 Kotlin:绝不 apply `org.jetbrains.kotlin.android`;没有 `kotlinOptions { }`。**
- 源集:`src/test/` = JUnit5 + MockK;`src/androidTest/` = JUnit4 + AndroidJUnit4。**不混用。**
- TDD:先写测试、跑、**确认失败**、再实现。
- 跑 gradle 带 `--no-daemon`。模拟器 `emulator-5554` 可用。
- 每个 Task 结束 commit,Conventional Commits。

---

# 阶段 1:崩溃(最高优先)

### Task 1:正则崩溃的三层修复 + `:core:subtitle` 真机冒烟测试

**Files:**
- Modify: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/SubtitleTags.kt`(及其余含正则的解析器)
- Modify: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/SubtitleRepository.kt`
- Modify: `feature/player/src/main/java/dev/insua/jellycast/feature/player/PlayerViewModel.kt`
- Create: `core/subtitle/src/androidTest/java/dev/insua/jellycast/subtitle/SubtitleParsingSmokeTest.kt`

**根因(已实测确认):** `Regex("""\{[^}]*}""")` 里未转义的 `}` 在 OpenJDK 合法、在 **Android 14+ 的 ICU 引擎上是语法错误**。`SubtitleTags` 是 `object`,正则在静态初始化里编译 → 抛 `ExceptionInInitializerError`(是 `Error` 不是 `Exception`)→ `catch (e: Exception)` 接不住 → 穿透 `viewModelScope.launch` → 进程被杀。

- [ ] **Step 1: 先写会失败的真机测试**

`core/subtitle` 此前**没有 androidTest 源集**,需要新建并配 `testInstrumentationRunner`。
参考 `core/database/build.gradle.kts` 的仪器测试配置。

测试内容:在真机上调用**每一个**解析入口(`SrtParser` / `VttParser` / `AssParser` / `parserFor` /
`SubtitleTags` 的标签剥离),喂真实形态的样本(含 `{\pos(…)}` 特效标签、BOM、零时长条目、
`\N` 换行)。断言输出正确 —— 而不只是"没抛异常"。

Run: `./gradlew --no-daemon :core:subtitle:connectedDebugAndroidTest`
Expected: **FAIL** —— `ExceptionInInitializerError` / `PatternSyntaxException`。把真实堆栈贴进报告。

- [ ] **Step 2: 第一层 —— 修根因**

转义 `}`。**并检查全项目所有 `Regex(` 的模式**是否还有同类问题(未转义的 `}`、`]`、
或依赖 JVM 特有语法的构造)。逐个在真机测试里覆盖。

把正则的编译从 `object` 的静态初始化挪到可捕获的位置(例如 `by lazy` 配合捕获,
或直接放进函数内),这样即便将来再有平台差异,也不会以 `ExceptionInInitializerError` 的形式炸掉。

- [ ] **Step 3: 第二层 —— 放宽字幕路径的捕获**

`SubtitleRepository.load` 的 `catch (e: Exception)` 改为能兜住 `Throwable`。
**但 `CancellationException` 必须继续重抛** —— 吞掉它会破坏结构化并发。

> ⚠️ 仅限字幕路径放宽。字幕是纯装饰功能,失败不该影响播放(v1 铁律)。
> **不得把这个做法推广到其他模块。** 在 KDoc 里写明这个取舍与边界。

- [ ] **Step 4: 第三层 —— ViewModel 兜底**

`PlayerViewModel` 中加载字幕的 `viewModelScope.launch` 加保护:任何 `Throwable`
都收敛成"无字幕"的 UI 状态,不得让协程异常终止进程。

- [ ] **Step 5: 跑真机测试确认转绿**

Run: `./gradlew --no-daemon :core:subtitle:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 6: 端到端验证"任何条目都不崩"**

在 `:app` 的端到端测试里增加:播放一个**带文本字幕轨**的条目,断言进程存活且位置前进。
用 `TestCredentials`;条目从服务器动态挑选(找 `MediaStreams` 里有 `IsTextSubtitleStream=true` 的),
**不要把具体条目 id 或剧名写死进代码或提交信息。**

- [ ] **Step 7: 全量回归 + Commit**

`fix(subtitle): escape the tag regex so it compiles on Android's regex engine`
`test(subtitle): run every parser entry point on a real device`

---

# 阶段 2:功能可用性

### Task 2:「我的媒体」可进入

**根因:** `JellyCastNavHost` 调 `HomeScreen(...)` 时**没传 `onLibraryClick`**,走了默认空函数;
路由表里也没有"进入某个库"这条路由。

- [ ] **Step 1: 核对接口**

```bash
jq -r '.paths["/Items"].get.parameters[]?.name' docs/jellyfin-openapi.json | grep -x parentId
```

- [ ] **Step 2: 写失败测试 → 加路由 `library/view/{libraryId}` → 接上回调 → 库内用 `parentId` 拉取**

走既有的 `MediaRepository` 缓存路径,bucket 键要含 `libraryId`。分页、离线态与既有列表一致。

- [ ] **Step 3: Compose UI 测试**:点击库卡片触发导航回调。
- [ ] **Step 4: Commit**:`feat(app): make library entries on home navigable`

### Task 3:有意义的播放队列(修"没有上一集")

**根因:** 导航层 4 处 `sessionViewModel.play(item, listOf(item))`,队列长度恒为 1,
`hasPrevious()` 恒 false。"下一集"能用只是因为 `AutoPlayNextController` 去 NextUp 补队列。

- [ ] **Step 1: 写失败测试**

断言:从「继续收听」播放第 2 项时,`hasPrevious()` 为 true 且 `previous()` 回到第 1 项;
从「下一集」分区播放时队列是该分区列表。

- [ ] **Step 2: 实现**

各分区把**同分区的完整列表**作为队列传出,当前项为起点。剧集详情页保持传整季(**不得破坏**)。

- [ ] **Step 3: 端到端**:通过 `MediaController` 发 `seekToPreviousMediaItem()`,断言切到上一项并继续播放。
- [ ] **Step 4: Commit**:`fix(app): pass real queues when playing from home sections`

### Task 4:字幕选轨与歌词定位

- [ ] **Step 1: 写失败测试**

- 同时存在外挂弹幕轨与普通字幕轨时,**选中的必须是普通字幕**
- `indexAt` 在**重叠**时间轴上返回"覆盖当前位置且最晚开始"的那条,而不是依赖不重叠假设
- 边界:位置落在空隙、早于第一条、晚于最后一条

弹幕的识别信号:`IsExternal` 为 true 且标题含 danmu/弹幕。**具体字段以 OpenAPI 为准,`jq` 核对。**
选不出合适的就不显示字幕,**不要退回弹幕**。

- [ ] **Step 2: 实现 → 确认转绿**
- [ ] **Step 3: 提高歌词跟随精度**

500 ms 轮询漏 85% 的行。改为按下一行边界调度或提高频率。**必须权衡功耗** ——
在报告中说明所选方案对唤醒次数的影响。

- [ ] **Step 4: Commit**:`fix(player): prefer real subtitles over danmaku and track overlapping lines`

---

# 阶段 3:诊断能力

### Task 5:应用内诊断日志导出

真机调试来回成本过高(无线调试配对多次失败)。**一次投入,长期受益。**

- [ ] **Step 1: 写失败测试**

- 崩溃与关键错误写入应用私有目录
- **绝不记录 token、密码;服务器地址须脱敏**(只留 scheme + 主机名首段)
- 有容量上限与轮转,**不得无限增长**
- 导出内容可被读取并分享

- [ ] **Step 2: 实现**

设置页加"导出诊断日志",走系统分享。默认开启,可关闭。
捕获未处理异常时也要落盘(注意:这不等于吞掉崩溃,只是先记录再交还给系统默认处理器)。

- [ ] **Step 3: 装机验证**:制造一次错误,导出,确认内容有用且**不含任何凭据**。
- [ ] **Step 4: Commit**:`feat(settings): add diagnostic log export`

---

# 阶段 4:待复现的三项

**先复现,再改。没有证据不许动手。**

### Task 6:进度条拖动、胶囊播放时间、缓冲卡顿

- [ ] **Step 1: 逐项复现并取证**

| 项 | 怀疑方向(**待验证,不是结论**) |
|---|---|
| 拖动不跟手 | seek 走重新请求转码的固有延迟 + 250 ms 去抖叠加;也可能是 `durationMs` 或手势本身 |
| 胶囊不显示时间 | MediaSession 未持续发布 `PlaybackState` 的位置/时长/速度 |
| 卡顿 | ExoPlayer 缓冲参数从未配置,用的是默认值;seek 后等服务端重起转码的空窗无缓冲遮掩 |

⚠️ **卡顿不是码率问题。** L1 已经只收音频(实测 132 kbps),比 web mobile 最低档视频流还省一个量级 ——
服务端根本不传视频字节。**不要用"降码率"来解决,方向是缓冲策略。**

- [ ] **Step 2: 按证据逐项修复**,每项先有能复现的失败测试。
- [ ] **Step 3: 胶囊表现只能真机验收** —— 模拟器没有 ColorOS。测试覆盖到"`PlaybackState` 内容正确"为止,
      胶囊实际表现列为用户手工验收项,**不得伪造结果**。
- [ ] **Step 4: Commit**(每项一个)

---

## Self-Review 记录

- **Spec 覆盖:** §2 崩溃三层 + 真机冒烟→Task 1;§3.1 我的媒体→Task 2;§3.2 队列→Task 3;
  §3.3 选轨 + §3.4 重叠定位 + §3.5 轮询→Task 4;§5 诊断日志→Task 5;§4 待证据三项→Task 6。
- **占位符:** Task 6 的具体修法依赖复现结果,这是**刻意的** —— 预先写死修法就是猜。已给出怀疑方向
  与"不许用降码率解决卡顿"的明确边界。
- **顺序依赖:** Task 1 最优先(崩溃)。Task 2/3/4 相互独立,可并发(各自模块不相交:
  Task 2 在 `:app` 导航 + `:feature:library`,Task 3 在 `:app` 导航 + `:core:player`,
  Task 4 在 `:core:subtitle` + `:feature:player`)—— ⚠️ **Task 2 与 Task 3 都改
  `JellyCastNavHost.kt`,不可并发**,须串行或合并。Task 5、6 独立。
- **并发编排:** 只有一台模拟器,**需要仪器测试的任务必须串行**。Task 1 与 Task 4 都要跑
  `:core:subtitle` 的真机测试,不可同时。
