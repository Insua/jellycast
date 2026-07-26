# 媒体库分页与搜索 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`(推荐)或 `superpowers:executing-plans` 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 给媒体库加上分页加载与顶部搜索,让数百部剧、8744 集的库能用。

**Architecture:** 一个不做 IO 的纯函数分页状态机 `PageState<T>` 放 `:core:model`;`JellyfinApi.items()` 补 `startIndex` 与 `searchTerm`;`LibraryViewModel` 用 `debounce + flatMapLatest` 驱动搜索并复用同一套分页;`LibraryScreen` 加搜索栏与触底加载。

**Tech Stack:** Kotlin · Compose · Retrofit · kotlinx.coroutines Flow · JUnit5 + MockK + Turbine

## Global Constraints

- 设计文档:`docs/superpowers/specs/2026-07-26-library-paging-and-search-design.md` —— 需求以它为准。
- **禁止凭记忆写 Jellyfin API。** 每个参数在使用前必须核对 `docs/jellyfin-openapi.json`。**Jellyfin 对错误大小写的查询参数静默忽略、不报错**,v1 期间已查出 6 处此类错误。
- **核心逻辑必须可离线单测。** 所有测试用 MockK 假 api,不连真实服务器。
- **不引入 Paging 3**(理由见设计文档 §3.1)。
- 版本一律走 `gradle/libs.versions.toml` 别名,**不在模块里写死版本号**。
- **AGP 9 内置 Kotlin 支持:绝不可 apply `org.jetbrains.kotlin.android`;没有 `kotlinOptions { }` 这个 DSL。**
- JUnit5 三件套缺一不可:`libs.junit.jupiter` + `testRuntimeOnly(libs.junit.platform.launcher)` + `useJUnitPlatform()`。AGP library 模块用 `tasks.withType<Test>().configureEach { }`(`tasks.test` 访问器不可用);纯 JVM 模块用 `tasks.test { }`。**验收必须 grep `tests="N"` 证明 N>0**,否则单测可能静默执行 0 个。
- 源集:`src/test/` 用 JUnit5 + MockK,`src/androidTest/` 用 JUnit4 + AndroidJUnit4,**不可混用**。
- 单元测试必须先写、先失败、再实现(TDD),**不许跳过"确认失败"**。
- 每个 Task 结束必须 commit,用 Conventional Commits。
- 跑 gradle 带 `--no-daemon`。模拟器 `emulator-5554` 可用。
- **不得触碰** `:core:player` / `:core:subtitle` / `:feature:player` / `:feature:server`。

---

## 文件结构总览

```
core/model/src/main/java/dev/insua/jellycast/model/PageState.kt        新增:纯函数分页状态机
core/model/src/test/java/dev/insua/jellycast/model/PageStateTest.kt    新增

core/network/.../network/JellyfinApi.kt                                修改:items() 补 startIndex / searchTerm

feature/home/.../home/HomeViewModel.kt                                 修改:最近添加补 limit
feature/home/.../home/HomeViewModelTest.kt                             修改:断言带 limit

feature/library/.../library/LibraryViewModel.kt                        修改:分页 + 搜索
feature/library/.../library/LibraryViewModelTest.kt                    修改:新增分页与搜索测试
feature/library/.../library/LibraryScreen.kt                           修改:搜索栏 + 触底加载 + 状态行
```

---

### Task 1:`PageState<T>` —— 纯函数分页状态机

**Files:**
- Create: `core/model/src/main/java/dev/insua/jellycast/model/PageState.kt`
- Test: `core/model/src/test/java/dev/insua/jellycast/model/PageStateTest.kt`

**Interfaces:**
- Consumes: 无(纯 Kotlin,零依赖)
- Produces:
  ```kotlin
  data class PageState<T>(
      val items: List<T> = emptyList(),
      val totalCount: Int? = null,
      val isLoading: Boolean = false,
      val error: String? = null,
  ) {
      val loadedCount: Int
      val endReached: Boolean
      fun startLoading(): PageState<T>
      fun onPageLoaded(newItems: List<T>, startIndex: Int, total: Int): PageState<T>
      fun onError(message: String): PageState<T>
      fun reset(): PageState<T>
  }
  ```

