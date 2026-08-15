# 高码率片源播不出来 & 列表滚动位置不保留 设计文档

> 状态:已定稿(2026-08-15)
> 前置:v11(上次播放记录补季集与封面,`ffee8fb`)已合入 master。
> 本文覆盖两件互不相关的事,共用一个批次:§1–§4 播放,§5 滚动位置。

---

## 1. 现象

用户报告:纪录片库里「舌尖上的中国 第四季 第一集」点开播不出来,一直卡死,界面显示的降级级别在
L2/L3 之间摇摆(**注:本产品只有 L1/L3 两级,"L2" 是用户对不稳定表现的描述,不是真有第三个级别**)。
其它剧集正常。

对真实服务器实测(2026-08-15,`test` 账户)确认现象成立,并定位到三个独立成因。

---

## 2. 片源特征

```
/volume2/watch-s2/documentary/舌尖上的中国/S04/….CCTV-4K….S04E01.2160p.50fps.UHDTV.HEVC.10bit.HLG.DD5.1-QHstudIo.ts
```

| 项 | 值 |
|---|---|
| 容器 | **ts**(MPEG-TS) |
| 视频 | hevc Main 10,3840×2160,50 fps,HLG,**36.9 Mbps** |
| 音频 | ac3 5.1,448 kbps |
| 总码率 | **37.4 Mbps** |

对照组「恰同学少年」:mkv / h264 / 528p / **2.0 Mbps**。

---

## 3. 根因:三处独立缺陷,缺一不可

### 3.1 R1 —— 探测被 OkHttp 默认超时打断(**根因**)

L1 本身**完全可用**。用 App 拼的那条 URL 原样请求:

```
GET /Audio/{id}/universal?api_key=…&mediaSourceId=…&audioCodec=aac&audioBitRate=128000&maxAudioChannels=2
→ 200  Content-Type: audio/aac  Accept-Ranges: none  Transfer-Encoding: chunked
→ 起播后 5388 kbps = 42 倍实时
```

问题在**首字节延迟**。MPEG-TS 没有全局索引,ffmpeg 必须先扫描才能产出第一帧音频,文件越大越慢:

| 条目 | L1 响应头延迟(冷) |
|---|---|
| 恰同学少年 E1 / E2(1080p h264,2.0 Mbps,mkv) | **1.2s / 2.4s** |
| 舌尖 S1E1(1080p h264,17.6 Mbps,mkv) | **2.1s** |
| 舌尖 S4 各集(4K HEVC,37.4 Mbps,**ts**) | **6.3s / 7.6s / 7.8s / 9.6s / 13.2s / 46.1s** |

而 `HttpStreamProbe` 用的是 `TrustAwareHttpClientModule` 提供的 `OkHttpClient.Builder().build()` ——
**OkHttp 默认 `readTimeout` 恰好是 10 秒**,全项目没有任何一处设置过超时。

用 10 秒超时对三集从未请求过的直接复现:

```
S4E4   9.6s  成功           ← 险过
S4E5  10.1s  TimeoutError   → 探测 null → 降级 L3
S4E6  10.3s  TimeoutError   → 探测 null → 降级 L3
```

**这解释了用户看到的"级别摇摆":不是两个级别,是同一条路径踩在 10 秒线上左右横跳。**

`PlaybackSourceResolver.isAudioOnly` 拿到 `null` 后按设计走 L3 兜底且**不缓存**该判定 —— 判定逻辑本身
是对的,错的是它收到了一个假的"没结论"。

### 3.2 R2 —— 播放器自己的超时是第二堵墙

即使探测通过,`createAudioOnlyPlayer` 里的 `DefaultMediaSourceFactory(context, extractors)` 用的是
media3 默认 `DefaultHttpDataSource`:

```
DEFAULT_CONNECT_TIMEOUT_MILLIS = 8000
DEFAULT_READ_TIMEOUT_MILLIS    = 8000     ← 从 media3 1.10.1 的 class 常量读出
```

