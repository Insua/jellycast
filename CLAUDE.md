# CLAUDE.md — JellyCast

本文件是本仓库的开发指令。**开始任何工作前先完整读完。**

---

## 这是什么

**JellyCast:一个 Android 应用,把自建 Jellyfin 服务器上的剧集和电影,当成播客来"听"。**

UI 是音乐/播客播放器形态(对标小宇宙、Spotify),内核连的是 Jellyfin,**全程不渲染视频画面**。字幕以**歌词形式**随播放滚动显示。

---

## 立刻要做的事

1. 读设计文档:`docs/superpowers/specs/2026-07-25-jellycast-design.md`
2. 读实现计划:`docs/superpowers/plans/2026-07-25-jellycast-implementation.md`
3. **调用 superpowers 技能执行计划**:
   - 推荐 `superpowers:subagent-driven-development`(每个 Task 派一个新 subagent,任务间审查)
   - 或 `superpowers:executing-plans`(在当前会话内批量执行 + 检查点)
4. **必须先完成 Task 0(Spike 技术验证)**,它是后续所有播放逻辑的前提。在拿到 Spike 结论前不要开始 Phase 2。

计划中的每个 Task 都是 TDD 循环:**写失败的测试 → 跑,确认失败 → 最小实现 → 跑,确认通过 → commit**。不要跳过"确认失败"这一步。

---

## 铁律(违反即返工)

1. **永远不渲染视频。** 不绑定 `Surface`,不创建 `PlayerView`。播放页显示的是海报封面。
2. **禁止凭记忆写 Jellyfin API。** 每个接口调用前,必须核对 `docs/jellyfin-openapi.json`(Task 0 中下载)。计划文档里写的接口签名是**待核验的草稿**,不是权威。
3. **降级链必须保底可用。** L3(客户端禁用视频轨)是确定可行的兜底路径。L1/L2 是优化,失败必须静默降级,不得让用户看到错误。
4. **字幕失败不得影响播放。** 任何字幕相关异常都降级为"无字幕",绝不向上抛错。
5. **不允许全局关闭 TLS 校验。** 自签证书只能按用户确认过的指纹白名单信任。
6. **核心逻辑必须可离线单测。** 选路、降级决策、字幕解析、歌词行定位都是纯 Kotlin,不依赖真实服务器。
7. **每个 Task 结束必须 commit**,用 Conventional Commits(`feat:` / `fix:` / `test:` / `chore:` / `docs:`)。

---

## 部署环境(影响设计决策)

| 项 | 值 |
|---|---|
| 服务器 | 群晖 **DS920+**,Intel **J4125**,带 UHD 600 核显(支持 QSV) |
| Jellyfin | 矿神第三方套件源安装 |
| 接入方式 | **Tailscale 内网** + **公网 HTTPS(DDNS + IPv6)** |

**关键推论:** 出门走公网时瓶颈是**家宽上行带宽**。所以"服务端只输出音频"(L1)不是锦上添花,是这个产品的核心价值——能把流量从几 Mbps 砍到 ~100 Kbps。

---

## 技术栈

Kotlin · Jetpack Compose + Material 3 · **Media3(ExoPlayer + MediaSessionService)** · Retrofit + OkHttp · kotlinx.serialization · Hilt · Room · DataStore · Coil · JUnit5 + MockK + Turbine

- **minSdk 26,targetSdk 35,JVM target 17**
- 依赖版本统一在 `gradle/libs.versions.toml`,**不要在模块里写死版本号**

---

## 模块结构

```
:app                  入口、导航、依赖装配
:core:model           纯数据模型(无 Android 依赖,可纯 JVM 单测)
:core:network         Jellyfin API、认证、多地址选路、证书策略
:core:database        Room:进度补报队列
:core:datastore       DataStore:服务器列表、用户偏好
:core:player          播放引擎、降级链、MediaSession、播放队列、进度上报
:core:subtitle        字幕拉取与解析(SRT/VTT/ASS)
:core:designsystem    主题、迷你播放条、封面卡
:feature:server       服务器管理与登录
:feature:home         "在听"首页
:feature:library      剧集/季/集/电影浏览
:feature:player       全屏播放页 + 歌词视图
:feature:settings     设置
```

**边界原则:** 每个模块单一职责,对外只暴露 interface。`:core:player` **不认识 Jellyfin API**,只接受已解析好的播放 URL + 元数据。

---

## 常用命令

```bash
# 编译
./gradlew :app:assembleDebug

# 跑某个模块的单测
./gradlew :core:subtitle:test
./gradlew :core:network:test --tests '*EndpointSelectorTest*'

# 跑全部 JVM 单测
./gradlew test

# 跑需要设备的测试(Room / Compose UI)
./gradlew connectedDebugAndroidTest

# 安装到真机
./gradlew installDebug

# 查看当前播放是否创建了视频解码器(应为无)
adb shell dumpsys media.metrics | grep -i codec
```

---

## 三个最容易做错的地方

**1. 把字幕交给播放器渲染。**
错。因为走纯音频流,字幕轨**不在流里**。字幕必须**独立 HTTP 拉取 → 自己解析 → 自己渲染成歌词 UI**。这样在 L1/L2/L3 任何级别下都能工作。

**2. 把"多服务器"和"多接入地址"搞混。**
这是两层:一台 **Server** 有多个 **Endpoint**(局域网 / Tailscale / 公网)。连接时**并发探测所有 endpoint,取最先成功的**。用户在家自动走局域网,出门自动走公网,零操作。

**3. Jellyfin 的时间单位是 ticks。**
1 tick = 100 纳秒,`毫秒 = ticks / 10_000`。**只在 DTO→Model 映射层换算一次**,UI 层和业务层禁止出现 ticks。

---

## v1 范围边界

**做:** 多服务器 + 多地址自动选路 / 剧集 + 电影 / 纯音频三级降级 / 歌词式字幕 / 后台播放与锁屏控制 / 倍速 · 睡眠定时 · 快进快退 / 自动连播 / 音轨选择 / 进度双向同步

**不做(不要自作主张加进来):** 离线下载 · 搜索 · 视频画面 · 音乐/有声书库 · 投屏 · 收藏夹 · 硬字幕 OCR

---

## 遇到问题时

- **Spike 结论与计划冲突** → 以 Spike 实测为准,更新计划文档和设计文档,并说明改动原因。
- **Jellyfin API 与计划中的签名不符** → 以 `docs/jellyfin-openapi.json` 为准,修正计划。
- **某个 Task 做不下去** → 停下来说明卡在哪、试过什么,不要猜着往下写。
