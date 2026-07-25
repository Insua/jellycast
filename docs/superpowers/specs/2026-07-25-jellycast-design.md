# JellyCast 设计文档

> 状态:已定稿(2026-07-25)
> 后续实现请参照 `docs/superpowers/plans/2026-07-25-jellycast-implementation.md`

---

## 1. 产品目标

**一句话:把自建 Jellyfin 服务器上的剧集和电影,当成播客来"听"的 Android 播放器。**

用户已经在 NAS 上囤了大量剧集,但很多内容(脱口秀、纪录片、访谈、日常番)其实**只听声音就够了**。现有的 Jellyfin 官方客户端是"视频播放器"形态,要盯着屏幕、锁屏就停、费电费流量。

JellyCast 把这件事翻转过来:**UI 是音乐/播客播放器的形态(对标小宇宙、Spotify),内核连的是 Jellyfin,全程不渲染视频画面。**

### 成功标准

1. 锁屏状态下能连续听完一整季剧,手机放兜里不发烫;
2. 出门在外(移动网络)听剧,流量消耗接近听播客,而不是看视频;
3. 手机上听到第 12 集第 8 分钟,打开电视上的 Jellyfin 能接着看;
4. 打开 App 到开始播放"下一集",不超过 3 次点击。

---

## 2. 部署环境(已确认)

| 项 | 值 |
|---|---|
| 服务器 | 群晖 **DS920+**(Intel Celeron **J4125**,4 核,带 UHD 600 核显,**支持 QSV 硬件转码**) |
| Jellyfin 安装方式 | 矿神(第三方)群晖套件源 |
| 接入方式 1 | **Tailscale 内网**(`100.x.x.x`) |
| 接入方式 2 | **公网 HTTPS**,DDNS + IPv6 |
| 客户端 | Android 手机 |

**推论:**
- J4125 做**视频**转码只能扛 1~2 路,但做**音频**转码/remux 毫无压力,若源音轨已是 AAC 甚至可以直接 copy(CPU 接近 0)。
- 出门走公网时,**瓶颈是家宽上行带宽**。这决定了"服务端只输出音频"不是锦上添花,而是**核心价值**。

---

## 3. 核心设计决策

### 3.1 音频投递:两级降级链(本项目最重要的设计)

> ✅ **已由 Spike-1 实测确定(2026-07-25)。完整数据见 `2026-07-25-spike-results.md`。**
> 原设计的三级链已修订为两级:**L2(HLS 音频 rendition)实测不存在,已删除。**

播放一集时,按顺序尝试,**第一个成功的即采用**:

| 级别 | 做法 | 实测流量 | CPU | 状态 |
|---|---|---|---|---|
| **L1 服务端纯音频流** | `GET /Audio/{itemId}/universal?audioCodec=aac&audioBitRate=…` | **71–132 Kbps 实测** | 41x 实时,非瓶颈 | ✅ **实测可用,默认路径** |
| ~~L2 HLS 音频 rendition~~ | ~~从 HLS 主播放列表取音频 rendition~~ | — | — | ❌ **实测不存在,删除** |
| **L3 客户端禁用视频轨** | 拉完整流,用 Media3 `TrackSelector` 关掉 video track | 高(仍下载视频字节) | 低(不解码视频) | ✅ 兜底 |

**实测省流量倍数:** 原始 1080p 流 5.57 Mbps → L1 @128k 实测 132 kbps,**省 42 倍**;
@64k 实测 71 kbps,**省 78 倍**。约 58 MB/小时 vs 原始 2.5 GB/小时。**产品核心价值已被数据证实。**

**设计原则:L3 保证产品一定能用,L1 决定产品好不好用。** 降级对用户**静默无感**,只在开发者选项里显示当前级别。

> ⚠️ **关键架构约束(Spike-1 实测):转码流返回 `Accept-Ranges: none`。**
> 带 Range 的请求返回 200 而非 206,服务器直接忽略。因此 **ExoPlayer 无法按字节 seek**——
> **seek 必须实现为「带新的 `startTimeTicks` 重新发起请求并 prepare」**。断点续播同理。
> 这一条直接决定 `:core:player` 的设计,不可沿用"给播放器一个 URL 然后 seekTo"的思路。

> ⚠️ **注意 L1 用的是 `/Audio/…` 接口而非 `/Videos/…`** —— 即把音频接口用在视频条目上。
> 计划中曾推测的 `/Videos/{id}/stream.mp3`(HTTP 500)与
> `/Videos/{id}/stream?audioCodec=aac`(视频轨仍在)**均已实测否定**。

