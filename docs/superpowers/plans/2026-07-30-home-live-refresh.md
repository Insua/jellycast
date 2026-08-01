# 首页「继续收听 / 下一集」静默定时刷新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让首页的「继续收听 / 下一集」在首页变为可见时、以及可见期间每 60 秒,静默刷新到服务端当前状态。

**Architecture:** 在 `HomeViewModel` 上新增一个只覆盖两个 bucket 的静默刷新入口,用独立的 job、复用既有的 `MediaRepository.bucket(...)` 编排与 `applySection(...)` 写入;在 `HomeScreen` 用 `repeatOnLifecycle(STARTED)` 接上"变为可见立刻一次 + 之后每 60 秒一次"的驱动,离开可见状态时协程随之取消。

**Tech Stack:** Kotlin · Jetpack Compose · `androidx.lifecycle:lifecycle-runtime-compose`(新增依赖)· kotlinx.coroutines · JUnit5(JVM)· Compose UI Test(设备)

**设计文档:** `docs/superpowers/specs/2026-07-30-home-live-refresh-design.md`

## Global Constraints

- **只刷两个 bucket:** `CacheBuckets.HOME_RESUME` 与 `CacheBuckets.HOME_NEXT_UP`。不得触碰 `HOME_LIBRARIES`、`HOME_FAVORITES`、`CacheBuckets.recentlyAddedOf(...)`。
- **不新起取数逻辑:** 必须复用 `repository.bucket(...)` + `applySection(...)`,与 `load()` / `refresh()` 同路。
- **静默:** 不置位 `isRefreshing`、不清空已有内容、失败不弹窗不进 `error` 态,只沿用 `recomputeState` 既有的 `isOffline` 语义。
- **不得取消 `loadJob`。** 冷启动 / 重试 / 下拉刷新的优先级更高;它们在飞时静默刷新直接跳过。
- **不得改动 `HomeUiState` 既有字段的语义**,也不得改 `load()` / `refresh()` / `retry()` 的行为 —— 它们有 20+ 条既有用例。
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**。
- `:feature:home` 的 JVM 单测跑在 **JUnit 5** 上:断言参数顺序是 `(condition, message)`。
- **Commit 格式:gitmoji + Conventional Commits**,即 `<emoji> <type>(<scope>): <subject>`,正文说明"为什么"。本计划各 Task 的提交信息已给出,照抄。
- JVM 单测命令:`./gradlew :feature:home:testDebugUnitTest`(注意:`:feature:home:test` 只是聚合生命周期任务,不接受 `--tests`)。设备测试命令:`./gradlew :feature:home:connectedDebugAndroidTest`。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `gradle/libs.versions.toml` | 版本目录 | 新增 `androidx-lifecycle-runtime-compose` |
| `feature/home/build.gradle.kts` | 模块依赖 | 引入上面这个依赖 |
| `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeViewModel.kt` | 首页取数与状态 | 新增静默刷新入口 + 独立 job |
| `feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt` | 首页 JVM 单测 | 新增用例 |
| `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt` | 首页 UI | 接上生命周期驱动 |
| `feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeLiveRefreshTest.kt` | 生命周期接线的设备测试 | 新建 |

**任务顺序:** Task 1 → 2 → 3,**串行执行**(Task 2 依赖 Task 1 的方法,Task 3 依赖 Task 1 的方法与 Task 2 的依赖引入)。

---

### Task 1: `HomeViewModel` 的静默刷新入口

**Files:**
- Modify: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeViewModel.kt`
- Test: `feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: 既有的 `repository.bucket(bucket) { fetchSection(kind) }`、`applySection(kind, cached)`、`sectionItems`、`loadJob`
- Produces: `HomeViewModel.refreshLive()`,无参、无返回值、**非 suspend**(内部 `viewModelScope.launch`)。Task 2 与 Task 3 都调它。

- [ ] **Step 1: 写失败的测试**

在 `HomeViewModelTest.kt` 的最后一个 `@Test` 之后、类的右花括号之前追加。先看一眼该文件顶部既有的构造辅助(`FakeMediaRepository`、构造 ViewModel 的方式、`runTest` 的用法),**沿用同样的写法**,不要另造一套。

