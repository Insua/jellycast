# 状态栏配色、首页顶部留白、播放进度持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉状态栏图标与浅色主题撞色、把首页顶部 chrome 减半、并让冷启动后迷你播放条直接停在上次听到的位置。

**Architecture:** 三件事互相独立。状态栏在 `:app` 的主题资源与 `MainActivity` 里显式声明外观;首页顶部把 `TopAppBar` 与 `TabRow` 合并成一行;播放进度在 `:core:datastore` 新增一条窄持久化记录,由 `:core:player` 的既有 10 秒心跳写入,由 `:app` 的 `AppSessionViewModel` 在冷启动时读出并装填迷你条。

**Tech Stack:** Kotlin · Jetpack Compose · DataStore + kotlinx.serialization · JUnit 5(JVM)· Compose UI Test(设备)

**设计文档:** `docs/superpowers/specs/2026-08-01-ui-polish-and-resume-design.md`

## Global Constraints

- **恢复流程绝不发网络请求、绝不启动前台服务、绝不自动播放。** 断网 / 未登录 / 服务器不可达时同样能恢复出迷你条。
- **不持久化整个 `MediaItem`。** 持久化记录是独立的窄结构,只含恢复播放与渲染迷你条所需字段。
- **不引入 Room 迁移**,记录存在 DataStore 里。
- **不引入 `enableEdgeToEdge()`** —— v5 已经调好 inset 体系,为改图标颜色去动它风险大于收益。
- 不得改动 `MiniPlayerBar` 的既有参数名与顺序;不得改动 `AppSessionViewModel.play(item, queue)` 的签名。
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**。
- `:feature:home` / `:core:datastore` / `:app` 的 JVM 单测跑在 **JUnit 5**(断言参数顺序 `(condition, message)`);`androidTest` 跑在 **JUnit 4**(顺序相反)。
- **Commit 格式:gitmoji + Conventional Commits**,`<emoji> <type>(<scope>): <subject>`,正文说明为什么。各 Task 给出的提交信息照抄。
- JVM 单测:`./gradlew :<module>:testDebugUnitTest`(`:<module>:test` 只是聚合任务,不接受 `--tests`)。设备测试:`./gradlew :<module>:connectedDebugAndroidTest`(`--tests` 无效,用 `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`)。
- **每条新测试都必须做变异验证**:把对应的实现改回缺陷状态,确认该条测试变红,记录真实输出,再恢复并确认 `git status --porcelain` 只剩预期文件。本仓库最近几轮反复出现"看着对但测不出任何东西"的空测试。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `app/src/main/res/values/themes.xml` | 启动主题 | 改父主题与 `windowBackground` |
| `app/src/main/res/values/colors.xml` | 启动背景色 | 新建 |
| `app/src/main/java/dev/insua/jellycast/MainActivity.kt` | 入口 Activity | 显式设置状态栏外观 |
| `app/src/androidTest/java/dev/insua/jellycast/SystemBarsAppearanceTest.kt` | 状态栏外观的设备证据 | 新建 |
| `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt` | 首页 UI | 合并顶栏与标签行 |
| `feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeTopChromeHeightTest.kt` | 顶部高度测量 | 新建 |
| `core/datastore/src/main/java/dev/insua/jellycast/datastore/LastPlayedStore.kt` | 上次播放记录 | 新建 |
| `core/datastore/src/test/java/dev/insua/jellycast/datastore/LastPlayedStoreTest.kt` | 记录读写单测 | 新建 |
| `core/player/src/main/java/dev/insua/jellycast/player/PlaybackService.kt` | 播放宿主 | 心跳时写记录 |
| `app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt` | 会话/迷你条 | 冷启动恢复 |
| `app/src/test/java/dev/insua/jellycast/navigation/AppSessionViewModelTest.kt` | 恢复行为单测 | 新增用例 |

**任务顺序:** Task 1、2 互相独立且与 3–5 无关,可按任意顺序;Task 3 → 4 → 5 必须串行(4 依赖 3 的接口,5 依赖 3 的接口)。**全部串行执行**,避免同分支并发提交打架。

---

### Task 1: 状态栏图标改为深色,消除启动黑闪