- [ ] **Step 1: 写失败的测试**

`core/model/src/test/java/dev/insua/jellycast/model/PageStateTest.kt`

```kotlin
package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageStateTest {

    @Test fun `初始状态为空且未到底`() {
        val s = PageState<String>()
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.loadedCount)
        assertNull(s.totalCount)
        assertFalse(s.isLoading)
        // totalCount 未知时不能判定到底,否则会漏加载
        assertFalse(s.endReached)
    }

    @Test fun `startLoading 置位且清除上次错误`() {
        val s = PageState<String>().onError("boom").startLoading()
        assertTrue(s.isLoading)
        assertNull(s.error)
    }

    @Test fun `第一页加载后记录条目与总数`() {
        val s = PageState<String>().startLoading()
            .onPageLoaded(listOf("a", "b"), startIndex = 0, total = 5)
        assertEquals(listOf("a", "b"), s.items)
        assertEquals(5, s.totalCount)
        assertFalse(s.isLoading)
        assertFalse(s.endReached)
    }

    @Test fun `第二页追加在第一页之后且顺序不乱`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .onPageLoaded(listOf("c", "d"), 2, 4)
        assertEquals(listOf("a", "b", "c", "d"), s.items)
        assertTrue(s.endReached)
    }

    @Test fun `startIndex 与已加载数不符的响应被丢弃`() {
        // 场景:重复请求或乱序响应。startIndex=0 的第二次响应不得把首页再追加一遍
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .onPageLoaded(listOf("a", "b"), 0, 4)
        assertEquals(listOf("a", "b"), s.items)
    }

    @Test fun `超前的 startIndex 也被丢弃`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a"), 0, 10)
            .onPageLoaded(listOf("x"), 5, 10)   // 中间缺了一段,不能接
        assertEquals(listOf("a"), s.items)
    }

    @Test fun `空结果立即视为到底`() {
        val s = PageState<String>().onPageLoaded(emptyList(), 0, 0)
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.totalCount)
        assertTrue(s.endReached)
    }

    @Test fun `onError 保留已加载内容`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 10)
            .startLoading()
            .onError("网络错误")
        assertEquals(listOf("a", "b"), s.items)   // 绝不清空用户已看到的内容
        assertEquals("网络错误", s.error)
        assertFalse(s.isLoading)
    }

    @Test fun `错误后可继续加载下一页`() {
        val s = PageState<String>()
            .onPageLoaded(listOf("a", "b"), 0, 4)
            .onError("网络错误")
            .startLoading()
            .onPageLoaded(listOf("c", "d"), 2, 4)
        assertEquals(listOf("a", "b", "c", "d"), s.items)
        assertNull(s.error)
    }

    @Test fun `reset 回到初始态`() {
        val s = PageState<String>().onPageLoaded(listOf("a"), 0, 9).onError("x").reset()
        assertEquals(PageState<String>(), s)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --no-daemon :core:model:test --tests '*PageStateTest*'`
Expected: FAIL — `Unresolved reference: PageState`

- [ ] **Step 3: 实现**

`core/model/src/main/java/dev/insua/jellycast/model/PageState.kt`

```kotlin
package dev.insua.jellycast.model

/**
 * 分页列表的不可变状态机。**不做任何 IO** —— 调用方负责取数据,把结果喂进来。
 *
 * 之所以不用 Paging 3:项目铁律要求核心逻辑可离线单测,而 `PagingData` 是不透明流,
 * 难以断言"第二页追加在第一页之后"这类行为。详见
 * `docs/superpowers/specs/2026-07-26-library-paging-and-search-design.md` §3.1。
 */
data class PageState<T>(
    val items: List<T> = emptyList(),
    /** 服务端 TotalRecordCount;null 表示尚未发起过成功请求 */
    val totalCount: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val loadedCount: Int get() = items.size

    /** 只有确知总数且已加载够了才算到底。totalCount 未知时必须返回 false,否则会漏加载。 */
    val endReached: Boolean get() = totalCount != null && items.size >= totalCount

    fun startLoading(): PageState<T> = copy(isLoading = true, error = null)

    /**
     * 接收一页数据。
     *
     * [startIndex] 用于幂等保护:只有恰好接在当前已加载条目之后的响应才被采纳。
     * 重复请求(startIndex 偏小)会导致内容重复,乱序/跳跃响应(startIndex 偏大)
     * 会在列表中留下空洞 —— 两者都直接丢弃。
     */
    fun onPageLoaded(newItems: List<T>, startIndex: Int, total: Int): PageState<T> {
        if (startIndex != items.size) return copy(isLoading = false, error = null)
        return copy(
            items = items + newItems,
            totalCount = total,
            isLoading = false,
            error = null,
        )
    }

    /** 失败时**保留**已加载内容 —— 绝不清空用户已经看到的东西。 */
    fun onError(message: String): PageState<T> = copy(isLoading = false, error = message)

    fun reset(): PageState<T> = PageState()
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew --no-daemon :core:model:test --tests '*PageStateTest*'`
Expected: PASS(10 个测试)