```kotlin
    // ---- 设计文档 §3/§4/§5:静默定时刷新 ----

    /**
     * 只刷「继续收听」和「下一集」。库列表和「最近添加」变化极慢,而后者是**按库各发一个请求**,
     * 每分钟重拉一遍是纯浪费流量 —— 这条用例把范围钉死。
     */
    @Test fun `静默刷新只请求继续收听和下一集两个 bucket`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.requestedBuckets.clear()

        viewModel.refreshLive()
        advanceUntilIdle()

        assertEquals(
            setOf(CacheBuckets.HOME_RESUME, CacheBuckets.HOME_NEXT_UP),
            repository.requestedBuckets.toSet(),
        )
    }

    /** 静默 = 不转下拉刷新的圈。 */
    @Test fun `静默刷新不置位 isRefreshing`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.refreshLive()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRefreshing, "静默刷新不该驱动下拉刷新指示器")
    }

    /**
     * 静默刷新发生在屏幕上已经有内容的时候。先清空再补回来会让首页闪一下白 ——
     * 这正是 v5 给下拉刷新定下的禁令,静默刷新更不能违反。
     */
    @Test fun `静默刷新期间已有内容不被清空`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        val before = viewModel.uiState.value.sections

        viewModel.refreshLive()
        // 还没 advance,请求都在飞 —— 此刻内容必须原样还在。
        assertEquals(before, viewModel.uiState.value.sections, "静默刷新不得清空已有内容")

        advanceUntilIdle()
    }

    /** 失败不进错误态 —— 屏幕上还有旧内容可看,不该被一整页错误盖住。 */
    @Test fun `静默刷新失败不产生错误态`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.failBucket(CacheBuckets.HOME_RESUME)

        viewModel.refreshLive()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error, "静默刷新失败不该把首页打成错误态")
    }

    /**
     * 设计文档 §5:用户主动发起的刷新优先级更高。同一个 bucket 被两条流程同时写,
     * 只会产生无意义的重复请求。
     */
    @Test fun `有下拉刷新在飞时静默刷新跳过且不取消它`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.requestedBuckets.clear()

        viewModel.refresh()          // 用户下拉,请求在飞
        viewModel.refreshLive()      // 同一时刻的定时器
        advanceUntilIdle()

        // 下拉刷新自己要刷的 bucket 一个都不能少 —— 说明它没被取消。
        assertTrue(
            repository.requestedBuckets.containsAll(
                listOf(CacheBuckets.HOME_RESUME, CacheBuckets.HOME_NEXT_UP, CacheBuckets.HOME_LIBRARIES)
            ),
            "静默刷新不得取消正在进行的下拉刷新",
        )
        // 两个 live bucket 各自只被请求一次 —— 说明静默刷新确实跳过了,没有叠加。
        assertEquals(
            1,
            repository.requestedBuckets.count { it == CacheBuckets.HOME_RESUME },
            "静默刷新应当在下拉刷新在飞时跳过,不重复请求",
        )
    }

    /** 这条是需求本身:在别处听过之后回到首页,位置要变成新的。 */
    @Test fun `静默刷新把服务端的新位置反映到界面`() = runTest {
        val repository = FakeMediaRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        repository.setResumePositionMs(itemId = "ep1", positionMs = 1_800_000L)
        viewModel.refreshLive()
        advanceUntilIdle()

        val resume = viewModel.uiState.value.sections.first { it.kind == HomeSectionKind.RESUME }
        assertEquals(1_800_000L, resume.items.first { it.id == "ep1" }.resumePositionMs)
    }
```

**这些测试依赖 `FakeMediaRepository` 具备三项能力:记录被请求过的 bucket(`requestedBuckets`)、让指定 bucket 失败(`failBucket`)、改写某个条目的续播位置(`setResumePositionMs`)。** 先读 `feature/home/src/test/java/dev/insua/jellycast/feature/home/FakeMediaRepository.kt`,缺哪项就补哪项;已有等价能力就直接用既有名字,不要重复造。同样,`createViewModel(repository)` 若与既有测试里的构造方式不同名,改成与既有一致的写法。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeViewModelTest*'`

Expected: 编译失败,`Unresolved reference: refreshLive`。

- [ ] **Step 3: 写最小实现**

在 `HomeViewModel` 里,`loadJob` 声明之后加上独立的 job 字段:

```kotlin
    /**
     * 静默刷新用自己的 job,和 [loadJob](冷启动 / 重试 / 下拉刷新共用)分开(设计文档 §5)。
     *
     * 合用一个 job 会让定时器把用户主动发起的刷新取消掉 —— 那是优先级完全反了。
     */
    private var liveRefreshJob: Job? = null
```

新增方法(放在 `refresh()` 之后):