**8 秒比探测的 10 秒还短。** 只修 R1 不修 R2,结果是探测说"能走 L1",播放器随即 `Source error`。

### 3.3 R3 —— L3 兜底对这种片源根本不成立(把错判放大成卡死)

`buildVideoStreamUrl` 拼的 L3 URL **不带任何码率/尺寸/音频参数**,服务端因此对视频做**流拷贝**:

```
GET /Videos/{id}/stream.mkv?api_key=…&mediaSourceId=…&playSessionId=…
→ 20 秒下载 136.3 MB = 54.5 Mbps
```

客户端虽然用 `audioOnlyTrackSelectionParameters()` 禁用了视频轨(铁律 1 不受影响),但**混流的字节
必须整条下载**才能取出音频。要维持音频实时就得持续吃 37.4 Mbps —— 家宽上行不可能,内网也吃力。
**这就是"一直卡死"。**

铁律 3 写着「降级链必须保底可用」,而 L3 对 4K 片源**事实上不可用**,是本条要修的理由。

---

## 4. 排除掉的两条路(都有实测,不要再试)

### 4.1 HEAD 探测 —— 是空探测,已否决

HEAD 只要 **0.1–1.3 秒**且不起转码,看起来是完美方案。但对 16 种(容器,视频编码,音频编码)组合
**全部**秒回 `200 audio/aac`,包括一个**根本没有音轨**的条目;而同一条目 GET 回 **500**。

结论:`Content-Type` 是从请求里的 `audioCodec=aac` 推出来的,不是实测结果。**HEAD 没有鉴别力,
GET 有。探测必须保持 GET。**

### 4.2 靠 `PlaybackInfo` 元数据判定 —— 同理

元数据说不出"服务端这次转码会不会失败"。上面那个无音轨条目的元数据一切正常,GET 才 500。

---

## 5. 修法

### 5.1 R1:探测的超时

在 `HttpStreamProbe` **内部**从注入的共享 client 派生一个只改超时的副本:

```
connectTimeout  10s   ← 服务器真的不可达时快速失败,不让用户干等
readTimeout     60s   ← 慢转码不是死服务器;覆盖实测最坏值 46.1s 并留余量
```

**两个超时必须分开设**,这是本条设计的要点:把它们混成一个值,要么在死服务器上白等 60 秒,
要么在慢转码上误判 —— 正是现在这个 bug。

**放在 `HttpStreamProbe` 里而不是 DI 模块**,有两个理由:
- 能用 MockWebServer 在 JVM 上钉死(DI 模块里的超时没法离线测)。
- `newBuilder()` 派生保留连接池与自签证书信任,`PlayerModuleTest` 里那条"探测必须用
  `@TrustAwareHttpClient`"的反射断言**不受影响,不需要改**。

### 5.2 R2:播放器的超时

给 `createAudioOnlyPlayer` 用的 `DefaultMediaSourceFactory` 显式配一个
`DefaultHttpDataSource.Factory`,超时与 §5.1 **取同一组常量**(10s / 60s)——两处用途相同,
分别取值只会在下一次调整时漏掉一处。

### 5.3 R3:L3 强制降到最低码率

`buildVideoStreamUrl` 增加:

```
&videoCodec=h264&videoBitRate=100000&maxWidth=320&maxFramerate=15
&audioCodec=aac&audioBitRate=128000&maxAudioChannels=2
```

音频参数是顺手补的 —— L3 现在**一个音频参数都不带**,服务端给什么是什么。

实测效果(舌尖 S4,60 秒采样):

| | 码率 | 相对实时 |
|---|---|---|
| 现在(流拷贝) | **54.5 Mbps** | 播不了 |
| 加上限后 | **0.51 Mbps** | **2.2 倍实时**,撑得住 |

**已知代价:** 降码率意味着服务端要真转码 4K HEVC,实测起播 **25.7 秒**。接受 —— L3 是罕见兜底,
而且 R1/R2 修完之后这一集根本走不到 L3。