**Files:**
- Create: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/java/dev/insua/jellycast/MainActivity.kt`
- Test: `app/src/androidTest/java/dev/insua/jellycast/SystemBarsAppearanceTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: 无(后续 Task 不依赖它)

- [ ] **Step 1: 写失败的测试**

创建 `app/src/androidTest/java/dev/insua/jellycast/SystemBarsAppearanceTest.kt`:

```kotlin
package dev.insua.jellycast

import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设计文档 §1:这个应用只有浅色主题,状态栏图标必须是**深色**的。
 *
 * 缺陷现场:`Theme.JellyCast` 继承 `android:Theme.Material.NoActionBar`(深色主题),
 * 系统据此判定"应用背景是深的"而画**浅色**图标 —— 于是白色的电池/信号/时间落在浅色界面上,
 * 几乎看不清。而代码里从来没有任何地方声明过状态栏外观。
 *
 * ⚠️ 这是**平台行为,JVM 单测结构上测不出**(v4 立下的铁律)。必须在真实 Activity 上取
 * `WindowInsetsController` 的实际状态。
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsAppearanceTest {

    @Test
    fun 状态栏图标是深色的() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertTrue(
                    "状态栏图标必须是深色(isAppearanceLightStatusBars=true) —— " +
                        "这个应用只有浅色主题,浅色图标会和界面撞色看不清。",
                    controller.isAppearanceLightStatusBars,
                )
            }
        }
    }

    @Test
    fun 导航栏图标是深色的() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertTrue(
                    "导航栏图标同理必须是深色。",
                    controller.isAppearanceLightNavigationBars,
                )
            }
        }
    }
}
```

先确认 `:app` 的 `androidTest` 依赖里有 `androidx.test:core`(`ActivityScenario` 来自它)和 `androidx.core:core-ktx`(`WindowCompat`)。缺哪个就在 `app/build.gradle.kts` 里按版本目录里既有的 alias 补上,**不要写死版本号**。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.SystemBarsAppearanceTest`

Expected: 两条都 FAIL —— 断言消息为「状态栏图标必须是深色…」。

> 需要已连接的设备或模拟器,用 `/home/insua/Android/Sdk/platform-tools/adb devices` 确认。

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/res/values/colors.xml`:

```xml
<resources>
    <!--
      启动窗口背景。必须和 Compose 浅色主题的 surface 一致 ——
      原先是纯黑,而应用是浅色的,冷启动时 Compose 内容渲染出来之前会黑闪一下。
    -->
    <color name="launch_background">#FFFBFE</color>
</resources>
```

把 `app/src/main/res/values/themes.xml` 改成:

```xml
<resources>
    <!--
      Compose-only app: no AppCompat / Material Components XML theme dependency.
      这个主题只为满足 manifest 的要求(Compose 内容设置之前的启动/闪屏背景)而存在。

      ⚠️ 父主题必须是 **Light**。系统会按父主题判定"应用背景是深是浅",据此决定系统栏
      图标的颜色。继承深色主题会让系统画浅色(白色)图标,落在本应用的浅色界面上几乎看不清。
      MainActivity 里还会再显式声明一次(见那里的注释)——两处一致,不依赖任何一处单独生效。
    -->
    <style name="Theme.JellyCast" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/launch_background</item>
    </style>
</resources>
```

在 `MainActivity.onCreate` 里、`setContent` **之前**加上显式声明:

```kotlin
        // 设计文档 §1:这个应用只有浅色主题(深色主题已被明确否决),所以系统栏图标固定为深色。
        //
        // 为什么不能只靠 XML 主题:主题只是给系统的一个"猜测依据",而真正决定图标颜色的是
        // WindowInsetsController 上的这两个标志。显式声明一次,行为就不再依赖父主题选得对不对,
        // 也不受后续主题改动影响。
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
```

需要的 import:

```kotlin
import androidx.core.view.WindowCompat
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.SystemBarsAppearanceTest`

Expected: PASS,2/2。

- [ ] **Step 5: 变异验证(必做)**

把 `MainActivity` 里那段 `WindowCompat...apply { ... }` 整个删掉,重跑测试。