- [ ] **Step 5: 确认测试真的执行了**

Run: `grep -o 'tests="[0-9]*"' core/model/build/test-results/test/TEST-*PageStateTest.xml`
Expected: `tests="10"`

- [ ] **Step 6: Commit**

```bash
git add core/model
git commit -m "feat(model): add pure paging state machine"
```

---

### Task 2:`JellyfinApi` 补分页与搜索参数,首页补上限

**Files:**
- Modify: `core/network/src/main/java/dev/insua/jellycast/network/JellyfinApi.kt`
- Modify: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeViewModel.kt`
- Test: `feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  suspend fun items(
      userId: String, types: String,
      recursive: Boolean = true, sortBy: String = "SortName",
      startIndex: Int? = null, limit: Int? = null,
      parentId: String? = null, searchTerm: String? = null,
  ): ItemsResponseDto
  ```
- `ItemsResponseDto` 已有 `total`(映射自 `TotalRecordCount`),无需改动。

- [ ] **Step 1: 强制核对 OpenAPI**

```bash
jq -r '.paths["/Items"].get.parameters[]?.name' docs/jellyfin-openapi.json \
  | grep -xE 'startIndex|searchTerm|limit|sortBy|includeItemTypes|recursive|parentId|userId'
```
Expected: 8 个名字全部原样命中(**注意全是小写开头的 camelCase**)。
若有任何一个对不上,**以 OpenAPI 为准**并在报告中说明。

```bash
jq -r '.components.schemas.BaseItemDtoQueryResult.properties|keys[]' docs/jellyfin-openapi.json
```
Expected: 含 `TotalRecordCount` 与 `StartIndex`。

- [ ] **Step 2: 写首页失败的测试**

在 `feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt` 中新增:

```kotlin
    @Test fun `最近添加带加载上限,不拉全量`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.resume(any()) } returns ItemsResponseDto(emptyList(), 0)
        coEvery { api.nextUp(any(), any()) } returns ItemsResponseDto(emptyList(), 0)
        coEvery {
            api.items(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ItemsResponseDto(emptyList(), 0)

        newViewModel(api).loadHome()
        advanceUntilIdle()

        // 库里有 8744 集,不带 limit 会一次性拉全量并渲染
        coVerify {
            api.items(
                userId = any(), types = "Episode,Movie", recursive = any(),
                sortBy = "DateCreated", startIndex = any(), limit = 20,
                parentId = any(), searchTerm = any(),
            )
        }
    }
```

> `newViewModel(api)` 沿用该测试文件中已有的构造辅助函数;若不存在则照现有测试的写法构造。

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew --no-daemon :feature:home:test`
Expected: FAIL — 参数个数不匹配(`items` 尚无 `startIndex`/`searchTerm`),或 `limit` 断言不满足

- [ ] **Step 4: 改 `JellyfinApi.items()`**

把 `core/network/.../JellyfinApi.kt` 中的 `items` 替换为:

```kotlin
    // 原文 GET Users/{userId}/Items 在 10.10.7 已移除,改为 GET Items?userId=
    // startIndex / searchTerm 均已核对 docs/jellyfin-openapi.json 的 /Items 参数表。
    // 注意 Jellyfin 对错误大小写的查询参数静默忽略,不报错 —— 参数名必须逐字与 OpenAPI 一致。
    @GET("Items")
    suspend fun items(
        @Query("userId") userId: String,
        @Query("includeItemTypes") types: String,
        @Query("recursive") recursive: Boolean = true,
        @Query("sortBy") sortBy: String = "SortName",
        @Query("startIndex") startIndex: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("parentId") parentId: String? = null,
        @Query("searchTerm") searchTerm: String? = null,
    ): ItemsResponseDto
```

> 参数为 null 时 Retrofit 不会拼进 URL,所以既有调用方无需改动即可编译。

- [ ] **Step 5: 给首页「最近添加」加上限**

`feature/home/.../HomeViewModel.kt` 第 74 行附近:

```kotlin
                    api.items(userId, types = "Episode,Movie", sortBy = "DateCreated", limit = 20)
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew --no-daemon :core:network:test :feature:home:test`
Expected: PASS(既有测试全绿 + 新增 1 个)

- [ ] **Step 7: Commit**

```bash
git add core/network feature/home
git commit -m "feat(network): add paging and search params to item query"
```

---

### Task 3:`LibraryViewModel` —— 分页与搜索

**Files:**
- Modify: `feature/library/src/main/java/dev/insua/jellycast/feature/library/LibraryViewModel.kt`
- Test: `feature/library/src/test/java/dev/insua/jellycast/feature/library/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `PageState<MediaItem>`;Task 2 的 `JellyfinApi.items(..., startIndex, limit, searchTerm)`
- Produces:
  ```kotlin
  data class LibraryUiState(
      val tab: LibraryTab = LibraryTab.SERIES,
      val series: PageState<MediaItem> = PageState(),
      val movies: PageState<MediaItem> = PageState(),
      val query: String = "",
      val searchResults: PageState<MediaItem> = PageState(),
      val detail: SeriesDetailUiState? = null,
  ) {
      val isSearching: Boolean get() = query.isNotBlank()
      /** 当前该显示哪一页数据 */
      val visible: PageState<MediaItem>
  }

  fun onQueryChange(query: String)
  fun loadNextPage()          // 当前可见列表的下一页
  fun retry()                 // 重试当前可见列表的失败页
  ```
- 常量:`PAGE_SIZE = 50`,`SEARCH_DEBOUNCE_MS = 300L`

- [ ] **Step 1: 写失败的测试**

追加到 `LibraryViewModelTest.kt`:

```kotlin
    private fun page(vararg names: String) =
        ItemsResponseDto(names.map { seriesDto(it) }, total = 120)

    @Test fun `剧集列表首屏只拉一页而不是全量`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.series.items.map { it.name })
        assertEquals(120, vm.uiState.value.series.totalCount)
    }

    @Test fun `loadNextPage 追加下一页且不重复触发`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        coEvery { api.items(any(), "Series", any(), any(), 2, 50, any(), null) } returns page("C", "D")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); vm.loadNextPage()   // 连点两次
        advanceUntilIdle()
        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.series.items.map { it.name })
        coVerify(exactly = 1) { api.items(any(), "Series", any(), any(), 2, 50, any(), null) }
    }

    @Test fun `到底之后不再请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns
            ItemsResponseDto(listOf(seriesDto("A")), total = 1)
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        coVerify(exactly = 0) { api.items(any(), any(), any(), any(), 1, any(), any(), any()) }
    }

    @Test fun `分页失败保留已加载内容并可重试`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A", "B")
        coEvery { api.items(any(), "Series", any(), any(), 2, 50, any(), null) } throws
            java.io.IOException("offline") andThen page("C", "D")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.loadNextPage(); advanceUntilIdle()
        assertEquals(listOf("A", "B"), vm.uiState.value.series.items.map { it.name })
        assertNotNull(vm.uiState.value.series.error)
        vm.retry(); advanceUntilIdle()
        assertEquals(listOf("A", "B", "C", "D"), vm.uiState.value.series.items.map { it.name })
        assertNull(vm.uiState.value.series.error)
    }

    @Test fun `搜索防抖:快速连续输入只发一次请求`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)
        vm.onQueryChange("银"); advanceTimeBy(100)
        vm.onQueryChange("银魂"); advanceUntilIdle()
        coVerify(exactly = 0) { api.items(any(), any(), any(), any(), any(), any(), any(), "银") }
        coVerify(exactly = 1) { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") }
    }

    @Test fun `搜索结果同时包含剧集与电影`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "银魂") } returns page("银魂")
        val vm = newViewModel(api)
        vm.onQueryChange("银魂"); advanceUntilIdle()
        assertTrue(vm.uiState.value.isSearching)
        assertEquals(listOf("银魂"), vm.uiState.value.visible.items.map { it.name })
    }

    @Test fun `清空搜索词回到浏览态并保留原 Tab`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Movie", any(), any(), 0, 50, any(), null) } returns page("电影A")
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("剧A")
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "x") } returns page("X")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.selectTab(LibraryTab.MOVIES)
        vm.onQueryChange("x"); advanceUntilIdle()
        vm.onQueryChange(""); advanceUntilIdle()
        assertFalse(vm.uiState.value.isSearching)
        assertEquals(LibraryTab.MOVIES, vm.uiState.value.tab)
        assertEquals(listOf("电影A"), vm.uiState.value.visible.items.map { it.name })
    }

    @Test fun `搜索失败置 error 且不影响浏览列表`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        coEvery { api.items(any(), "Series", any(), any(), 0, 50, any(), null) } returns page("A")
        coEvery { api.items(any(), "Series,Movie", any(), any(), 0, 50, any(), "q") } throws
            java.io.IOException("offline")
        val vm = newViewModel(api)
        vm.loadLibrary(); advanceUntilIdle()
        vm.onQueryChange("q"); advanceUntilIdle()
        assertNotNull(vm.uiState.value.searchResults.error)
        assertEquals(listOf("A"), vm.uiState.value.series.items.map { it.name })
    }
```

