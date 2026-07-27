# Spike 结论(2026-07-25)

> 对真实服务器实测得出。**本文档是 Phase 2 播放内核的实现依据,优先于设计文档与实现计划中的任何推测。**

## 测试环境

| 项 | 值 |
|---|---|
| 服务器 | `http://100.126.20.77:8096`(Tailscale `nas` 节点) |
| Jellyfin 版本 | **10.10.7**(OpenAPI 319 个接口,已存 `docs/jellyfin-openapi.json`) |
| 硬件 | 群晖 DS920+ / Intel J4125 |
| 测试样本 | 银魂 S1E6,`0007d2ad93b90d3276dc4e64d97ac558` |
| 样本媒体轨 | Video h264 1080p / Audio **AAC** stereo / Subtitle **ASS 文本轨**(idx=2) |
| 样本原始码率 | **5 571 529 bps(5.57 Mbps)** |

> 客户端注意:本机 shell 有全局 `http_proxy`,访问 Tailscale 内网的 curl 必须带 `--noproxy '*'`,
> 否则请求被塞进代理并超时。此坑与服务器无关,但会浪费大量排查时间。

---

## Spike-1 纯音频流

### 结论:**L1 可行。降级链起始级别 = L1。**

**但可用接口不是计划里猜的那个。** 计划推测的 `/Videos/{id}/stream.mp3` 与
`/Videos/{id}/stream?audioCodec=aac` 都不成立;真正可用的是**把音频接口用在视频条目上**:

```
GET /Audio/{itemId}/universal
    ?api_key={token}
    &mediaSourceId={mediaSourceId}
    &audioCodec=aac
    &audioBitRate={目标码率}
    &maxAudioChannels=2
```

### 四个候选的实测结果

| 候选 | 结果 | ffprobe 轨道 | 判定 |
|---|---|---|---|
| `GET /Videos/{id}/stream.mp3` | **HTTP 500** `Error processing request.` | — | ❌ |
| `GET /Videos/{id}/stream?audioCodec=aac&static=false` | HTTP 200,`video/x-matroska`,98 MB | audio **+ video** | ❌ 视频轨仍在 |
| `GET /Videos/{id}/master.m3u8` | HTTP 200,但只有一条 1080p 混流 | — | ❌ 无独立音频 rendition |
| **`GET /Audio/{id}/universal?audioCodec=aac`** | HTTP 200,`audio/aac` | **仅 audio** | ✅ |

### 码率控制有效(这是本项目省流量的关键)

`audioBitRate` 参数实测生效:

| 请求 `audioBitRate` | 实测输出码率 | 相对原始 5.57 Mbps |
|---|---|---|
| 不传 | 257 679 bps | 省 21.6 倍 |
| `128000` | **132 093 bps** | **省 42 倍** |
| `64000` | **70 827 bps** | **省 78 倍** |

**建议默认值 128 kbps**(约 58 MB/小时),移动网络受限时可降到 64 kbps(约 32 MB/小时)。
对照原始视频流约 2.5 GB/小时 —— 这就是本产品的核心价值,已被数据证实。

### NAS 转码性能:非瓶颈

实测**约 41 倍实时速度**(15 秒内取得 619 秒音频)。源音轨本就是 AAC,J4125 做音频转码/copy
毫无压力。**无需为 CPU 做任何降级设计。**

> NAS 的 SSH(22)与 8920 端口在 Tailscale 侧均不可达,故未能直接读取 ffmpeg 进程 CPU;
> 以 41x 实时吞吐作为等价证据。

### ⚠️ 最重要的架构约束:转码流不支持 Range 请求

```
Accept-Ranges: none
```

带 `-r 0-1000` 的请求返回 **HTTP 200 而非 206**,服务器直接忽略 Range。含义:

- **ExoPlayer 无法通过字节范围 seek。**
- **seek 必须实现为「用新的 `startTimeTicks` 重新发起请求」**,即换一个 MediaItem 重新 prepare。
- 实测 `startTimeTicks=6000000000`(600 秒处)返回 HTTP 200,该参数可用。
- 断点续播同理:从 `resumePositionMs * 10_000` 换算出 ticks 作为起始点请求。

**这一条直接决定 `:core:player` 的设计,Task 8/9 必须据此实现,不可沿用"给播放器一个 URL 然后 seekTo"的思路。**

### L2(HLS 音频 rendition):**不可行,从降级链中移除**

- `GET /Audio/{id}/master.m3u8` → **HTTP 500**
- `GET /Videos/{id}/master.m3u8` → HTTP 200,但内容只有一条混流:
  `BANDWIDTH=256000 ... RESOLUTION=1920x1080`,**零个 `EXT-X-MEDIA:TYPE=AUDIO` 独立音频 rendition**

### 修订后的降级链