Expected: 至少 `状态栏图标是深色的` 变红。记录真实失败输出,恢复,确认 `git status --porcelain` 只剩预期文件。

> 若删掉之后测试**仍然绿**,说明父主题改成 Light 之后系统已经自己给出了正确的值 —— 那么这条测试是在测主题而不是在测那段代码。此时把变异改成"把父主题改回 `android:Theme.Material.NoActionBar`",确认变红。**两种变异至少要有一种能让它变红**,并在报告里写清是哪一种。

- [ ] **Step 6: 截图为证**

在模拟器上装好 debug 包,截一张首页图,确认状态栏的电池/信号/时间是深色、清晰可辨。把截图路径写进报告。

```bash
./gradlew :app:installDebug
/home/insua/Android/Sdk/platform-tools/adb shell screencap -p /sdcard/statusbar.png
/home/insua/Android/Sdk/platform-tools/adb pull /sdcard/statusbar.png /tmp/statusbar.png
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/values/themes.xml \
        app/src/main/java/dev/insua/jellycast/MainActivity.kt \
        app/src/androidTest/java/dev/insua/jellycast/SystemBarsAppearanceTest.kt
git commit -m "🎨 fix(app): 状态栏图标改为深色,消除启动黑闪

主题继承的是 android:Theme.Material.NoActionBar(深色),系统据此画白色
系统栏图标,而应用渲染的是浅色界面 —— 判定依据和实际显示对不上,电池、
信号、时间几乎看不清。代码里从未声明过系统栏外观。

父主题改为 Light,并在 MainActivity 显式声明 isAppearanceLight*Bars,
两处一致、不依赖任何一处单独生效。windowBackground 从纯黑改为与浅色
主题一致的颜色,消除冷启动黑闪。"
```

---

### Task 2: 首页顶部合并为一行

**Files:**
- Modify: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt`
- Test: `feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeTopChromeHeightTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: 无

- [ ] **Step 1: 先量出改动前的基线**

在动代码之前,写一个**测量**测试并跑一次,记录当前值。创建
`feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeTopChromeHeightTest.kt`。

先读 `HomeScreenInsetTest.kt` —— 它是 v5 靠像素测量发现"顶部多出恰好一个状态栏高度"的那个测试,**照它的方式**拿 rule、渲染 `HomeScreenContent`、取节点位置。

要测量的是:**第一个分区标题(「继续收听」)的顶部 y 坐标**减去内容根的顶部 y 坐标 = 顶部 chrome 的总高度。

跑一次,把实测基线值记进报告。设计文档估算约 144dp,以实测为准。

- [ ] **Step 2: 写失败的断言**

在同一个测试里加上目标断言:顶部 chrome 高度必须**不超过基线的 60%**(去掉 64dp 的 `TopAppBar` 加 8dp padding,理论上降到约一半;留出余量避免把测试写成对某个像素值的死绑定)。

```kotlin
    @Test
    fun 顶部chrome高度不超过基线的六成() {
        // BASELINE_PX 填 Step 1 实测出来的值,并在注释里写明它是怎么来的
        val actual = measureTopChromeHeightPx()
        assertTrue(
            "首页顶部 chrome 高度 ${actual}px,超过基线 ${BASELINE_PX}px 的 60%。" +
                "设计文档 §2:去掉只装了一个静态应用名的 TopAppBar,把图标并进标签行。",
            actual <= BASELINE_PX * 0.6,
        )
    }
```

同时保留两条行为断言,防止"省了空间但功能没了":搜索按钮与账户按钮仍然存在且可点(用既有的 `HomeScreenTestTags.SEARCH_BUTTON` / `ACCOUNT_BUTTON`)。

- [ ] **Step 3: 跑测试,确认失败**

Run: `./gradlew :feature:home:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.feature.home.HomeTopChromeHeightTest`

Expected: 高度那条 FAIL,两条行为断言 PASS。

- [ ] **Step 4: 写最小实现**

在 `HomeScreen.kt` 里:

1. **删掉 `HomeTopBar` 这个 composable**,以及 `HomeScreenContent` 里对它的调用。
2. 把 `Column(modifier = Modifier.padding(vertical = 8.dp))` 改成 `Column()` —— 顶部那 8dp 一并去掉(底部间距由内容自己负责)。
3. 把 `TabRow` 换成一个 `Row`:左边是 `TabRow`(用 `Modifier.weight(1f)`),右边是原先那两个 `IconButton`。

```kotlin
        // 设计文档 §2:顶部只留一行。
        //
        // 原先是「TopAppBar(静态应用名 + 两个图标)」压在「TabRow」上面,两行加起来 112dp,
        // 而其中 64dp 那一行只装了一个用户早就知道的应用名 —— 他清楚自己打开的是哪个 App。
        // 把图标并进标签行,顶部 chrome 减半。
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(
                selectedTabIndex = uiState.tab.ordinal,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HomeScreenTestTags.TAB_ROW),
            ) {
                Tab(
                    selected = uiState.tab == HomeTab.FEED,
                    onClick = { onSelectTab(HomeTab.FEED) },
                    text = { Text("首页") },
                )
                Tab(
                    selected = uiState.tab == HomeTab.FAVORITES,
                    onClick = { onSelectTab(HomeTab.FAVORITES) },
                    text = { Text("我的最爱") },
                )
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.testTag(HomeScreenTestTags.SEARCH_BUTTON),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            IconButton(
                onClick = onAccountClick,
                modifier = Modifier.testTag(HomeScreenTestTags.ACCOUNT_BUTTON),
            ) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "账户")
            }
        }
```

清理掉因此不再使用的 import(`TopAppBar` 等);`HomeScreenTestTags.TOP_BAR` 若已无使用者,一并删除并同步改掉引用它的既有测试。

- [ ] **Step 5: 跑测试,确认通过**

Run: `./gradlew :feature:home:connectedDebugAndroidTest`

Expected: PASS,`:feature:home` 全部设备测试绿(含既有的 `HomeScreenInsetTest`、`HomePullToRefreshTest`、`HomeScreenQueueTest`、`HomeLiveRefreshTest`)。

再跑 `./gradlew :feature:home:testDebugUnitTest`,确认 JVM 侧也全绿。

- [ ] **Step 6: 变异验证(必做)**

把删掉的 `HomeTopBar` 加回去(在 `Row` 之上再放一个 `TopAppBar`),重跑测试 → `顶部chrome高度不超过基线的六成` 必须变红。记录真实输出,恢复,确认 `git status --porcelain` 只剩预期文件。

- [ ] **Step 7: 截图为证**

装包截图,附在报告里,确认视觉上确实紧凑了且两个图标还在。

- [ ] **Step 8: Commit**

```bash
git add feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt \
        feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeTopChromeHeightTest.kt
git commit -m "💄 feat(home): 顶部合并为一行,chrome 减半

第一行内容之上原先压着四层:状态栏 inset + 8dp padding + 64dp TopAppBar
+ 48dp TabRow,合计约 144dp。其中 TopAppBar 整整占一行,里面只有一个静态
应用名和两个图标 —— 应用名对用户没有信息量。

去掉标题栏,两个图标并进标签行。新增测量测试钉住高度,防止回退。"
```

---

### Task 3: 上次播放记录的持久化存储

**Files:**
- Create: `core/datastore/src/main/java/dev/insua/jellycast/datastore/LastPlayedStore.kt`
- Create: `core/datastore/src/test/java/dev/insua/jellycast/datastore/LastPlayedStoreTest.kt`

**Interfaces:**
- Consumes: `:core:datastore` 既有的 DataStore 实例与 kotlinx-serialization(两者都已就位)
- Produces:
  - `@Serializable data class LastPlayed(val itemId: String, val positionMs: Long, val title: String, val subtitle: String, val imageTag: String?, val runTimeMs: Long?, val updatedAt: Long)`
  - `class LastPlayedStore`,方法 `val lastPlayed: Flow<LastPlayed?>`、`suspend fun save(record: LastPlayed)`、`suspend fun clear()`
  - Task 4 调 `save`,Task 5 读 `lastPlayed` 并在退出登录时调 `clear`

- [ ] **Step 1: 写失败的测试**