**永远不渲染视频。** 即使走 L3,ExoPlayer 也不绑定 `Surface`,播放界面显示的是**海报封面**。

### 3.2 字幕即歌词(产品亮点)

把字幕做成 **Apple Music 歌词** 那样的体验:随播放进度**逐行滚动 + 当前行高亮**,可点击某行**跳转到该时间点**。

| 字幕类型 | 支持 | 说明 |
|---|---|---|
| **内封文本字幕**(mkv/mp4 容器内的 SRT/ASS/SSA 轨) | ✅ | 通过 Jellyfin 字幕接口独立拉取文本 |
| **外挂字幕**(同目录 .srt/.ass,已被 Jellyfin 识别) | ✅ | 同上 |
| **硬字幕 / 图形字幕**(烧进画面的、PGS/VobSub 位图) | ❌ | 无法提取文本,UI 中不提供该选项 |

**关键架构点:** 因为走的是**纯音频流**,字幕轨**不在音频流里**。所以字幕必须**独立 HTTP 拉取 → 自行解析 → 自行渲染**,而**不是**依赖播放器内建的字幕渲染。

好处:歌词式 UI 完全可控,且在 L1/L2/L3 任何级别下都能工作。

### 3.3 多服务器 + 多接入地址(自动选路)

两层模型,不要混淆:

```
Server(一台 Jellyfin 服务器)
  ├─ name          "家里 NAS"
  ├─ credentials   userId + accessToken
  └─ endpoints[]   同一台服务器的多个接入地址
       ├─ { url: "http://192.168.1.10:8096",     label: "局域网",   priority: 1 }
       ├─ { url: "http://100.x.x.x:8096",        label: "Tailscale", priority: 2 }
       └─ { url: "https://xxx.ddns.net:8920",    label: "公网",      priority: 3 }
```

**自动选路策略:** 连接时对所有 endpoint **并发发起健康探测**(`GET /System/Info/Public`,超时 3s),**取第一个成功返回的**。在家自动走局域网,出门自动走 Tailscale 或公网,**用户零操作**。

播放过程中当前 endpoint 失效时,自动重新选路并续播。

**多服务器:** 支持添加多台 Jellyfin 服务器,各自独立登录态,顶部可切换。

### 3.4 HTTPS 与证书

- 公网走 HTTPS(DDNS + IPv6)。群晖 DSM 可签发 Let's Encrypt 正式证书,**优先假设是可信证书**。
- 若探测到**自签证书**,弹窗让用户**显式确认信任该证书指纹**(记录指纹,仅对该 endpoint 生效)。**不允许全局关闭证书校验。**
- IPv6 地址需正确处理方括号形式(`https://[240e::1]:8920`)。

### 3.5 播放行为

| 能力 | 决策 |
|---|---|
| 后台播放 | 前台 Service + `MediaSessionService` |
| 锁屏 / 通知栏 / 蓝牙 / 车机控制 | 由 MediaSession 统一提供 |
| 拔耳机 | 自动暂停(`AudioManager` 焦点处理) |
| 自动连播下一集 | 默认开,可关。基于 Jellyfin `NextUp` |
| 倍速 | 0.5x – 3.0x,记忆上次设置 |
| 快退 / 快进 | 15s / 30s(可在设置中调整) |
| 睡眠定时 | 15/30/45/60 分钟 + "播完本集" |
| 音轨选择 | 支持(多语言音轨) |
| 进度同步 | 双向同步回 Jellyfin |

---

## 4. 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 播放 | **Media3 (ExoPlayer) + MediaSessionService** |
| 网络 | Retrofit + OkHttp + kotlinx.serialization |
| 依赖注入 | Hilt |
| 本地存储 | DataStore(配置/凭据) + Room(列表缓存、播放进度) |
| 图片 | Coil |
| 异步 | Coroutines + Flow |
| 测试 | JUnit5 + MockK + Turbine + Compose UI Test |

**最低 SDK:** 26(Android 8.0) — MediaSession 与前台服务行为在此之上稳定。
**目标 SDK:** 最新稳定版。

---

## 5. 模块架构