```kotlin
    /**
     * 静默刷新「继续收听 / 下一集」(设计文档 §3)。
     *
     * ## 它解决的问题
     *
     * [HomeViewModel] 是 `@HiltViewModel`,**只要首页还在返回栈上就一直活着** —— [init] 里那次
     * [load] 之后,从播放页返回首页、从后台切回前台都不会再取一次数,屏幕上是几十分钟前的位置。
     * 用户在别处听过(或本机被系统杀掉)之后重开 App,点「继续收听」会从旧位置开始播。
     *
     * ## 为什么只有这两个分区
     *
     * 它们是唯一会因为"在别处听了一会儿"而改变的分区,也是点播主入口。库列表几乎不变;
     * 「最近添加」是**按库各发一个请求**,每分钟重拉一遍纯属浪费,出门走公网时尤其。
     *
     * ## 「静默」的边界
     *
     * 不置位 [HomeUiState.isRefreshing](那是下拉刷新手势的指示器)、不清空已有内容、失败不进
     * [HomeUiState.error]。失败只沿用 [recomputeState] 既有的 [HomeUiState.isOffline] 语义 ——
     * 屏幕上确实是旧数据,横幅是诚实的。
     *
     * ## 让路
     *
     * 冷启动 / 重试 / 下拉刷新在飞时直接跳过:同一个 bucket 被两条流程同时写只会产生无意义的
     * 重复请求,而用户主动发起的那条优先级更高。**绝不取消 [loadJob]。**
     * 冷启动时 [init] 的 [load] 通常仍在进行中,所以首页刚可见时的这一次会被这条规则跳过,
     * 不会重复请求。
     */
    fun refreshLive() {
        if (loadJob?.isActive == true || liveRefreshJob?.isActive == true) return
        liveRefreshJob = viewModelScope.launch {
            LIVE_REFRESH_SECTIONS
                .map { kind ->
                    launch {
                        repository.bucket(kind.bucket) { fetchSection(kind) }
                            .collect { cached -> applySection(kind, cached) }
                    }
                }
                .joinAll()
        }
    }
```

在文件顶部、`private const val FAVORITES_LIMIT` 附近加上常量:

```kotlin
/**
 * 参与静默刷新的分区(设计文档 §2)。刻意**不含** [HomeSectionKind.LIBRARIES] ——
 * 库列表几乎不变,而它还会带出"最近添加"的按库 N 个请求。
 */
private val LIVE_REFRESH_SECTIONS = listOf(HomeSectionKind.RESUME, HomeSectionKind.NEXT_UP)
```

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :feature:home:testDebugUnitTest`

Expected: PASS,`:feature:home` 全部用例绿(既有 20+ 条 + 新增 6 条)。

- [ ] **Step 5: 变异验证(必做,不可跳过)**

本仓库在上一轮出现过 4 条"看着对但测不出任何东西"的空测试。逐条确认新用例真的有判别力:

1. 把 `LIVE_REFRESH_SECTIONS` 改成 `HomeSectionKind.entries.toList()`,跑测试 → `静默刷新只请求继续收听和下一集两个 bucket` 必须变红。恢复。
2. 把 `refreshLive()` 开头的跳过判定整行删掉,跑测试 → `有下拉刷新在飞时静默刷新跳过且不取消它` 必须变红。恢复。
3. 在 `refreshLive()` 里加一行 `_uiState.update { it.copy(isRefreshing = true) }`,跑测试 → `静默刷新不置位 isRefreshing` 必须变红。恢复。

每次恢复后用 `git status --porcelain` 确认工作区只剩你自己的改动。把三次的实际失败输出记进报告。任何一条不变红,说明该用例是空的,**必须重写到能变红为止**。

- [ ] **Step 6: Commit**

```bash
git add feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeViewModel.kt \
        feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt \
        feature/home/src/test/java/dev/insua/jellycast/feature/home/FakeMediaRepository.kt
git commit -m "✨ feat(home): 继续收听与下一集支持静默刷新

首页 ViewModel 只要还在返回栈上就一直活着,init 之后不再取数,
从播放页返回或切回前台看到的都是几十分钟前的位置。

新增只覆盖这两个 bucket 的静默刷新入口:独立 job、复用既有仓储编排,
不转指示器、不清空内容、失败不进错误态;用户主动刷新在飞时让路。"
```

---

### Task 2: 引入 `lifecycle-runtime-compose` 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `feature/home/build.gradle.kts`

**Interfaces:**
- Consumes: 版本目录里既有的 `lifecycle = "2.10.0"` version ref
- Produces: 可在 `:feature:home` 里 `import androidx.lifecycle.compose.LocalLifecycleOwner` 与 `androidx.lifecycle.compose.repeatOnLifecycle` 之一(具体用哪个由 Task 3 决定);Task 3 依赖本任务。

- [ ] **Step 1: 加版本目录条目**

在 `gradle/libs.versions.toml` 的 `[libraries]` 段,紧挨着 `androidx-lifecycle-viewmodel-compose` 那一行之后加入:

```toml
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
```

**复用既有的 `lifecycle` version ref,不要新起一个版本号。**

- [ ] **Step 2: 在模块里引用**

在 `feature/home/build.gradle.kts` 的 `dependencies` 块里,`implementation(libs.androidx.lifecycle.runtime)` 那一行之后加入:

```kotlin
    implementation(libs.androidx.lifecycle.runtime.compose)
