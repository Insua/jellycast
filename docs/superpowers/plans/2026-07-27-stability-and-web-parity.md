# 播放稳定性、浏览层对齐与系统集成 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`(推荐)逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 让 JellyCast 在真机上真正可用 —— 稳定播放不闪退,浏览层贴近 Jellyfin Web mobile,补上图标与系统媒体通知。

**Architecture:** 先建立能在模拟器上复现真实播放链路的自动化验证(Task 1),再用它驱动稳定性根因修复(Task 2)。之后是媒体元数据/通知(Task 3)、图标(Task 4)、首页改版(Task 5)。

**Tech Stack:** Kotlin · Compose · Media3 · Hilt(含 hilt-android-testing)· JUnit4 仪器测试 · MockK

## Global Constraints

- 设计文档:`docs/superpowers/specs/2026-07-27-stability-and-web-parity-design.md` —— 需求以它为准。
- 🔴 **凭据铁律:真实服务器地址、用户名、密码不得出现在代码、测试、文档、提交信息、日志或任何报告里。** 一律从**已 gitignore 的本地文件**读取。每个 Task 收尾必须跑一次泄漏复查(见下)。
- 🔴 **稳定性优先。** 用户明确说"能真正稳定播放 > 一切"。UI 与图标排在后面。
- 🔴 **主题保持浅色。** 参照 Web mobile 的是**结构与信息层级**,不是配色。用户明确否决深色。
- 🔴 **播放页形态不变** —— 仍是大封面 + 歌词式字幕。本次只改浏览层。
- **禁止凭记忆写 Jellyfin API。** 每个参数用 `jq` 核对 `docs/jellyfin-openapi.json`。**Jellyfin 对错误大小写的查询参数静默忽略**,v1 期间已查出 6 处。
- **永远不渲染视频**:不引入 `media3-ui`、不建 `PlayerView`、不绑 `Surface`。
- **不允许全局关闭 TLS 校验。**
- **ticks 只在网络映射层换算一次**(`ms = ticks / 10_000`),其余层一律毫秒。
- 不得削弱既有保证:首个成功探测胜出 / 落选探测真取消 / `CancellationException` 传播 / L3 恒可播 / 字幕失败不影响播放 / seek 走重新 resolve 而非 `player.seekTo`。
- 版本一律走 `gradle/libs.versions.toml` 别名,**不写死**。
- **AGP 9 内置 Kotlin:绝不 apply `org.jetbrains.kotlin.android`;没有 `kotlinOptions { }`。**
- 源集:`src/test/` = JUnit5 + MockK;`src/androidTest/` = JUnit4 + AndroidJUnit4。**不可混用。**
- TDD:先写测试、跑、**确认失败**、再实现。不许跳过"确认失败"。
- 跑 gradle 带 `--no-daemon`。模拟器 `emulator-5554` 可用。
- 每个 Task 结束 commit,Conventional Commits。

### 每个 Task 收尾必做的泄漏复查

```bash
git ls-files | xargs grep -lniE '<真实主机名片段>|<用户名>|<密码>' 2>/dev/null && echo "🚨 泄漏" || echo "✅ 干净"
git log --all -p | grep -ciE '<用户名>:<密码>' # 应为 0
```
(执行时把尖括号替换成实际值**在本地心算核对**,**不要把替换后的命令写进任何报告**。)

---

## 文件结构总览

```
testing.properties                      新增:真实服务器与账号(gitignore,不提交)
testing.properties.example              新增:模板,只含占位符(提交)

app/src/androidTest/.../e2e/
    TestCredentials.kt                  从 BuildConfig 读凭据;缺失则跳过测试
    PlaybackE2eTest.kt                  四个环节的端到端测试

core/player/.../PlaybackMetadata.kt     新增:MediaItem → Media3 MediaMetadata 映射
app/src/main/res/mipmap-anydpi-v26/     新增:自适应图标
app/src/main/res/drawable/              新增:图标前景/背景矢量

feature/home/.../HomeScreen.kt          改:我的媒体 / 16:9 继续收听 / 分组最近添加
feature/home/.../HomeViewModel.kt       改:加载 UserViews
core/network/.../JellyfinApi.kt         改:补 userViews()
```

---

### Task 1:端到端验证脚手架(本计划的前提)

**没有它,后面所有"修好了"都是空话。** v1 有 281 个单测全绿却在真机四个环节全崩,就是因为从没端到端跑过。

**Files:**
- Create: `testing.properties.example`
- Modify: `.gitignore`(加 `testing.properties`)
- Modify: `app/build.gradle.kts`(把凭据读进 `BuildConfig`,仅 androidTest 用)
- Create: `app/src/androidTest/java/dev/insua/jellycast/e2e/TestCredentials.kt`
- Create: `app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt`