```
:app                      应用入口、导航、依赖装配

:core:model               纯数据模型(Server, Endpoint, MediaItem, Episode, SubtitleLine…)
:core:network             Jellyfin API 客户端、认证、endpoint 选路、证书策略
:core:database            Room:缓存与进度
:core:datastore           DataStore:服务器列表、凭据、用户偏好
:core:player              播放引擎、降级链、MediaSession、播放队列
:core:subtitle            字幕拉取、解析(SRT/ASS/VTT)、时间轴索引
:core:designsystem        主题、配色、通用组件(迷你播放条、封面卡…)

:feature:server           服务器列表 / 添加 / 登录 / 选路状态
:feature:library          剧集浏览、季/集列表、电影列表
:feature:home             "在听" 页(继续收听 / 下一集 / 最近添加)
:feature:player           全屏播放页 + 歌词式字幕视图
:feature:settings         设置
```

**边界原则:** 每个 module 单一职责,对外只暴露 interface;`:core:player` 不认识 Jellyfin API,只接受一个已解析好的播放源 URL + 元数据。

---

## 6. 界面结构

```
[服务器选择 / 登录]
  · 服务器列表(可添加多台)
  · 添加服务器:名称 + 一个或多个接入地址 + 用户名/密码
  · 显示当前选中的 endpoint 与延迟
      ↓
[主界面]  底部 3 Tab + 全局常驻迷你播放条
  │
  ├─ 「在听」(首页)
  │    · 继续收听 (Resume)
  │    · 下一集 (NextUp)          ← 追剧主入口
  │    · 最近添加
  │
  ├─ 「媒体库」
  │    · 剧集 → 季 → 单集列表
  │    · 电影(平铺)
  │
  └─ 「设置」
       服务器管理 / 默认倍速 / 快进快退秒数 / 自动连播 /
       字幕(歌词)开关与默认语言 / 开发者信息(当前降级级别)

[全屏播放页](上滑迷你条展开)
  · 大封面海报 + 剧名 / 集名 / 集号
  · 【歌词式字幕区】随进度滚动高亮,点击行可跳转
  · 进度条(可拖) + 已听/总时长
  · ⏪15s   ▶/⏸   ⏩30s
  · 倍速 · 睡眠定时 · 音轨 · 字幕语言 · 下一集
```

**核心视觉决定:全程无视频画面。** 播放页把海报放在音乐播放器"专辑封面"的位置,这是让它"感觉像播客"的关键。

---

## 7. Jellyfin API 契约

> ⚠️ **强制要求:** 下列接口按 Jellyfin 通用 API 撰写,**实现前必须以目标服务器实际的 OpenAPI 文档为准核对**
> (`{server}/api-docs/swagger.json` 或 `{server}/openapi.json`)。**不得凭本文档或模型记忆直接编码。**

| 用途 | 接口 |
|---|---|
| 服务器探活(选路用,无需认证) | `GET /System/Info/Public` |
| 登录 | `POST /Users/AuthenticateByName` → `AccessToken` + `User.Id` |
| 认证头 | `Authorization: MediaBrowser Client="JellyCast", Device="<型号>", DeviceId="<uuid>", Version="<ver>", Token="<token>"` |
| 媒体库列表 | `GET /Users/{userId}/Views` |
| 剧集列表 | `GET /Users/{userId}/Items?IncludeItemTypes=Series&Recursive=true` |
| 电影列表 | `GET /Users/{userId}/Items?IncludeItemTypes=Movie&Recursive=true` |
| 季 | `GET /Shows/{seriesId}/Seasons` |
| 集 | `GET /Shows/{seriesId}/Episodes?seasonId={id}` |
| 继续收听 | `GET /Users/{userId}/Items/Resume` |
| 下一集 | `GET /Shows/NextUp?userId={userId}` |
| 条目详情 | `GET /Users/{userId}/Items/{itemId}` |
| 封面 | `GET /Items/{itemId}/Images/Primary?maxWidth=…` |
| **播放信息(降级链决策依据)** | `POST /Items/{itemId}/PlaybackInfo` |
| 播放流 | `GET /Videos/{itemId}/stream` / `master.m3u8`(参数见 Spike-1 结论) |
| **字幕流** | `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` |
| 上报开始 | `POST /Sessions/Playing` |
| 上报进度 | `POST /Sessions/Playing/Progress`(每 10s 或 seek 时) |
| 上报结束 | `POST /Sessions/Playing/Stopped` |

---

## 8. 错误处理