---

## 6. 已知问题:探测那次 GET 是白起的一个转码(本次**不修**)

`HttpStreamProbe` 读完响应头就 `close()`,服务端那个已经启动的转码作业被丢弃;紧接着 ExoPlayer
用同一条 URL 再请求一次,服务端**从头再起一个**。实测(NAS 空闲,舌尖 S4):

| | 首次出声等待 |
|---|---|
| 直接播,不探测 | **7.9s** |
| 先探测再播(现在的行为) | **23.6s**(探测 7.8 + 播放 15.7) |
| NAS 有负载时最坏测到 | **126.5s**(探测 6.3 + 播放 120.2) |

对照组 S1(17.6 Mbps h264 mkv)双请求只多 2.8 秒 —— **代价随片源分析成本放大,不是常数。**

**本次不动它。** 去掉这次浪费要把架构改成「乐观走 L1 + 播放失败再降级 L3」,需要新增播放期
降级路径、改动 `AudioPlaybackEngine` 核心与它的 200+ 条既有测试,风险远大于本 bug 本身。
记录在此,作为后续独立议题。

---

## 7. 列表滚动位置返回后回到顶部

### 7.1 现象

进电视剧列表 → 点进某一部 → 返回列表 → **列表回到顶部**,之前滚到哪里丢失。

### 7.2 静态排查已经排除的原因

**不是"忘了用 `rememberSaveable`"。** 全部四个列表用的都是 saveable 支撑的滚动状态:

| 位置 | 滚动状态 |
|---|---|
| `LibraryScreen.kt:227` | `key(tab, isSearching) { rememberLazyGridState() }` |
| `LibraryContentsScreen.kt:124` | `rememberLazyGridState()` |
| `CollectionDetailScreen.kt:87` / `SeriesDetailScreen.kt:81` | 未传 `state`,用组件内部默认的 saveable 状态 |

`rememberLazyGridState` / `rememberLazyListState` 内部就是 `rememberSaveable(saver = …Saver)`,
而 `NavHost` 的 `composable{}` 目的地本来就有 `rememberSaveableStateHolder` 兜着。所以**滚动位置
确实被保存了** —— 问题出在恢复的那一刻。

### 7.3 实测结论(替换掉最初的猜测)

**最初的猜测是错的,已被推翻:** §7.3 原来怀疑"`LazyGrid`/`LazyList` 恢复索引之后,第一次测量时
如果列表是空的,索引会被夹到 0 并覆盖掉恢复值"。Task 5(复现)、Task 6 各轮修复期间反复用真机
logcat 核对过多条路径(底部导航栏返回、`returnToHome`、进程回收/Activity 重建专项测试),**这个
"恢复出索引 → 首次测量为空 → 被夹到 0"的机制在任何一条可达路径上都没有被观测到过**。凡是复现出
`firstVisibleItemIndex=0` 的路径,观测到的都是"`LibraryViewModel` 被整个重建成一个全新实例,列表
从未被滚动过",不是"曾经滚到 40、恢复出 40、又被夹回 0"。进程回收(`ListScrollProcessDeathTest`)
这条本来最有希望观测到"恢复—夹紧"这一步的路径,认真尝试后确认无法复现(见
`.superpowers/sdd/2026-08-15-high-bitrate-playback-and-scroll-restore/task-5-report.md` 的
"候选 (i)"一节)。

**实测确定的真实成因是两类,都与"索引夹紧"无关:**

**(a) `returnToHome` 把 library 这个导航条目彻底销毁,索引从未被存过。** 播放序列结束后触发的
`navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }`
**不带 `saveState`**——"library"连同它上面的"detail"被直接销毁、不写入 `backStackMap`,
`ViewModelStore` 随之清空。用户再点『媒体库』得到的是一个从零加载的全新列表,`firstVisibleItemIndex`
自然是 0——这不是"恢复错了",是压根没有恢复这回事。