**Interfaces:**
- Produces: `object TestCredentials { val isConfigured: Boolean; val baseUrl: String; val username: String; val password: String }`

- [ ] **Step 1: 写模板与 gitignore**

`testing.properties.example`(**提交**,只含占位符):
```properties
# 复制为 testing.properties 并填入真实值。testing.properties 已被 .gitignore 排除。
# 缺失本文件时端到端测试会自动跳过,不会导致构建失败。
e2e.baseUrl=http://your-server:8096
e2e.username=your-username
e2e.password=your-password
```

`.gitignore` 追加:
```
# 端到端测试用的真实服务器与账号,绝不提交
testing.properties
```

- [ ] **Step 2: 把凭据读进 BuildConfig**

`app/build.gradle.kts` 的 `android { defaultConfig { } }` 内:
```kotlin
        val e2e = Properties().apply {
            val f = rootProject.file("testing.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun e2eProp(key: String) = "\"${e2e.getProperty(key).orEmpty()}\""
        buildConfigField("String", "E2E_BASE_URL", e2eProp("e2e.baseUrl"))
        buildConfigField("String", "E2E_USERNAME", e2eProp("e2e.username"))
        buildConfigField("String", "E2E_PASSWORD", e2eProp("e2e.password"))
```
并在 `buildFeatures` 里开 `buildConfig = true`。

> 🔴 值为空字符串时测试跳过。**不要给这些字段写任何默认真实值。**

- [ ] **Step 3: 写 TestCredentials**

```kotlin
package dev.insua.jellycast.e2e

import dev.insua.jellycast.BuildConfig
import org.junit.Assume.assumeTrue

/** 端到端测试的凭据来源。缺失时用 JUnit Assume 跳过,而不是失败——CI 上不该因为没配服务器就红。 */
object TestCredentials {
    val baseUrl: String get() = BuildConfig.E2E_BASE_URL
    val username: String get() = BuildConfig.E2E_USERNAME
    val password: String get() = BuildConfig.E2E_PASSWORD
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** 放在每个端到端测试的开头。 */
    fun assumeConfigured() = assumeTrue("testing.properties 未配置,跳过端到端测试", isConfigured)
}
```

> 🔴 **绝不 log 这三个值中的任何一个**,断言失败信息里也不许出现。

- [ ] **Step 4: 写第一个端到端测试(先失败)**

`PlaybackE2eTest.kt`。用 `HiltAndroidRule` + 预置 `ServerStore`,跳过登录 UI,然后走真实播放链路:

```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaybackE2eTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    // 注入 ServerStore / JellyfinApi / AudioPlaybackEngine 等真实实现

    @Before fun setUp() {
        TestCredentials.assumeConfigured()
        // 1. 用 TestCredentials 登录拿 token
        // 2. serverStore.upsert(...) + setActive(...)
    }

    @Test fun 开始播放后位置确实前进() { /* Step 5 展开 */ }
}
```

**关键:断言必须是行为性的。** 「位置在 10 秒内前进了 ≥5 秒」,不是「调用没抛异常」。

- [ ] **Step 5: 实现四个场景**

| 测试 | 断言 |
|---|---|
| `开始播放后位置确实前进` | 拿第一集 → play → 轮询 `absolutePositionMs`,10s 内增长 ≥5000ms |
| `连续播放三十秒不中断` | 播 30s,期间每 5s 采样,位置单调递增且无 `Error` 状态 |
| `seek 后位置跳到目标并继续前进` | seek 到 600000ms → 位置落在 [590000, 630000] 且继续增长 |
| `切后台后播放继续` | 用 `InstrumentationRegistry` 把 Activity 置后台 → 位置仍前进 |

轮询用条件等待(轮询 + 超时),**不要用固定 `Thread.sleep` 当断言**。

- [ ] **Step 6: 跑,记录真实结果**

Run: `./gradlew --no-daemon :app:connectedDebugAndroidTest --tests '*PlaybackE2eTest*'`

**预期:部分或全部失败** —— 这正是用户报告的 bug。**把真实失败输出和堆栈完整记进报告**,这是 Task 2 的输入。
若全部意外通过,如实说明,并分析模拟器与真机的差异(前台服务限制、ColorOS 后台策略、网络时序)。

- [ ] **Step 7: 泄漏复查 + Commit**

确认 `git status` 里没有 `testing.properties`,`git ls-files` 里没有凭据。

```bash
git add .gitignore testing.properties.example app/build.gradle.kts app/src/androidTest
git commit -m "test: add end-to-end playback harness against a real server"
```

