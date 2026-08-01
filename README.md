<div align="center">

# JellyCast

**把 Jellyfin 服务器上的剧集和电影,当成播客来「听」。**

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-26-blue)](https://developer.android.com/tools/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)](https://developer.android.com/jetpack/compose)
[![Jellyfin](https://img.shields.io/badge/Jellyfin-10.10%2B-00A4DC?logo=jellyfin&logoColor=white)](https://jellyfin.org)

中文 · [English](README.en.md)

</div>

---

## 这是什么

JellyCast 是一个 Android 客户端,界面是播客 / 音乐播放器的形态,内核连的是你自建的 **Jellyfin** 服务器。它**全程不渲染视频画面** —— 播放页显示的是海报封面,字幕以**歌词形式**随播放滚动。

### 为什么要有它

NAS 上囤了大量剧集,但其中很大一部分 —— 脱口秀、纪录片、访谈、日常番、听过很多遍的老剧 —— **只听声音就够了**。

官方客户端是视频播放器形态:要盯着屏幕,锁屏就停,耗电,而且在外网下要传完整的视频流。

JellyCast 把这件事翻过来:**它假设你不看画面**,于是可以做官方客户端做不到的事 —— 让服务端只转码音频。同一集内容,视频流要几 Mbps,纯音频流约 **100 Kbps**。出门在外时,瓶颈是家里宽带的**上行**带宽,这一个数量级的差别决定了能不能听得流畅。

---

## 功能

**播放**

- 🎧 **纯音频串流** —— 服务端只转码音频轨,不传视频字节
- 🎵 **字幕即歌词** —— 文本字幕像 Apple Music 歌词那样滚动高亮
- 🔒 **后台播放** —— 锁屏、通知栏、蓝牙耳机、车机控制
- ⏩ **播客三件套** —— 倍速(0.5×–3.0×)、睡眠定时、可配置的快进快退秒数
- ▶️ **按剧集顺序自动连播** —— 一季播完接下一季,整部播完回到首页
- 🎚️ **音轨选择**(仅在 L3 降级路径下可用)

**内容与同步**

- 🌐 **多服务器 + 多接入地址** —— 一台服务器可配局域网 / Tailscale / 公网多个地址,连接时**并发探测、最先成功的胜出**。在家自动走局域网,出门自动走公网,零操作
- ↔️ **进度双向同步** —— 手机听到一半,电视上接着看
- 📚 **媒体库浏览 + 分页 + 搜索** —— 面向真实体量的库(开发时的测试库有 8000+ 集)
- ⭐ **收藏 / 标记已播放**
- 📴 **离线缓存** —— 缓存优先 + 后台刷新,断网打开看到的是上次的内容而不是白屏
- 🔄 **首页静默刷新** —— 回到首页或切回前台时自动同步「继续收听 / 下一集」

**其它**

- 🩺 **诊断日志导出** —— 落盘到应用私有目录,一键分享,不记录任何凭据
- 🔐 **自签证书支持** —— 按用户确认过的指纹白名单信任,**不做全局 TLS 关闭**

---

## 它是怎么工作的

这一节记录几个不那么显然的设计决定 —— 它们解释了这个项目为什么长成现在这样。

### 音频降级链

播放一个条目时,按顺序尝试:

| 级别 | 做法 | 效果 |
|---|---|---|
| **L1** | 请求 `/Audio/{id}/universal`,由服务端转码成纯音频 | 约 100 Kbps,服务端不传视频字节 |
| **L3** | 请求 `/Videos/{id}/stream`,在客户端的 `TrackSelector` 里禁用视频轨 | 确定可行的兜底路径 |

L1 失败必须**静默降级**,不能让用户看到错误。

> **为什么没有 L2。** 最初的设计里有一级 L2:从 HLS 主播放列表里挑出纯音频 rendition。Spike 阶段实测证明 Jellyfin **不提供**这样的 rendition,于是降级链从三级改成了两级。这是这个项目「以实测为准、不以计划为准」的第一个例子。

### 转码流不支持 Range 请求,所以 seek 不是 seek

服务端的转码流返回 `Accept-Ranges: none`,这意味着 `player.seekTo()` 在这条流上**不可靠**。

JellyCast 的做法是:每一次 seek 都**带着新的 `startTimeTicks` 重新解析 URL 并重新 prepare**。相应地,播放器报的是「转码流内的相对位置」,而条目内的**绝对位置**由播放引擎单独维护 —— 锁屏进度条、歌词定位、进度上报读的都是后者。

### 字幕必须自己拉、自己解析、自己渲染

因为走的是纯音频流,**字幕轨不在流里**。所以字幕是独立 HTTP 拉取 → 自己解析(SRT / VTT / ASS)→ 自己渲染成歌词 UI。这样在任何降级级别下都能工作。

字幕的任何失败都降级为「无字幕」,**绝不影响播放**。

### 「多服务器」和「多接入地址」是两层

一台 **Server** 拥有多个 **Endpoint**(局域网 / Tailscale / 公网)。连接时并发探测所有 endpoint,取最先成功的那个。这就是「在家和在外都不用手动切」的实现方式。

### 时间单位

Jellyfin 的时间单位是 **ticks**(1 tick = 100 纳秒)。换算**只在 DTO → Model 的映射层做一次**,业务层和 UI 层不出现 ticks。

---

## 环境要求

| | |
|---|---|
| 设备 | Android 8.0(API 26)及以上 |
| 服务器 | Jellyfin 10.10 或更新(在 10.10.7 上测试) |
| 构建 | JDK 17 · Android SDK 36 · Gradle 9.5(随 wrapper 提供) |

---

## 构建

```bash
git clone https://github.com/<your-account>/jellycast.git
cd jellycast

# Debug 包
./gradlew :app:assembleDebug

# 安装到已连接的设备
./gradlew installDebug
```

### 测试

```bash
./gradlew test                              # 全部 JVM 单测
./gradlew :core:subtitle:testDebugUnitTest  # 单个模块
./gradlew connectedDebugAndroidTest         # 需要设备 / 模拟器
```

端到端测试需要一台**真实的 Jellyfin 服务器**。把 `testing.properties.example` 复制成 `testing.properties` 并填好地址与账号 —— 该文件已被 gitignore,缺失时端到端测试会被 `Assume` **跳过**而不是失败。

### 发布签名

keystore 与密码**永远不进版本库**。构建脚本按下面的顺序读取,读不到就产出**未签名包**而不是构建失败:

1. 环境变量 `JELLYCAST_STORE_FILE` / `JELLYCAST_STORE_PASSWORD` / `JELLYCAST_KEY_ALIAS` / `JELLYCAST_KEY_PASSWORD`(CI 用)
2. 项目根的 `keystore.properties`(本机用,已 gitignore,模板见 `keystore.properties.example`)

```bash
./gradlew signingStatus        # 查看当前是否配了签名,不泄露密码
./gradlew :app:assembleRelease
```

---

## 项目结构

```
:app                  入口、导航、依赖装配
:core:model           纯数据模型(无 Android 依赖,可纯 JVM 单测)
:core:network         Jellyfin API、认证、多地址选路、证书策略
:core:database        Room:离线缓存、进度补报队列
:core:datastore       DataStore:服务器列表、用户偏好
:core:player          播放引擎、降级链、MediaSession、播放队列、进度上报
:core:subtitle        字幕拉取与解析(SRT / VTT / ASS)
:core:diagnostics     诊断日志
:core:designsystem    主题、迷你播放条、封面卡
:feature:server       服务器管理与登录
:feature:home         「在听」首页
:feature:library      剧集 / 季 / 集 / 电影浏览
:feature:player       全屏播放页 + 歌词视图
:feature:settings     设置
```

**边界原则:** 每个模块单一职责,对外只暴露 interface。`:core:player` **不认识 Jellyfin API**,它只接受已经解析好的播放 URL 和元数据。

选路、降级决策、字幕解析、歌词行定位这些核心逻辑都是纯 Kotlin,可以脱离真实服务器单测。

---

## 技术栈

Kotlin 2.4.10 · Jetpack Compose + Material 3 · **Media3(ExoPlayer + MediaSessionService)** · Retrofit + OkHttp · kotlinx.serialization · Hilt · Room · DataStore · Coil · JUnit 5 + MockK + Turbine

---

## 状态

**可用。** 核心功能已实现并在真机上日常使用,当前版本 `0.1.0`。

**明确不做**(短期内):离线下载 · 视频画面 · 音乐 / 有声书库 · 投屏 · 硬字幕 OCR

---

## 许可

**尚未选定许可证。** 在补上 `LICENSE` 文件之前,默认保留所有权利。

---

## 致谢

- [Jellyfin](https://jellyfin.org) —— 这个项目存在的前提
- [Media3 / ExoPlayer](https://github.com/androidx/media)
- 交互形态参考了 [小宇宙](https://www.xiaoyuzhoufm.com) 与 Spotify 的播客播放器