> `seriesDto(name)` / `newViewModel(api)` 沿用该文件中已有的辅助函数;若签名不符,按现有测试的写法调整,**但断言语义不得削弱**。
> 既有测试(点击某集把整季作为队列传出、季按季号排序、剧集电影分两 Tab)**必须继续通过**,如因 `series`/`movies` 由 `List` 变为 `PageState` 而需要调整取值路径,只改取值,不改断言含义。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --no-daemon :feature:library:test`
Expected: FAIL — `PageState` 未接入、`onQueryChange`/`loadNextPage`/`retry` 不存在

- [ ] **Step 3: 实现**

改写 `LibraryViewModel.kt`。要点:

```kotlin
private const val PAGE_SIZE = 50
private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val session: JellyfinSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        // debounce + flatMapLatest:新输入自动取消在途请求,慢响应不会覆盖快响应
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { q ->
                    flow {
                        if (q.isBlank()) {
                            emit(null)
                        } else {
                            emit(runCatching { fetchPage(types = "Series,Movie", startIndex = 0, searchTerm = q) })
                        }
                    }
                }
                .collect { result -> applySearchResult(result) }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank()) _uiState.update { it.copy(searchResults = PageState()) }
        queryFlow.value = query
    }

    fun loadNextPage() { /* 取 visible;isLoading 或 endReached 则直接返回;否则以 loadedCount 为 startIndex 取下一页 */ }

    fun retry() { /* 清 error 后重跑当前可见列表的下一页 */ }

    private suspend fun fetchPage(types: String, startIndex: Int, searchTerm: String? = null): ItemsResponseDto =
        api.items(
            userId = session.userId(), types = types,
            sortBy = "SortName", startIndex = startIndex, limit = PAGE_SIZE,
            searchTerm = searchTerm,
        )
}
```

**必须遵守:**
- `loadNextPage()` 在 `isLoading == true` 或 `endReached == true` 时**立即返回**,这是"连点两次只发一次请求"的保证
- 所有网络调用 `runCatching`,失败走 `PageState.onError(...)`,**不向上抛错**
- 搜索与浏览共用 `fetchPage`,只差 `types` 与 `searchTerm`
- `LibraryUiState.visible` = `if (isSearching) searchResults else if (tab == SERIES) series else movies`

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew --no-daemon :feature:library:test`
Expected: PASS(既有 3 个 + 新增 8 个)