---

### Task 2:稳定性根因修复

**依赖 Task 1 的失败输出。** 没有复现就不许改代码。

**Files:** 视复现结果而定。首要嫌疑见下。

- [ ] **Step 1: 逐个复现并取证**

对 Task 1 中每个失败的测试,拿到**完整堆栈**。必要时在关键边界加临时日志(前台服务启动、resolve 开始/结束、prepare、STATE_ENDED),跑一次收集证据,**再**分析。

- [ ] **Step 2: 首要嫌疑(是假设,不是结论)**

| 嫌疑 | 机理 | 预期症状 |
|---|---|---|
| `AppSessionViewModel.kt:141` 先 `startForegroundService` 再 `engine.play()`,而 `play()` 内含 `PlaybackInfo` + L1 探测两次网络往返 | 系统要求 5 秒内 `startForeground`,慢网下超时抛 `ForegroundServiceDidNotStartInTimeException` | 点击播放瞬间闪退 |
| `HttpStreamProbe` 每次 `resolve()` 发完整 GET;seek = 重新 resolve ⇒ **每次 seek 在 NAS 上起一个新转码**并丢弃 | J4125 上并发转码堆积 | seek/快进卡死、播放中断 |
| `TrustAwareHttpClientModule` 在 Hilt provider 里 `runBlocking` 读 DataStore | 主线程阻塞 | ANR |
| 自动连播 `resolve()` 被调两次 | 多余请求 + 状态竞态 | 切集异常 |

- [ ] **Step 3: 逐个修复**

**每个缺陷:先有能复现它的失败测试 → 再改 → 由该测试从红转绿证明。** 一次改一个,不要打包。

修 `startForegroundService` 时序的思路(供参考,以复现结果为准):把「解析播放源」放到 Service 内部、
在 Service 起来并 `startForeground` 之后再做;或先用占位 metadata 立刻 `startForeground`,解析完再更新。
**选一种并在报告中说明理由。**

修探测的思路:按 `mediaSourceId` 缓存 L1 判定结果,seek 时不重复探测。

- [ ] **Step 4: 全量回归**

Run: `./gradlew --no-daemon test` → 281 个既有单测全绿
Run: `./gradlew --no-daemon :app:connectedDebugAndroidTest` → Task 1 的四个场景全绿

- [ ] **Step 5: 泄漏复查 + Commit**(每个修复一个 commit)

---

### Task 3:媒体元数据与通知(锁屏 / 流体云的前提)

