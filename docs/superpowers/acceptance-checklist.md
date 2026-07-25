# JellyCast v1 验收结果(2026-07-25)

> 对真实服务器(群晖 DS920+ / Jellyfin 10.10.7 / Tailscale `100.126.20.77:8096`)实测。
> 未实测的项目**明确标注为待验收**,不做推测性勾选。

---

## 1. 核心价值命题:流量削减 —— ✅ 实测确认

同一集(银魂 S1E6,源为 1080p H264 + AAC stereo),三种投递方式各下载 60 秒后用 `ffprobe` 测得:

| 投递方式 | 实测码率 | 视频轨 | 每小时流量 | 相对 L3 |
|---|---|---|---|---|
| **L1 @64k** | **70 kbps** | **0** | ~31 MB | **省 76.6 倍** |
| **L1 @128k**(默认) | **132 kbps** | **0** | ~59 MB | **省 40.6 倍** |
| L3 兜底(完整流) | 5362 kbps | 1 | ~2.4 GB | 基准 |

**结论:产品的核心价值主张成立且被数据证实。** 设计文档「出门在外听剧,流量消耗接近听播客
而不是看视频」的成功标准达成 —— 59 MB/小时 与主流播客(约 30–60 MB/小时)同量级。

`ffprobe` 确认 L1 输出**零视频轨**,不是"下载了视频但不解码",是服务端根本没传。

## 2. NAS 转码性能 —— ✅ 实测确认,非瓶颈

**41 倍实时速度**(15 秒内取得 619 秒音频)。源音轨本就是 AAC,J4125 做音频转码/copy 无压力。
**无需为 CPU 做任何降级设计。**

## 3. 进度双向同步 —— ⚠️ 只验证了服务端契约,**没有验证 App**(已更正)

> **这一节原来写的是"✅ 实测确认",那个结论站不住,在此更正。**
> 当时的验证是用 `curl` 手工打服务端接口做的,**全程没有经过 App**。而全支线复审(Critical 2)
> 查明:`ProgressReporter` 当时没有任何生产调用方(全仓零非 DI 引用),App **一次进度都没上报过**。
> 也就是说,原表格证明的是"Jellyfin 这几个接口按文档工作",不是"JellyCast 能同步进度"。
> 只有读方向(启动时从 `UserData.PlaybackPositionTicks` 续播)是真的在工作。

### 已验证(用 `curl` 直接打服务端,与 App 无关)

| 步骤 | 结果 |
|---|---|
| `POST /Sessions/Playing/Progress`,`PositionTicks=5000000000` | HTTP 204 |
| 回读 `GET /Items/{id}?userId=` | `PlaybackPositionTicks: 5000000000` |
| ticks→ms 换算 | `500000 ms` = 8 分 20 秒,**精确无误** |
| 出现在「继续收听」 | ✅ `GET /UserItems/Resume` 返回该集 |

结论仅限于:**服务端接口契约与 ticks 换算公式(`ms = ticks / 10_000`)是对的**,请求体字段名与
大小写核对 `docs/jellyfin-openapi.json` 无误。

### 未验证 / 当时根本不成立

| 项 | 状态 |
|---|---|
| App 真的发出 `POST /Sessions/Playing` / `/Progress` / `/Stopped` | ❌ 当时**完全没有**:`ProgressReporter` 没有生产调用方 |
| Room 补报队列被排空 | ❌ 当时 `flushPending()` 从没被调用,队列只进不出 |
| 成功标准 #3(手机听到 8:00,换电视接着看) | ❌ 当时不成立 |

### 修正后的状态(本次修复)

`PlaybackService` 现在驱动 `PlaybackProgressCoordinator`(源就绪 → start / 换集 → stop+start /
seek → progress / 每 10s 心跳 / STATE_ENDED 与 onDestroy → stop,每次源就绪顺带 `flushPending()`),
上报的位置取 `AudioPlaybackEngine.absolutePositionMs`(绝对位置)。这条链路上"什么时候上报什么"
由 11 个离线单测覆盖。