| 级别 | 做法 | 状态 |
|---|---|---|
| **L1** | `GET /Audio/{itemId}/universal?audioCodec=aac&audioBitRate=…` | ✅ **实测可用,默认路径** |
| ~~L2~~ | ~~HLS 音频 rendition~~ | ❌ **实测不存在,删除** |
| **L3** | `GET /Videos/{itemId}/stream?static=true` + 客户端 TrackSelector 禁用视频轨 | 保留为兜底 |

**降级链从三级简化为两级。** 计划文档 §3.1 与 `AudioDeliveryLevel` 枚举中的
`HLS_AUDIO_RENDITION` 应予删除(或保留枚举值但标注为永不命中)。

---

## Spike-2 字幕

### 结论:**可行。四种格式全部返回 HTTP 200。**

接口路径与计划一致:

```
GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}?api_key={token}
```

| 格式 | 大小 | 说明 |
|---|---|---|
| `srt` | 26 KB | 毫秒精度 `00:00:01,700 --> 00:00:02,590`,388 条 |
| `vtt` | 34 KB | 带 `Region:` 头与 `region:… line:90%` cue 参数 |
| `ass` | 39 KB | 原始格式 |
| `js` | 56 KB | JSON:`{"TrackEvents":[{"Id","Text","StartPositionTicks","EndPositionTicks"}]}` |

- **时间轴精度:毫秒**(srt/vtt);`js` 格式是 **ticks**,需 `/10_000` 换算。
- 位图字幕识别字段:`MediaStreams[].IsTextSubtitleStream`(本样本 ASS 轨为 `true`)。

### ⚠️ 三个必须在解析器里处理的真实数据坑

这些是从**真实字幕文件**中发现的,不是假想:

1. **ASS 特效标签未被剥离。** Jellyfin 转出的 `srt` 与 `js` 中**都残留 11 处** `{\...}`:
   ```
   你{\fn方正准圆_GBK}啰{\r}里{\fn方正准圆_GBK}啰{\r}嗦个头 快放老子出去
   {\pos(352,483)}（处女大碟播放禁止之后半年)
   ```
   **`SrtParser` 必须同时剥离 HTML 标签和 `{...}` 花括号标签。**
   计划中 `SrtParser` 只剥离 `</?[a-zA-Z][^>]*>`,**不够**,会把 `{\pos(352,483)}` 直接显示给用户。
   (计划中 `AssParser` 已有 `EFFECT = Regex("""\{[^}]*}""")`,把它复用到 SRT/VTT 即可。)

2. **存在 5 条零时长条目**(`00:00:00,000 --> 00:00:00,000`),内容是
   `=============正文=========` 这类制作组标记。`SubtitleTimeline.indexAt(0)` 会命中它并高亮。
   建议解析阶段**过滤掉 `endMs <= startMs` 的条目**。

3. **文件带 UTF-8 BOM**(`﻿`)。当前基于时间戳正则定位的解析方式不受影响,但
   若改为按行号解析会出错。VTT 的 `Region:` 头块也需跳过(现有实现因找不到时间戳而自然跳过,可接受)。

### 格式选型建议

推荐 **`srt`**:体积最小、解析最简单、毫秒精度已足够。
`js` 虽结构化但体积翻倍且仍需剥离特效标签,收益不足。

---

## Spike-3 公网 IPv6 + HTTPS

### 结论:**未完成 —— 缺少公网域名。**

- Tailscale 侧 **8920 端口不可达**(仅 8096 开放)。
- 设计文档 §2 提到「公网 HTTPS,DDNS + IPv6」,但**具体 DDNS 域名未提供**,无法测试。
- 亦未在移动网络环境下验证 IPv6 直连。

**待办:** 需要用户提供 DDNS 域名后补测,内容包括:是否连通、证书是否受 Android 信任、是否实际走 IPv6。

**对开发的影响:有限。** 多地址选路(Task 4)与证书指纹白名单(Task 5)的实现不依赖此结论,
只需保证公网 endpoint 探测失败时能正确降级到其他 endpoint。可先按计划实现,待域名到位后做端到端验收(Task 23)。

---

## 对计划文档的影响汇总

| 项 | 变更 |
|---|---|
| 设计文档 §3.1 | 降级链三级 → **两级**,L1 接口改为 `/Audio/{id}/universal` |
| 设计文档 §9 | Spike-1 / Spike-2 完成,Spike-3 待公网域名 |
| `AudioDeliveryLevel` 枚举(Task 2 已实现) | `HLS_AUDIO_RENDITION` 永不命中 |
| Task 8 `PlaybackSourceResolver` | L1/L2 URL 模板全部重写;L2 分支删除 |
| Task 9 `JellyCastPlayerFactory` | 必须支持「seek = 带新 `startTimeTicks` 重新 prepare」 |
| Task 13 `SrtParser` | 必须增加剥离 `{...}`;必须过滤零时长条目 |