先读 `core/datastore/src/test/` 下既有的测试,看它们怎么造 DataStore(临时文件?fake?),**沿用同样的方式**。然后创建 `LastPlayedStoreTest.kt`,覆盖:

- 没存过任何东西时 `lastPlayed` 发射 `null`
- `save` 之后能读回**完全相同**的记录(逐字段断言,不要只断言非空)
- 再 `save` 一次会**覆盖**而不是追加
- `clear` 之后回到 `null`
- 存储里是**损坏的内容**(不合法 JSON)时发射 `null` 而不抛异常 —— 这条很重要:恢复流程在冷启动路径上,一个异常就是启动即崩

每条都要能对着未实现的代码失败。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:datastore:testDebugUnitTest`

Expected: 编译失败,`Unresolved reference: LastPlayedStore`。

- [ ] **Step 3: 写最小实现**

创建 `LastPlayedStore.kt`。要点:

```kotlin
/**
 * 「这台设备上次在听什么」—— 冷启动时把迷你播放条直接装填出来的数据来源(设计文档 §3)。
 *
 * ## 为什么不直接存 `MediaItem`
 *
 * `MediaItem` 是领域模型,字段会随功能演进增删(它已经在后续任务里被追加过 `unplayedItemCount`、
 * `isFavorite`、`seriesId` 等)。把它直接序列化进存储,等于让每一次模型改动都变成一次存储格式
 * 变更。这里只存两件事所需的最少字段:**恢复播放**(条目 id + 位置)与**渲染迷你条**
 * (标题、副标题、封面 tag、总时长)。
 *
 * ## 为什么损坏的内容要静默降级成 null
 *
 * 读这条记录发生在**冷启动路径**上。存储被写坏(进程在写入中途被杀、格式变更)时如果抛异常,
 * 用户看到的就是"打开就崩" —— 而这条记录只是一个便利功能,丢了最多是迷你条空着。
 */
```

- 用一个 `stringPreferencesKey("last_played")` 存 JSON
- 读取时 `runCatching { Json.decodeFromString<LastPlayed>(raw) }.getOrNull()`
- `Json` 实例配 `ignoreUnknownKeys = true`(将来加字段时旧记录仍可读)

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:datastore:testDebugUnitTest`

Expected: PASS,`:core:datastore` 全部用例绿。

- [ ] **Step 5: 变异验证(必做)**

- 把 `runCatching { ... }.getOrNull()` 改成直接 `decodeFromString` → 「损坏内容发射 null」那条必须变红
- 把 `save` 改成不覆盖(存在就跳过)→ 「再 save 一次会覆盖」那条必须变红

记录真实输出,逐次恢复,确认 `git status --porcelain` 只剩预期文件。

- [ ] **Step 6: Commit**

```bash
git add core/datastore/src/main/java/dev/insua/jellycast/datastore/LastPlayedStore.kt \
        core/datastore/src/test/java/dev/insua/jellycast/datastore/LastPlayedStoreTest.kt
git commit -m "✨ feat(datastore): 新增上次播放记录的持久化存储

冷启动后迷你播放条是空的,必须先进首页找到继续收听再点进去才能播。
存一条窄记录(条目 id + 位置 + 渲染迷你条所需的最少字段),让下次打开
直接装填好。

刻意不序列化整个 MediaItem:那是领域模型,字段会随功能增删,直接存等于
让每次模型改动都变成一次存储格式变更。读取失败静默降级为 null —— 这条
路径在冷启动上,抛异常就是打开即崩。"
```

---