```

- [ ] **Step 3: 确认能解析**

Run: `./gradlew :feature:home:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。依赖解析不了会在这里直接报错。

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml feature/home/build.gradle.kts
git commit -m "🔧 chore(home): 引入 lifecycle-runtime-compose

首页要按「可见时才刷新」驱动静默刷新,需要 Compose 侧的生命周期感知能力。
复用版本目录里既有的 lifecycle version ref,不新起版本号。"
```

---

### Task 3: `HomeScreen` 接上生命周期驱动

**Files:**
- Modify: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt`
- Create: `feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeLiveRefreshTest.kt`

**Interfaces:**
- Consumes: `HomeViewModel.refreshLive()`(Task 1)、`libs.androidx.lifecycle.runtime.compose`(Task 2)
- Produces: 无(终点)

- [ ] **Step 1: 写失败的测试**

创建 `feature/home/src/androidTest/java/dev/insua/jellycast/feature/home/HomeLiveRefreshTest.kt`。

先确认 `:feature:home` 是否已有 `androidTest` 源集与 Compose UI 测试依赖(既有 `HomeScreenTest.kt` 在 `src/test/` 还是 `src/androidTest/`?)。**如果既有的 Compose 测试跑在 `src/test/`(Robolectric),就把这个测试也放在 `src/test/`,沿用同样的 runner 与依赖,不要为它新引入一套 androidTest 基础设施。** 下面的代码按"与既有 Compose 测试同源集、同写法"编写,实际 import 与 rule 以既有文件为准。

测试要断言的行为(用 `ComposeTestRule` 驱动生命周期):

```kotlin
package dev.insua.jellycast.feature.home

// import 与既有的 Compose 测试文件保持一致

/**
 * 设计文档 §3.1:首页变为可见时必须立刻静默刷新一次 —— 这是「打开就是最新位置」的关键。
 * §3.2:离开可见状态后定时器必须停,不在后台耗电耗流量。
 */
class HomeLiveRefreshTest {

    // 用一个只记录调用次数的假驱动,把「什么时候该刷」这件事和真实取数解耦。
    @Test
    fun `首页变为可见时立刻触发一次静默刷新`() {
        var refreshCount = 0
        // 渲染挂载了生命周期驱动的组件,把 refreshLive 换成计数器
        // 断言:组件进入 STARTED 之后 refreshCount == 1
    }

    @Test
    fun `离开可见状态后不再触发静默刷新`() {
        var refreshCount = 0
        // 让组件离开组合 / 生命周期降到 STARTED 以下,推进时间超过一个刷新间隔
        // 断言:refreshCount 没有再增加
    }
}
```

**⚠️ 上面是要断言的行为,不是可直接粘贴的实现。** 写这个测试之前先读 `HomeScreenTest.kt`,照它的方式拿 `ComposeTestRule`、控制生命周期、推进时间。为了让"每 60 秒一次"可测,**把刷新间隔做成组件参数并给默认值**(见 Step 3),测试传一个很短的间隔。

- [ ] **Step 2: 跑测试,确认失败**

Run: `./gradlew :feature:home:testDebugUnitTest --tests '*HomeLiveRefreshTest*'`(若放在 `androidTest`,则用 `./gradlew :feature:home:connectedDebugAndroidTest`)

Expected: 编译失败或断言失败 —— 生命周期驱动还不存在。

- [ ] **Step 3: 写最小实现**

在 `HomeScreen.kt` 里,`HomeScreen` 组件中 `val uiState by viewModel.uiState.collectAsState()` 之后,加入生命周期驱动:

```kotlin
    /**
     * 设计文档 §3:首页可见期间才刷新。
     *
     * - `repeatOnLifecycle(STARTED)` 让这段协程**在首页变为可见时启动、不可见时取消** ——
     *   离开首页或退到后台连协程都不存在,而不是"存在但不发请求"。后台定时打接口是耗电耗流量
     *   的典型反模式。
     * - 进入循环先刷一次,再按间隔重复:第一次覆盖「切回前台 / 从播放页返回首页」,
     *   之后的覆盖「停在首页不动,而在别的设备上继续听」。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, liveRefreshIntervalMs) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refreshLive()
                delay(liveRefreshIntervalMs)
            }
        }
    }
```