**已确认:代码里从未给 `MediaItem` 设过 `MediaMetadata`** —— 唯一提及处是一段解释"为什么不用"的注释。所以锁屏和流体云现在只会显示空白。

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackMetadata.kt`
- Modify: 构造 Media3 `MediaItem` 的地方(`AudioPlaybackEngine` / `ExoPlayerControl`)
- Test: `core/player/src/test/java/dev/insua/jellycast/player/PlaybackMetadataTest.kt`

- [ ] **Step 1: 写失败的测试**

```kotlin
class PlaybackMetadataTest {
    @Test fun `剧集带标题副标题与封面`() {
        val item = MediaItem(id="ep1", kind=MediaKind.EPISODE, name="第一集",
            seriesName="银魂", seasonNumber=1, episodeNumber=6, imageTag="tag1")
        val md = item.toMediaMetadata(posterUrl = "http://x/img")
        assertEquals("第一集", md.title)
        assertEquals("银魂 · S01E06", md.artist)      // 复用 displaySubtitle
        assertEquals("http://x/img".toUri(), md.artworkUri)
    }
    @Test fun `无封面时不设 artworkUri 而不是设成空串`() { /* posterUrl = null → artworkUri 为 null */ }
    @Test fun `电影没有剧名时副标题不出现多余分隔符`() { /* MOVIE kind */ }
}
```

- [ ] **Step 2: 跑,确认失败 → 实现 → 确认通过**

`MediaMetadata.Builder()` 设 `setTitle` / `setArtist` / `setArtworkUri` / `setIsPlayable(true)` /
`setMediaType(MEDIA_TYPE_MUSIC)`。副标题复用 `:core:model` 的 `displaySubtitle`,**不要重写格式化逻辑**。

- [ ] **Step 3: 接到播放链路并加仪器测试**

在 `:app/src/androidTest` 加一个测试:开始播放后,`MediaController.mediaMetadata.title` 非空且等于集名。

- [ ] **Step 4: 确认通知形态**

Media3 的 `MediaSessionService` 默认用 `DefaultMediaNotificationProvider` 生成 Media 样式通知。
确认通知渠道、图标、session token 正确。**流体云只能真机验收,列为用户手工项,不得伪造。**

- [ ] **Step 5: Commit**:`feat(player): publish media metadata for lockscreen and system media surfaces`

---

### Task 4:应用图标

**已确认当前没有任何图标**(`app/src/main/res/` 下只有 `values`),用的是 Android 默认图标。

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`(或 `values/ic_launcher_background.xml` 定义纯色)
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml`(`android:icon` / `android:roundIcon`)

- [ ] **Step 1: 设计并实现矢量图标**

方向:呼应"把剧当播客听" —— 音频/播放的意象,**不要用视频/胶片元素**(与产品定位冲突)。
用 `VectorDrawable`,不用位图,这样各密度都清晰。

自适应图标要求:前景内容必须落在中心 **66dp / 108dp** 的安全区内,否则会被圆形遮罩裁掉。

- [ ] **Step 2: 装机验证**

```bash
./gradlew --no-daemon installDebug
adb shell monkey -p dev.insua.jellycast -c android.intent.category.LAUNCHER 1
```
截图确认桌面图标显示正常(圆形遮罩下不裁切、不糊)。**把截图结论如实写进报告。**

- [ ] **Step 3: Commit**:`feat(app): add adaptive launcher icon`

---

### Task 5:首页对齐 Jellyfin Web mobile(浅色)

**Files:**
- Modify: `core/network/.../JellyfinApi.kt`(补 `userViews()`)
- Modify: `feature/home/.../HomeViewModel.kt` / `HomeScreen.kt`
- Modify: `core/designsystem/.../PosterCard.kt`(或新增 16:9 卡片)
- Test: 对应 ViewModel 单测 + Compose UI 测试

- [ ] **Step 1: 核对接口(强制)**

```bash
jq -r '.paths["/UserViews"].get.parameters[]?.name' docs/jellyfin-openapi.json
jq -r '.components.schemas.UserItemDataDto.properties|keys[]' docs/jellyfin-openapi.json | grep -i unplayed
```
预期:`userId` 等参数;`UnplayedItemCount` 存在(角标用)。**大小写以输出为准。**

- [ ] **Step 2: 补 API 与 ViewModel(先写失败测试)**

```kotlin
@GET("UserViews")
suspend fun userViews(@Query("userId") userId: String): ItemsResponseDto
```
`HomeViewModel` 新增「我的媒体」分区,与既有三个分区**并发加载、互不阻塞**(沿用现有模式),
某个失败不影响其余。写测试锁定这两点。

- [ ] **Step 3: 首页布局改版**

| 分区 | 卡片形态 |
|---|---|
| 我的媒体 | 横滑,**16:9** 库封面,底部叠库名 |
| 继续收听 | 横滑,**16:9** 缩略图 + 底部已听进度条 + 中央播放三角 |
| 最近添加 | **按库分组**,标题右侧 `>`,竖版海报 + 未看数角标 |

**保持浅色主题。** 顶部加应用标识 + 搜索入口 + 账号入口。
「我的最爱」标签本次**不实现内容** —— 要么不显示,要么明确标为暂未开放,**不做点进去是空的假入口**。

- [ ] **Step 4: Compose UI 测试**

至少覆盖:我的媒体为空时不显示该分区;继续收听的进度条按 `resumePositionMs / runTimeMs` 渲染;
点击库卡片触发回调。定位用 `testTag`,不要靠文案。

- [ ] **Step 5: 全量回归 + 装机截图**

Run: `./gradlew --no-daemon test :app:assembleDebug` → 全绿
装机截图对照 spec §5 的表格逐项确认。

- [ ] **Step 6: Commit**:`feat(home): restructure home to match Jellyfin web mobile layout`

---

## Self-Review 记录

- **Spec 覆盖:** §3 验证脚手架→Task 1;§4 稳定性→Task 2;§5 浏览层→Task 5;§6.1 图标→Task 4;§6.2 通知/流体云→Task 3。§3.3 凭据铁律→Global Constraints + 每个 Task 的泄漏复查步骤。
- **占位符:** Task 2 的具体修复内容依赖 Task 1 的复现结果,这是**刻意的** —— 先复现再定位是 systematic-debugging 的要求,预先写死修法就是猜。已给出首要嫌疑表与判定纪律,实现者不会无从下手。
- **类型一致性:** `TestCredentials`(Task 1)被 Task 2/3 的仪器测试复用;`toMediaMetadata`(Task 3)签名在 Task 3 定义;`userViews()`(Task 5)返回既有 `ItemsResponseDto`。
- **顺序依赖:** Task 2 强依赖 Task 1;Task 3/4/5 相互独立,但都应在 Task 2 之后,以免在不稳定的地基上验收。