### Task 4: 播放心跳时写入记录

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackService.kt`
- Modify: `core/player/build.gradle.kts`(若尚未依赖 `:core:datastore`,按既有 alias 补上)
- Test: `core/player/src/test/java/dev/insua/jellycast/player/` 下新增或扩充

**Interfaces:**
- Consumes: Task 3 的 `LastPlayedStore.save(record)` 与 `LastPlayed(...)`
- Produces: 无新公开接口

- [ ] **Step 1: 决定写入点并写失败的测试**

`PlaybackService` 已经有一条 10 秒心跳协程(`observeProgressReporting` 里那个 `while (isActive) { delay(...); if (exoPlayer.isPlaying) progressCoordinator.onTick() }`),它同时拿得到 `playQueue.current`(`MediaItem`)与 `playbackEngine.absolutePositionMs`(绝对位置)。

**但 `PlaybackService` 在 JVM 单测里造不出来**(v6 的经验)。所以:把「从当前条目 + 位置构造出一条 `LastPlayed`」这段决策抽成一个**纯函数**放在 `:core:player` 里,对它单测;`PlaybackService` 只负责在心跳里调用它并 `save`。

纯函数要覆盖的用例:

- 剧集条目 → 标题是集名,副标题含剧名与季集编号
- 电影条目 → 标题是片名,副标题不含季集编号
- `runTimeMs` 为 null 时记录里也是 null(不伪造)
- 位置为 0 时**仍然**产出记录(用户刚点开还没走,也该被记住)

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :core:player:testDebugUnitTest`

Expected: 编译失败或断言失败。

- [ ] **Step 3: 写最小实现**

实现纯函数,并在 `PlaybackService` 的心跳里接上:心跳那一拍已经确认 `exoPlayer.isPlaying`,在 `progressCoordinator.onTick()` 之后追加一次 `lastPlayedStore.save(...)`。

同时在 `onDestroy` 的收尾路径上写一次最终位置 —— 那里已经取好了 `playbackEngine.absolutePositionMs`。

**写入失败绝不打断播放**(项目铁律):整个写入包在 `runCatching` 之外还要显式重抛 `CancellationException`,与 `ProgressReporter` 里既有的写法一致。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :core:player:testDebugUnitTest`

Expected: PASS,`:core:player` 全部用例绿。

- [ ] **Step 5: 变异验证(必做)**

把纯函数里"副标题带季集编号"那段去掉 → 对应用例必须变红。记录输出,恢复。

- [ ] **Step 6: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/PlaybackService.kt \
        core/player/build.gradle.kts \
        core/player/src/test/java/dev/insua/jellycast/player/
git commit -m "✨ feat(player): 播放心跳时写入上次播放记录

复用既有的 10 秒进度心跳 —— 那一拍已经同时拿得到当前条目和绝对位置。
onDestroy 收尾时再写一次最终位置。

「从条目和位置构造记录」抽成纯函数单独单测:PlaybackService 在 JVM 单测里
造不出来,把决策留在它里面等于没有测试。写入失败不打断播放。"
```

---

### Task 5: 冷启动恢复迷你播放条

**Files:**
- Modify: `app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt`
- Test: `app/src/test/java/dev/insua/jellycast/navigation/AppSessionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `LastPlayedStore.lastPlayed` / `clear()`
- Produces: 无(终点)

- [ ] **Step 1: 写失败的测试**

先读既有的 `AppSessionViewModelTest.kt`,沿用它构造 ViewModel 的方式(它已经有 `PlayQueue`、`session` 等的替身)。新增用例:

- **有记录时,冷启动后 `miniPlayer` 非空**,标题/副标题/进度与记录一致,且 `isPlaying == false`
- **恢复过程中不发任何网络请求** —— 断言 session/api 替身**一次都没被调用**(沿用离线缓存那条用例"断言没有创建任何 api client"的做法)
- **恢复过程中不启动前台服务** —— 断言启动服务的替身没被调用
- **恢复不会自动播放** —— 断言引擎替身的 `play` 没被调用
- **没有记录时** `miniPlayer` 保持 null
- **恢复出来的条目,点播放时起点是记录里的位置** —— 断言引擎 `play` 收到的 `startPositionMs` 等于记录值

最后一条是这个功能的核心价值,必须有。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :app:testDebugUnitTest`

Expected: 新增用例 FAIL。

- [ ] **Step 3: 写最小实现**

在 `AppSessionViewModel` 里:

- 注入 `LastPlayedStore`
- `init` 里读一次记录,若有则:
  - 用记录字段构造一个 `MediaItem`,**把 `resumePositionMs` 设成记录里的位置**
  - `playQueue.setQueue(listOf(item), 0)`
  - 把 `_miniPlayer` 置成 `isPlaying = false`、`progress = miniPlayerProgress(位置, 总时长)`、`hasNext = false`