给 `HomeScreen` 增加带默认值的参数(**加在参数列表末尾,不要改动既有参数的顺序或名字** —— 导航层有既有调用点):

```kotlin
    /**
     * 静默刷新间隔(设计文档 §3.2)。60 秒的取舍:这两个接口各自最多 20 条,单次开销很小;
     * 60 秒意味着"在别处听完一集"最迟一分钟反映到首页。更短在公网场景下是白耗流量。
     * 做成参数只为了让 UI 测试能传一个很短的值,生产调用点一律用默认值。
     */
    liveRefreshIntervalMs: Long = LIVE_REFRESH_INTERVAL_MS,
```

在文件顶部加常量:

```kotlin
/** 见 [HomeScreen] 的 `liveRefreshIntervalMs`。 */
internal const val LIVE_REFRESH_INTERVAL_MS = 60_000L
```

需要的 import:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
```

(部分 import 可能已存在;`LocalLifecycleOwner` 在新版 Compose 里位于 `androidx.lifecycle.compose`,若编译报错则改用 `androidx.compose.ui.platform.LocalLifecycleOwner`,以实际能编过的为准。)

- [ ] **Step 4: 跑测试,确认通过**

Run: `./gradlew :feature:home:testDebugUnitTest`(如放在 androidTest 则同时跑 `./gradlew :feature:home:connectedDebugAndroidTest`)

Expected: PASS,`:feature:home` 全部用例绿。

- [ ] **Step 5: 变异验证(必做)**

把 `repeatOnLifecycle(Lifecycle.State.STARTED)` 换成直接 `while (true) { ... }`(去掉生命周期约束),跑测试 → `离开可见状态后不再触发静默刷新` 必须变红。恢复并确认 `git status --porcelain` 只剩你自己的改动。把实际失败输出记进报告。

- [ ] **Step 6: 全量回归**

```bash
./gradlew test
```

Expected: 全绿。

- [ ] **Step 7: Commit**

```bash
git add feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt \
        feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeLiveRefreshTest.kt
git commit -m "✨ feat(home): 首页可见时静默刷新继续收听与下一集

进入 STARTED 立刻刷一次(覆盖切回前台、从播放页返回),之后每 60 秒一次;
离开可见状态时协程随 repeatOnLifecycle 一起取消,不在后台耗电耗流量。

间隔做成带默认值的参数,只为让 UI 测试能传一个很短的值。"
```

> 若测试文件最终落在 `src/androidTest/`,把上面 `git add` 的路径换成实际路径。

---

## 计划自查

**1. Spec 覆盖**

| 设计文档要求 | 落点 |
|---|---|
| §2 只刷两个分区 | Task 1 的 `LIVE_REFRESH_SECTIONS` + 对应用例 |
| §3.1 变为可见时刷一次 | Task 3 的 `repeatOnLifecycle` 循环体首次执行 |
| §3.2 可见期间每 60 秒 | Task 3 的 `delay(liveRefreshIntervalMs)` |
| §3.2 不可见时停 | Task 3 的 `repeatOnLifecycle(STARTED)` + 变异验证 |
| §4 静默(不转圈 / 不清空 / 不进错误态) | Task 1 的三条用例 |
| §5 让路且不取消 loadJob | Task 1 的跳过判定 + 对应用例 |
| §6 复用既有编排 | Task 1 实现里直接调 `repository.bucket` + `applySection` |
| §7.1 JVM 单测六条 | Task 1 Step 1 |
| §7.2 Compose 生命周期测试 | Task 3 Step 1 |
| §8 验收标准 | Task 3 Step 6 |

无遗漏。

**2. 占位符扫描**

Task 3 Step 1 的测试是**行为描述而非可粘贴代码**,并已明确标注原因(必须先读既有 Compose 测试才能确定源集与 runner)。其余步骤均为完整代码。

**3. 类型一致性**

- `refreshLive()` —— Task 1 定义,Task 3 调用,同名同签名 ✓
- `LIVE_REFRESH_SECTIONS` / `LIVE_REFRESH_INTERVAL_MS` —— 各自只在定义处与使用处出现,命名一致 ✓
- `libs.androidx.lifecycle.runtime.compose` —— Task 2 定义的 TOML key `androidx-lifecycle-runtime-compose` 在 Gradle 里正是这个访问器 ✓
- `CacheBuckets.HOME_RESUME` / `HOME_NEXT_UP` / `HOME_LIBRARIES` —— 与 `:core:network` 既有常量名一致 ✓
