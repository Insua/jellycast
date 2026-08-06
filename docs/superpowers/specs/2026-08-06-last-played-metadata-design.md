# 上次播放记录缺失季集与封面 设计文档

> 状态:已定稿(2026-08-06)
> 前置:v9(音频缓存子系统)已合入 master。
> 本文修复一个真机现场缺陷:冷启动恢复出来的播放信息不完整。

---

## 1. 现象

用户原话:

> 保存当前的播放进度的,他没有保存当前的 季、集、图,还有标题信息,这个不太好吧。

冷启动后迷你条是恢复出来了,但:

- 迷你条**没有封面**
- 展开播放页,顶栏的**剧名是空的**
- 播放页的**季集副标题(S01E02)也是空的**

而且点下播放之后这些空白**不会自己补上** —— `AudioPlaybackEngineImpl.play()` 不回写 `PlayQueue`,
恢复出来的那个残缺条目会一直用到用户从别处重新进入为止。

---

## 2. 根因

### 2.1 结构化季集字段根本没被持久化

`LastPlayed`(`core/datastore/.../LastPlayedStore.kt`)只存了两个**已经拼好的显示字符串**
`title` 与 `subtitle`,没有存 `seriesName` / `seasonNumber` / `episodeNumber` / `seriesId` / `seasonId`。

于是 `AppSessionViewModel.restoreLastPlayed()` 重建出来的 `MediaItem` 里这五个字段全是 `null`。

播放页直接读结构化字段,不读那个拼好的字符串:

- `PlayerScreen.kt:158` → `mediaItem?.seriesName.orEmpty()` → 顶栏空白
- `PlayerScreen.kt:298` → `mediaItem?.displaySubtitle` → 而 `displaySubtitle` 由
  `seriesName` + `seasonNumber` + `episodeNumber` 拼成(`Media.kt:38-46`),三个都是 null → 空串

**这不只是显示问题。** `seriesId` / `seasonId` 还有功能用途:

| 位置 | 用法 |
|---|---|
| `AutoPlayNextController.kt:182` | `finished.seriesId ?: detail().seriesId ?: return Outcome.Unknown` |
| `CachePrefetchController.kt:274` | `item.seriesId ?: detail().seriesId ?: return null` |

两处都有「回退到一次网络 `detail()` 查询」的兜底。所以**有网时**只是多一次往返;
**断网时那次兜底也失败** —— 冷启动恢复后接着听,会既不自动连播、也不预取缓存。

### 2.2 封面拿不到 baseUrl:一个时序竞态

`restoreLastPlayed()` 在 `init` 里启动,而它读的 `baseUrl.value` 此刻还是初始的空串 ——
`refreshBaseUrl()` 是另一条独立的异步流程。于是:

```kotlin
posterUrl = baseUrl.value.takeIf { it.isNotBlank() }?.let { item.posterUrl(it) }   // → null
```

而且 `_miniPlayer` 是一次性快照,**baseUrl 后来解析出来了也没有任何地方重算它**。

> 注意 `imageTag` 本身是**存了**的 —— 缺的不是图片标识,是拼 URL 需要的服务器地址。

---

## 3. 方向

### 3.1 持久化结构化字段

在 `LastPlayed` 里补上 `seriesName` / `seasonNumber` / `episodeNumber` / `seriesId` / `seasonId`,
恢复时原样填回 `MediaItem`。

**每个新字段都必须带默认值。** 这是本仓库已经吃过一次的教训(上一批 Task 5 复审):没有默认值的话,
字段引入之前写盘的旧记录会在读取的 `runCatching` 里整条解析失败被静默丢弃 ——
用户升级后冷启动的迷你条会凭空消失一次。

### 3.2 封面随 baseUrl 就绪而补上

迷你条的封面不能是一次性快照。baseUrl 解析出来之后要能反映到已恢复的条目上。

**约束不变:恢复本身仍然绝不发网络请求。** `refreshBaseUrl()` 已经有三级兜底
(本次选路结果 → 本进程上次成功的地址 → 激活服务器优先级最高的 endpoint),
最后两级不需要联网;URL 只是 Coil 的缓存键,命中磁盘缓存的请求根本不走网络。

### 3.3 不做的

- **不为恢复去服务端重新拉元数据** —— 那会违反「冷启动恢复不发网络请求」这条硬约束,
  也让断网冷启动重新退化成空白迷你条。记录里存得下的就存,存不下的就不显示。
- 不改 `displaySubtitle` 的拼接规则 —— 它没问题,问题是喂给它的字段是空的。
- 不回写 `PlayQueue`(播放开始后用完整元数据覆盖恢复出来的条目)—— 那是另一个更大的改动,
  且需要一次网络查询;本次把记录存全之后,这条路径的收益已经很小。

---

## 4. 验收标准

- 播一集(剧集),杀掉进程重开:
  - 迷你条**有封面**
  - 展开播放页,顶栏显示**剧名**
  - 播放页副标题显示 **S01E02** 形式的季集
- 电影同理:标题正确,不显示季集编号
- 断网冷启动:上述显示信息**照常出现**(它们来自本地记录),封面走 Coil 磁盘缓存
- 恢复过程**仍然不发任何网络请求**、不自动播放、不启动前台服务
- 字段引入**之前**写盘的旧记录仍能正常解出并恢复(只是季集为空)
- 既有全部测试保持绿

---

## 5. 测试策略

- **记录读写**:JVM 单测,逐字段往返;**旧格式 JSON(没有新字段)仍能解出**这条必须有
- **恢复填回**:JVM 单测,断言重建出的 `MediaItem` 的五个字段与记录一致
- **封面**:JVM 单测,断言 baseUrl 由空变为有值之后,迷你条的 `posterUrl` 不再是 null
- **不发网络请求**:沿用既有那条用例,确认本次改动没有把它破坏
- 每条新测试都要变异验证 —— 本仓库反复出现过「看着对但测不出任何东西」的空测试

---

## 6. 开放问题

无。