**(b) 这个扁平导航图上的一整族"先存后取、取的正是刚存的那份"缺陷。** `NavController`
(`androidx.navigation:navigation-compose:2.9.8`,经复审反编译 `NavControllerImpl
.executePopOperations` 确认)在 `popUpTo(X) { saveState = true }` 时,会把被弹出的那一段**写进
`backStackMap` 两次**:一次以"被弹出的最深的目的地"为键,一次以 `X`(pop 锚点自己)为键。
只要后续任何一次 `navigate(Y) { restoreState = true }` 命中了这两个键中的一个,就会把当时存的那
一整段原样恢复回来。本项目的底部导航是单张扁平图(不是每个 tab 一个嵌套 `NavGraph`),多个调用点
共用 `Routes.HOME` 当 `popUpTo` 锚点,于是同一个"`HOME` 键"在不同调用点之间反复出现"存款人"和
"取款人"错配,表现为一整族自我循环/自我覆盖的缺陷,在 Task 6 的五轮修复里逐一实测出来:
- 从 library 的子页面点回『媒体库』图标本身,逐字复刻生产 `NavOptions` 后是彻底的 no-op——
  `navigate()` 正常返回但返回栈字节级不变(Task 6 Round 0/1)。
- 底部导航栏对"当前就在自己这个 tab"的点击,原始实现整段状态被销毁,回到自己 tab 时数据重新拉取
  (Round 1 缺陷 A/Round 2 Critical 1)。
- 点『在听』tab 退回首页时,若 `HOME` 是 `popUpTo` 锚点且目标也是 `HOME`,`navigate(HOME){
  popUpTo(HOME){saveState=true}; restoreState=true}` 同一次调用里"存"和"取"互相抵消,点击没有
  任何反应(Round 4)。
- 点『在听』先把某段状态存进 `HOME` 键,`returnToHome` 随后作为这个键的取款人把它原样恢复出来,
  播放结束后没有落在首页,而是落在了刚才存的那个子页面(Round 5,`TabNavInvariantSequenceTest`
  在 161 条穷举可达序列里测出 3 条违规)。

真正命中"用户滚到 40 之后返回丢失滚动位置"这条用户可见 bug 的,是 (a)——`returnToHome` 销毁了
`library` 这个导航条目。(b) 这一整族是同一批底部导航代码里并存的、更严重的独立缺陷(点了没反应/
落错页面),不是"滚动位置丢失"的成因,但同样在这批修复里一并解决了。详细的实测日志、每一轮的
候选写法为什么不成立,见
`.superpowers/sdd/2026-08-15-high-bitrate-playback-and-scroll-restore/task-5-report.md` 与
`task-6-report.md`。

### 7.4 修法:实测结果(不再是"先复现,再定修法"的待办)

原文这里写的是执行前的流程要求("先写能失败的测试,再定修法,不允许照 §7.3 的猜测直接改代码")——
这条流程本身被完整执行了(Task 5 只复现不修,Task 6 才动代码),现按实测结果记录最终做法。

**修法方向确实如原文所猜的"让数据不为空"(保住数据),但机制不是"重新拉数据更快",而是让承载
数据的导航条目本身存活。** `app/src/main/java/dev/insua/jellycast/navigation/TabNavAction.kt`
把"点某个 tab 该做什么""播放结束回首页时哪段状态值得保留"提成两个纯函数
(`tabNavAction`、`restorableTabOwner`),配套两个执行体
(`NavController.executeTabNavAction` / `executeReturnToHome`),取代原来散落在
`JellyCastNavHost.kt` 里、各自手写 `NavOptions` 的写法。核心不变式写进了该文件顶部的 KDoc:

> 凡是会被当成 `popUpTo`/`popBackStack` 锚点、且带 `saveState = true` 的目的地(本项目里只有
> `Routes.HOME`),都不许成为 `navigate(…){ restoreState = true }` 的目标。