> **关键复用:** 既有的 `play(item, queue)` 已经用 `item.resumePositionMs` 当播放起点。把持久化位置塞进这个字段,续播就**不需要改 `play` 的签名或实现** —— 点迷你条播放走的是和从首页点播完全相同的一条路。

- `onMiniPlayerPlayPause` 需要处理「恢复出来但还没真正开始播」这个新状态:此时没有 MediaController 会话可切换,应当改为发起一次 `play(restoredItem, listOf(restoredItem))`。判据用引擎是否已有当前条目,不要靠 `isPlaying`(暂停中的真实会话也是 false)。

- 退出登录 / 切换服务器的既有路径上调 `lastPlayedStore.clear()`。

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS,`:app` 全部 JVM 用例绿。

- [ ] **Step 5: 变异验证(必做)**

- 把 `resumePositionMs` 改成硬编码 `0L` → 「起点是记录里的位置」那条必须变红
- 在 `init` 的恢复分支里加一行 `audioPlaybackEngine.play(...)` → 「恢复不会自动播放」那条必须变红
- 在恢复分支里加一次 `session.userId()` 调用 → 「不发网络请求」那条必须变红

逐条记录真实输出并恢复。

- [ ] **Step 6: 真机/模拟器验证**

装包,播一集,等至少一次心跳(10 秒以上),从最近任务划掉 App,重开:

- 迷你条显示的是那一集,进度停在离开时的位置
- 通知栏**没有**通知
- 点播放能从该位置续上

把观察结果写进报告。**关掉设备网络再重开一次**,确认迷你条照样恢复得出来。

- [ ] **Step 7: 全量回归**

```bash
./gradlew test
./gradlew connectedDebugAndroidTest
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/insua/jellycast/navigation/AppSessionViewModel.kt \
        app/src/test/java/dev/insua/jellycast/navigation/AppSessionViewModelTest.kt
git commit -m "✨ feat(app): 冷启动恢复迷你播放条,点开就能接着听

冷启动后迷你条是空的,要先进首页找到继续收听再点进去才能播。现在读本地
记录把播放器装填好:显示上次那一集和停下的位置,点播放直接续上。

三条硬约束:不自动播放、不启动前台服务、不发网络请求 —— 断网冷启动同样
能恢复。复用 play() 既有的 resumePositionMs 起点语义,不改它的签名。"
```

---

## 计划自查

**1. Spec 覆盖**

| 设计文档要求 | 落点 |
|---|---|
| §1 状态栏图标深色 | Task 1 |
| §1 消除启动黑闪 | Task 1(`windowBackground` + `colors.xml`) |
| §2 顶部合并为一行 | Task 2 |
| §3 存什么 / 不存整个 MediaItem | Task 3 |
| §3 何时写 | Task 4 |
| §3 何时读 + 三条硬约束 | Task 5 |
| §3 续播位置用本机的 | Task 5(`resumePositionMs`) |
| §3 记录失效 | Task 5(退出登录时 `clear`) |
| §5 测试策略 | 各 Task 的测试与变异验证步骤 |

无遗漏。

**2. 占位符扫描**

Task 2 Step 1、Task 3 Step 1、Task 4 Step 1、Task 5 Step 1 给的是**要覆盖的行为清单而非可粘贴代码**,并各自写明了原因(必须先读既有测试才能确定构造方式)。其余改代码的步骤均给出完整代码。

**3. 类型一致性**

- `LastPlayed` / `LastPlayedStore.save` / `.lastPlayed` / `.clear()` —— Task 3 定义,Task 4 与 Task 5 按同名同签名使用 ✓
- `MediaItem.resumePositionMs` —— 与 `:core:model` 既有字段名一致,且与 `AppSessionViewModel.play` 既有用法一致 ✓
- `HomeScreenTestTags.SEARCH_BUTTON` / `ACCOUNT_BUTTON` / `TAB_ROW` —— 与既有常量一致 ✓
- `miniPlayerProgress(positionMs, durationMs)` —— 与既有内部函数签名一致 ✓