**但仍然待真机 + 凭据人工验收(见文末清单):**

- [ ] 用 App 播一集到 8:00,在 Jellyfin Web 端看到相同进度(端到端写方向)
- [ ] 断网播一段 → 恢复网络 → 确认 Room 补报队列被排空、进度补上
- [ ] 上报的位置是绝对位置(seek/续播之后不会把进度往回冲)

## 4. 模拟器 → Tailscale 可达性 —— ✅ 实测确认

从模拟器**内部**用 TCP 与 ICMP 均可达 `100.126.20.77:8096`,**无需在模拟器里安装 Tailscale**。
原因:QEMU 用户态网络下,模拟器发起的连接由宿主机代建 socket,走宿主机路由表,
而 `100.64.0.0/10` 已由 `tailscale0` 接管。

> ⚠️ 宿主机的 `http_proxy=127.0.0.1:7890` 会把 Tailscale 流量塞进代理导致超时。
> 命令行访问 NAS 必须带 `--noproxy '*'`。模拟器不继承该环境变量,不受影响。

## 5. 铁律复查 —— ✅ 全部通过

| 铁律 | 复查方式 | 结果 |
|---|---|---|
| 永不渲染视频 | 全仓 grep `media3-ui` / `PlayerView` / `setVideoSurface*` | **零真实引用**(18 处命中全是注释与 `PlayerViewModel` 子串误报) |
| 不全局关闭 TLS 校验 | 审计所有 `checkServerTrusted` 实现 | 仅 2 处:`CertificatePolicy`(系统校验优先,指纹仅在 catch 中、按 host 隔离)与 `PeerCertificateFetcher`(仅裸握手取证书供用户确认,不发 HTTP、不读响应体、不接触凭据) |
| 字幕失败不影响播放 | 单测覆盖 HTTP 失败 / 网络异常 / 二进制垃圾 / provider 抛异常 | 全部降级为空 timeline |
| 降级链保底可用 | 单测覆盖 probe 抛异常 / 返回 false | L3 恒定给出可播 URL |
| ticks 只换算一次 | grep `Ticks` / `10_000` 在 ViewModel 与 UI 层 | 零泄漏 |

## 6. 构建与测试 —— ✅

| 项 | 结果 |
|---|---|
| `./gradlew test` | **BUILD SUCCESSFUL,229 个 JVM 单测全绿**(全支线复审修正前是 179) |
| `:core:database:connectedDebugAndroidTest` | 4 个 Room 测试真机全绿 |
| `:core:designsystem:connectedDebugAndroidTest` | 5 个 Compose UI 测试真机全绿 |
| `:app:assembleDebug` | 成功 |
| `installDebug` + 冷启动 | 安装成功,logcat 无 FATAL / AndroidRuntime 崩溃 |
| 规模 | 13 个模块,105 个 Kotlin 文件,9310 行 |

---

## ⏸ 待真机 + 凭据验收(**未实测,不予勾选**)

本环境是无头模拟器且无法自动填表(系统中文 IME 会把 ASCII 标点全角化,阻断脚本化输入),
以下项目**必须在真机上人工验收**:

- [ ] 冷启动 → 添加服务器 → 登录 → 进入首页(完整 UI 走查)
- [ ] 首页点「下一集」→ 开始播放 → 迷你条出现
- [ ] 切 Tab 迷你条仍在且继续播放;点迷你条展开全屏播放页
- [ ] 锁屏出现媒体控制卡片,可暂停 / 切集
- [ ] **锁屏拖动进度条**(验证 seek 重新 resolve 的链路,这是 `Accept-Ranges: none` 下的关键路径)
- [ ] 蓝牙耳机播放/暂停键、**上一首键**(验证 `seekToPrevious` 拦截)
- [ ] 拔出有线耳机自动暂停
- [ ] 后台 30 分钟不被系统杀
- [ ] 歌词随播放滚动、当前行居中高亮、点击行跳转
- [ ] 手动滚动歌词后 3 秒恢复自动跟随
- [ ] 倍速、睡眠定时(含**「播完本集」**模式)生效
- [ ] 自动连播下一集,且连播后**锁屏 seek 仍指向新的一集**(引擎状态同步)
- [ ] 自动连播换集后**封面不变空白**(复审 Important 4:NextUp 补充的条目要带 `imageTag`)
- [ ] **从中途续播一集,歌词第一帧就对准当前音频**(复审 Critical 1:绝对位置)
- [ ] 续播后按快进 30s 是往前 30 秒,不是跳回开头附近(播放页按钮 + 锁屏 + 蓝牙三条路各验一次)
- [ ] 播放页进度条显示的总时长是这一集的真实时长(不是钉在 100%)
- [ ] 暂停 → 从最近任务划掉 App → 再打开 → **还能正常播放**(复审 Important 3:播放器没被释放)
- [ ] 设置里改倍速,正在播放的内容**立刻变速**;杀进程重开后倍速被记住(复审 Important 5)
- [ ] 设置里关掉「歌词式字幕」,播放页不再滚字幕(复审 Minor 6)
- [ ] 从家里 WiFi 切到移动网络,自动切 endpoint 且续播
- [ ] `android:exported="false"` 是否影响系统媒体发现(若失效则改回 `true`)

## ⏸ 待公网域名验收

Spike-3 未完成 —— 缺 DDNS 域名。Tailscale 侧 8920 端口不可达。待补测:

- [ ] 移动网络下 IPv6 直连群晖是否连通
- [ ] 证书链在 Android 上是否受信
- [ ] IPv6 方括号地址(`https://[240e::1]:8920`)端到端可用
- [ ] 自签证书场景:指纹确认流程、**封面图能否加载**(Coil 已共享信任配置)、
      **L1 探测能否成功**(探测 client 已配信任策略,否则会静默降级到 L3)

---

## 已知取舍(非缺陷,已评估并接受)

| 项 | 说明 |
|---|---|
| `accessToken` 明文存于 DataStore | v1 接受;设计文档未要求加密 |
| 不保存密码 | 401 时直接跳登录页,不做静默重认证(与设计文档 §8 冲突,以不存密码为准) |
| 音频码率 / serverId 在 DI 建图时快照 | 改动需重启进程 |
| 自签证书信任表为启动时快照 | 新确认的证书对 Coil / 字幕需重启生效(API 路径不受影响) |
| 自动连播时 `resolve()` 被调两次 | 每次切集多一次网络请求,换取引擎状态强一致 |
| `@Singleton ExoPlayer` 不随 Service 释放 | 复审 Important 3 的决定:Service 销毁只 `stop()` + `clearMediaItems()` + 释放 MediaSession,不 `release()` 播放器。宁可留一个空闲播放器,也不要留一个已释放的死对象(否则重开 App 必崩在 prepare 上,还被静默转成「该条目无法播放」)。真正回收交给进程结束 |
| 进程被系统直接杀掉时最后一次 `stop` 上报会丢 | 10 秒心跳保证 Jellyfin 里的进度最多落后 10 秒 |
| 每次 seek 都重新 resolve → 服务端起一次新转码 | `Accept-Ranges: none` 下没有别的选择;拖动进度条已经做成"只在松手时 seek 一次"来抑制抖动 |
| 音轨选择在 L1 不可用 | `/Audio/{itemId}/universal` 没有 `audioStreamIndex` 参数(已核对 OpenAPI),见设计文档 §3.5 的边界说明。不为了这个功能默认降级到 L3 |
| TOFU 残余风险 | 若攻击者恰在用户确认证书的那一刻 MITM,其证书会被固定。UI 已显示指纹与 URL 供用户核对 |
