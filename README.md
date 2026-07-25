# JellyCast

把自建 **Jellyfin** 服务器上的剧集和电影,当成**播客**来听的 Android 应用。

UI 是音乐/播客播放器形态,内核连的是 Jellyfin,**全程不渲染视频画面**;字幕以**歌词形式**随播放滚动。

## 为什么

NAS 上囤了大量剧集,但很多内容(脱口秀、纪录片、访谈、日常番)其实**只听声音就够了**。官方客户端是视频播放器形态——要盯屏幕、锁屏就停、费电费流量。

JellyCast 把这件事翻转过来。

## 核心特性

- 🎧 **纯音频播放** — 三级降级链,尽可能只传音频,把流量从几 Mbps 砍到 ~100 Kbps
- 🎵 **字幕即歌词** — 文本字幕像 Apple Music 歌词一样滚动高亮,点击行可跳转
- 🌐 **多地址自动选路** — 一台服务器配局域网 / Tailscale / 公网多个地址,连接时自动选可达最快的那个
- 🖥️ **多服务器** — 支持添加多台 Jellyfin
- 🔒 **后台播放** — 锁屏、通知栏、蓝牙耳机、车机控制
- ⏩ **播客三件套** — 倍速、睡眠定时、快退 15s / 快进 30s
- ↔️ **进度双向同步** — 手机听到一半,电视上接着看

## 文档

| 文档 | 说明 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | 开发指令(AI 与人类都从这里开始) |
| [`docs/superpowers/specs/2026-07-25-jellycast-design.md`](docs/superpowers/specs/2026-07-25-jellycast-design.md) | 设计文档 |
| [`docs/superpowers/plans/2026-07-25-jellycast-implementation.md`](docs/superpowers/plans/2026-07-25-jellycast-implementation.md) | 实现计划(逐任务 TDD) |

## 技术栈

Kotlin · Jetpack Compose · Material 3 · Media3 (ExoPlayer) · Retrofit · Hilt · Room · DataStore

## 状态

🚧 设计与计划已完成,尚未开始实现。从 **Task 0(Spike 技术验证)** 开始。