| 场景 | 行为 |
|---|---|
| 所有 endpoint 都探测失败 | 提示"**无法连接到服务器**",并列出各 endpoint 的失败原因(超时 / 拒绝 / DNS) |
| Tailscale 未连接 | 若 Tailscale endpoint 超时且其他也不通,明确提示"**检查 Tailscale 是否已连接**" |
| 自签证书 | 弹窗展示证书指纹,用户确认后信任(仅该 endpoint) |
| Token 过期(401) | 用保存的凭据静默重新认证;再失败才跳登录页 |
| 降级链某级失败 | **静默降级**,用户无感;三级全败才提示"该条目无法播放" |
| 播放中网络中断 | 缓冲耗尽后暂停 + 提示;网络恢复自动从断点续播 |
| 字幕拉取失败 | **不影响播放**,歌词区显示"无可用字幕" |
| 无文本字幕(仅硬字幕/位图) | 歌词区显示"此内容无文本字幕",不报错 |
| 进度上报失败 | 本地入队,下次联网补报 |

---

## 9. 技术风险验证(Spike)结论

> 完整实测数据见 **`2026-07-25-spike-results.md`**。下面是摘要。

**Spike-1(阻塞级):Jellyfin 能否输出纯音频流?→ ✅ 可行,已解除阻塞**

- 可用接口:`GET /Audio/{itemId}/universal?audioCodec=aac&audioBitRate={bps}&maxAudioChannels=2`
- 实测码率:请求 128k → 输出 132 kbps;请求 64k → 输出 71 kbps(原始 5.57 Mbps)
- NAS 性能:**41 倍实时速度**,J4125 非瓶颈,无需为 CPU 做降级设计
- **L2(HLS 音频 rendition)实测不存在**(`/Audio/…/master.m3u8` 返回 500;
  `/Videos/…/master.m3u8` 只有一条 1080p 混流),**已从降级链删除**
- **关键约束:`Accept-Ranges: none`**,seek 必须靠重发 `startTimeTicks` 请求(详见 §3.1)

**Spike-2:字幕文本获取 → ✅ 可行**

- 接口:`GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}`
- `srt` / `vtt` / `ass` / `js` 四种格式**全部返回 200**;**选用 `srt`**(体积最小、毫秒精度)
- 位图字幕识别字段:`MediaStreams[].IsTextSubtitleStream`
- ⚠️ **真实数据的三个坑,解析器必须处理:**
  1. **ASS 特效标签 `{\pos(…)}` 未被剥离**(srt 与 js 中均残留)→ 解析器须同时剥离 `{...}` 与 HTML 标签
  2. **存在零时长条目**(`00:00:00,000 --> 00:00:00,000`,内容为制作组标记)→ 须过滤 `endMs <= startMs`
  3. 文件带 **UTF-8 BOM**,VTT 另有 `Region:` 头块

**Spike-3:公网 IPv6 + HTTPS 可达性 → ⏸ 未完成**

- **缺少 DDNS 域名,无法测试。** Tailscale 侧仅 8096 开放,8920 不可达。
- **对开发影响有限:** 多地址选路与证书指纹白名单的实现不依赖此结论,
  只需保证公网 endpoint 探测失败时正确降级。待域名到位后在 Task 23 端到端验收中补测。

---

## 10. 测试策略

**单元测试(必须):**
- endpoint 选路逻辑(多个候选、超时、优先级)
- 降级链决策逻辑(各级失败时是否正确降级)
- 字幕解析(SRT/ASS/VTT → 时间轴模型),含畸形输入
- 歌词当前行计算(给定播放位置 → 正确的行索引)
- 进度上报的补报队列

**集成测试:**
- 对真实 DS920+ 走通:登录 → 列表 → 播放 → 上报 → 断点续播

**手动验收清单:**
- [ ] 锁屏能暂停/切集
- [ ] 蓝牙耳机按键有效
- [ ] 拔耳机自动暂停
- [ ] 后台 30 分钟不被系统杀
- [ ] 倍速、睡眠定时生效
- [ ] 歌词随播放滚动、点击可跳转
- [ ] 从家里 WiFi 切到移动网络,自动切 endpoint 且续播
- [ ] 手机听到一半,Jellyfin Web 端显示相同进度

---

## 11. v1 范围边界

**做:** 多服务器 + 多接入地址自动选路 / 剧集 + 电影 / 纯音频播放(三级降级)/ 歌词式字幕 / 后台播放与锁屏控制 / 倍速 · 睡眠定时 · 快进快退 / 自动连播 / 音轨选择 / 进度双向同步

**明确不做:**
- ❌ 离线下载(v2)
- ❌ 搜索(用户明确不需要)
- ❌ 视频画面渲染(与产品定位冲突)
- ❌ 音乐 / 有声书库
- ❌ 投屏 / 遥控其他设备
- ❌ 收藏夹 / 自定义播放列表
- ❌ 硬字幕 OCR

---

## 12. 开放问题

无。设计已定稿。