修完之后,全项目带 `saveState`/`restoreState` 的 `NavOptions` 组合只剩一处
(`TabNavAction.kt` 内部),`returnToHome`/底部导航栏/首页搜索入口统一改成调用同一批执行体。
守卫这条不变式的是设备测试
`app/src/androidTest/java/dev/insua/jellycast/navigation/TabNavInvariantSequenceTest.kt`
(在真实 `NavController` 上穷举深度 ≤3 的可达动作序列)——JVM 单测测不到这条:
`backStackMap` 的读写是 `NavController` 内部状态,不在两个纯函数的输入输出里。

用户报的"滚动位置丢失"由 `LibraryScreenTest`/`ListScrollRestoreTest` 里
`播放序列结束返回首页后再次进入媒体库滚动位置保持` 等用例守护——`library` 这个
`NavBackStackEntry`(连同它的 `ViewModelStore`/`SaveableStateHolder`)存活是滚动位置保持的
充分条件,全程没有触碰任何 `LazyGridState`,不是"把索引再存一份"这种绕过症状的做法。

### 7.5 范围

四个列表页全部要恢复滚动位置:媒体库(剧集/电影/合集三个 Tab 各自独立)、按库浏览、
合集详情、剧集详情。**搜索结果不算** —— 搜索结果本来就不缓存(见 `LibraryViewModel` 类 KDoc)。

---

## 8. 测试策略

### 8.1 JVM 单测(主战场,每条都必须能对着未修复代码变红)

| 目标 | 怎么测 |
|---|---|
| R1 探测超时 | MockWebServer 把**响应头**延迟 15 秒,用**生产默认超时**。旧值 → `isAudioOnly` 返回 `null`;新值 → 返回 `true`。**这条直接复现根因**,慢 15 秒可以接受。 |
| R1 连接与读取是两个独立超时 | 把两个超时做成构造参数(生产默认即 §5.1 的值),测试注入 `connect=200ms / read=1500ms`:延迟 800ms 的响应头**成功**(证明 read 生效且没被 connect 卡掉),指向黑洞地址的请求在 ~200ms 量级失败(证明 connect 没被拉长到 read 的值)。这样不必为了测超时真的等 10 秒。 |
| R3 L3 URL | 断言 `buildVideoStreamUrl` 产出的 URL 含全部六个降码率参数;变异掉任一参数测试必须红 |
| R3 不影响 L1 | 断言 L1 URL **没有**被这次改动污染 |

### 8.2 设备测试

| 目标 | 怎么测 |
|---|---|
| R2 播放器超时 | 起一个本地 HTTP server,响应头故意延迟 12 秒,断言 ExoPlayer 仍能打开该流(默认 8 秒下必失败) |
| §7 滚动位置 | 见 §7.4,复现测试本身就是回归护栏 |

### 8.3 端到端(**本次必须做,不允许再跳过**)

在模拟器上连真实服务器,播放舌尖上的中国 S4E1:

1. 断言实际走的是 **L1**(不是 L3)
2. 断言真的出声(播放位置在推进)
3. 记录首次出声耗时

用户已多次指出端到端一直是空白,而本仓库最严重的一个缺陷(缓存文件不可 seek)正是靠一次真机
探针才发现的。**这一条不做,这次修复就没有被验证过。**

---

## 9. 验收标准

- 舌尖上的中国 S4E1 能正常播放,且走 **L1**
- 普通片源(恰同学少年等)行为**完全不变**,起播不变慢
- 服务器不可达时,失败仍在 ~10 秒量级返回,不会干等 60 秒
- L3 URL 带全部降码率参数;真走到 L3 时码率在 1 Mbps 量级而不是 50 Mbps
- 进列表 → 滚动 → 进详情 → 返回:滚动位置保持(四个列表页 + 媒体库三个 Tab)
- 既有全部测试保持绿

---

## 10. 开放问题

无。§6 的"探测白起一个转码"已明确列为本次**不做**的后续议题。
