# 媒体库分页与搜索 设计文档

> 状态:已定稿(2026-07-26)
> 前置:v1 已完成(`2026-07-25-jellycast-design.md`),本文是 v1 之后的第一个增量。

---

## 1. 为什么做这个

v1 完成后复盘发现两个会直接伤害日常使用的问题:

**大库会卡。** `LibraryViewModel` 调 `JellyfinApi.items()` **不传任何 limit,也没有分页**,
打开媒体库会一次性拉全量并一次性渲染。目标服务器上有 **8744 集**、数百部剧,
这不是理论风险,是确定会碰到的。首页「最近添加」分区同样漏传 limit。

**没有搜索。** v1 把搜索列在「明确不做」里(理由是"用户明确不需要"),
但几百部剧靠滚动查找与"体验非常好的播放器"这个目标冲突。**本次明确推翻该决定。**

> ⚠️ **范围变更记录:** 本设计把「搜索」从 v1 的「不做」列表移入「做」。
> `CLAUDE.md` 与 v1 设计文档 §11 需同步更新。

---

## 2. 目标与非目标

**做:**
- 剧集列表、电影列表、搜索结果三处分页加载
- 媒体库顶部搜索栏,搜剧集与电影
- 首页「最近添加」补上加载上限

**不做(本次):**
- 搜索单集(会被几百条单集淹没结果;剧集详情页已能逐集浏览)
- 搜索历史 / 搜索建议 / 拼音首字母匹配
- 排序与筛选(未看、类型、年份)—— 独立议题,另行设计
- 剧集详情页内的季/集分页(单季集数有限,当前一次性加载可接受)
- Paging 3 迁移

---

## 3. 核心设计决策

### 3.1 分页:纯函数状态机 + 手动 load-more

**不用 Paging 3。** 理由是可测性:项目铁律要求「核心逻辑必须可离线单测」,
现有 251 个测试全部建立在"纯 StateFlow ViewModel + MockK"模式上。
`PagingData` 是一个不透明的流,难以在离线单测里断言"第二页追加在第一页之后"这类行为,
引入它会在测试策略上开一个口子。而 Paging 3 的额外能力(占位符、`RemoteMediator`)
本场景用不到。

代价是自己写约 60 行纯逻辑,收益是这块与项目其余部分保持同样的可测性。

**`PageState<T>` —— 不可变、不做 IO、放 `:core:model`。**

```kotlin
data class PageState<T>(
    val items: List<T> = emptyList(),
    val totalCount: Int? = null,      // 服务端 TotalRecordCount;null = 尚未知
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val loadedCount: Int get() = items.size
    /** 已知总数且已全部加载完 */
    val endReached: Boolean get() = totalCount != null && items.size >= totalCount

    fun startLoading(): PageState<T>
    /** startIndex 用于幂等:与当前 loadedCount 不符的响应直接丢弃(乱序/重复请求) */
    fun onPageLoaded(newItems: List<T>, startIndex: Int, total: Int): PageState<T>
    /** 失败不清空已加载内容 */
    fun onError(message: String): PageState<T>
}
```

`:core:model` 已有 `SubtitleTimeline`(纯查找逻辑)这个先例,放这里不破坏模块定位。

**分页参数:** 每页 **50**;滚到距列表底部 **10** 项时预取下一页。

`JellyfinApi.items()` 需补 `startIndex` 参数。**`/Items` 已支持 `startIndex`,
响应 `BaseItemDtoQueryResult` 带 `TotalRecordCount` 与 `StartIndex`,均已核对
`docs/jellyfin-openapi.json`。**

### 3.2 搜索:复用 `/Items?searchTerm=`,不用 `/Search/Hints`

两个端点都存在。选前者,理由:

| | `/Items?searchTerm=` | `/Search/Hints` |
|---|---|---|
| 返回类型 | `BaseItemDto` | `SearchHint` |
| 能否复用现有 mapper | ✅ 完全同构 | ❌ 需另写映射 |
| 能否复用分页 | ✅ 同一套 `startIndex`/`TotalRecordCount` | 需另做 |
| 是否带 `imageTag` | ✅ | 字段不同 |

搜索与浏览走同一条代码路径,只是多带一个 `searchTerm`,少一个类型过滤。

**参数:** `searchTerm` + `includeItemTypes=Series,Movie` + `recursive=true` +
`startIndex` + `limit`。参数名大小写以 OpenAPI 为准 —— Jellyfin 对错误大小写**静默忽略**,
v1 期间已在计划文档中查出 6 处此类错误。

### 3.3 搜索交互

- 搜索框常驻媒体库顶部,**输入即搜**,`debounce(300ms)`
- 用 `MutableStateFlow<String>` + `flatMapLatest`,**自动取消在途请求**
  (避免慢响应覆盖快响应导致结果闪回)
- 有搜索词时**隐藏剧集/电影双 Tab**,结果混排,用副标题区分类型
- 清空搜索框 → 回到浏览态,**恢复原先的 Tab 选择**
- 搜索结果同样分页

### 3.4 状态与错误

| 场景 | 表现 |
|---|---|
| 首页加载中 | 骨架/转圈 |
| 加载下一页 | 列表底部转圈 |
| 下一页失败 | 底部「加载失败,点击重试」,**已加载内容不清空** |
| 搜索无结果 | 「没有匹配的剧集或电影」 |
| 搜索出错 | 同上错误行,可重试 |
| 库为空 | 「这个库还没有内容」 |

**原则:分页或搜索出错,绝不清空用户已经看到的内容。**

---

## 4. 受影响的模块

| 模块 | 改动 |
|---|---|
| `:core:model` | 新增 `PageState<T>` |
| `:core:network` | `JellyfinApi.items()` 补 `startIndex`;确认 `searchTerm` 参数 |
| `:feature:library` | `LibraryViewModel` 接分页 + 搜索;`LibraryScreen` 加搜索栏与 load-more |
| `:feature:home` | 「最近添加」补 limit=20 |

**不动:** `:core:player`、`:core:subtitle`、`:feature:player`、`:feature:server`。

---

## 5. 测试策略

**`PageState` 纯单测(离线):**
- 首页加载后 `items` 正确、`totalCount` 记录
- 第二页追加在第一页之后,顺序不乱
- **幂等:`startIndex` 与当前 `loadedCount` 不符的响应被丢弃**(重复/乱序请求)
- `endReached` 在加载满 `totalCount` 后为 true;`totalCount` 为 null 时为 false
- `onError` 保留已有 `items`
- 空结果:`items` 为空且 `endReached` 为 true

**`LibraryViewModel` 单测(MockK 假 api,离线):**
- 滚到底触发下一页,且不会重复触发
- **搜索 debounce:快速连续输入只发一次请求**
- **在途请求被新输入取消**(旧结果不覆盖新结果)
- 清空搜索词恢复浏览态与原 Tab
- 搜索失败保留已有结果并置 error
- 分页失败后重试能继续

**`HomeViewModel`:** 断言「最近添加」请求带 limit。

---

## 6. 与既有铁律的关系

本次改动**不触碰**播放、字幕、TLS、降级链,五条铁律中只有两条相关:

- **禁止凭记忆写 Jellyfin API** —— `startIndex` / `searchTerm` / `includeItemTypes`
  的存在与大小写必须以 `docs/jellyfin-openapi.json` 为准逐一核对
- **核心逻辑必须可离线单测** —— 正是不选 Paging 3 的原因

---

## 7. 开放问题

无。