- [ ] **Step 5: 确认测试真的执行了**

Run: `grep -o 'tests="[0-9]*"' feature/library/build/test-results/*/TEST-*.xml`
Expected: N ≥ 11,且无 `tests="0"`

- [ ] **Step 6: Commit**

```bash
git add feature/library
git commit -m "feat(library): add paged loading and debounced search"
```

---

### Task 4:`LibraryScreen` —— 搜索栏与触底加载

**Files:**
- Modify: `feature/library/src/main/java/dev/insua/jellycast/feature/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: Task 3 的 `LibraryUiState.visible` / `isSearching` / `query`,以及 `onQueryChange` / `loadNextPage` / `retry`

- [ ] **Step 1: 加搜索栏**

顶部常驻 `OutlinedTextField`(或 M3 `SearchBar`),placeholder「搜索剧集与电影」,
带前置搜索图标;`query` 非空时显示清除按钮,点击调 `onQueryChange("")`。
`KeyboardOptions(imeAction = ImeAction.Search)`,`singleLine = true`。

**`isSearching` 为 true 时隐藏剧集/电影双 Tab**,结果混排,
用 `MediaItem.kind` 在副标题区分「剧集」/「电影」。

- [ ] **Step 2: 触底预取**

用 `LazyGridState`(或 `LazyListState`)监听:

```kotlin
val shouldLoadMore by remember {
    derivedStateOf {
        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= state.visible.loadedCount - 10   // 距底部 10 项时预取
    }
}
LaunchedEffect(shouldLoadMore, state.visible.loadedCount) {
    if (shouldLoadMore) onLoadNextPage()
}
```

`loadNextPage()` 自身已对 `isLoading`/`endReached` 做了防抖,UI 侧无需再判。

- [ ] **Step 3: 状态行**

列表底部按 `visible` 的状态渲染:

| 条件 | 显示 |
|---|---|
| `isLoading && loadedCount > 0` | 居中转圈 |
| `error != null` | 「加载失败,点击重试」,整行可点 → `retry()` |
| `isLoading && loadedCount == 0` | 首屏转圈 |
| `!isLoading && loadedCount == 0 && isSearching` | 「没有匹配的剧集或电影」 |
| `!isLoading && loadedCount == 0 && !isSearching` | 「这个库还没有内容」 |

**错误态下已加载的内容必须继续显示。**

- [ ] **Step 4: 编译并装机冒烟**

```bash
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon installDebug
adb logcat -d | grep -iE 'FATAL|AndroidRuntime' | head
```
Expected: 构建成功;安装成功;logcat 无崩溃。

> 登录后的完整走查需要凭据,本环境可能无法完成 —— **如实报告到哪一步,不要伪造**。

- [ ] **Step 5: 全量回归**

Run: `./gradlew --no-daemon test :app:assembleDebug`
Expected: 全部模块单测全绿(v1 的 251 个 + 本次新增),app 构建成功。

- [ ] **Step 6: Commit**

```bash
git add feature/library
git commit -m "feat(library): add search bar and infinite scroll to library screen"
```

---

## Self-Review 记录

- **Spec 覆盖:** §3.1 `PageState`→Task 1;§3.2 搜索端点与参数→Task 2/3;§3.3 搜索交互(debounce/取消在途/隐藏 Tab/清空恢复)→Task 3/4;§3.4 状态与错误→Task 3(状态)+ Task 4(呈现);§2「首页最近添加补 limit」→Task 2;§5 测试策略→各 Task 内。
- **占位符:** 无。Task 3 Step 3 的 `loadNextPage`/`retry` 给的是行为约束而非完整代码体,但约束是可判定的(何时立即返回、失败如何处理),且测试已完整给出,实现者不需要猜。
- **类型一致性:** `PageState<T>` 的 `items`/`totalCount`/`isLoading`/`error`/`endReached`/`loadedCount` 在 Task 1 定义,Task 3/4 引用一致;`items(...)` 八参签名在 Task 2 定义,Task 3 测试与实现均按此顺序调用。
- **已知风险:** Task 3 会把 `LibraryUiState.series`/`movies` 由 `List<MediaItem>` 改为 `PageState<MediaItem>`,既有 3 个测试与 `LibraryScreen` 的取值路径需同步调整 —— 已在 Task 3 Step 1 中写明"只改取值,不改断言含义"。
