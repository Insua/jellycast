package dev.insua.jellycast.player

/**
 * L1/L3 流共用的 HTTP 超时,一共三处消费方(Finding 2 复审补全:之前这里写「探测与播放两处」,
 * 漏了缓存下载):
 * 1. **探测** —— [HttpStreamProbe]。
 * 2. **播放** —— ExoPlayer 自己的 HTTP 数据源(`JellyCastPlayerFactory`)。
 * 3. **缓存下载** —— `AudioCacheDownloader`(经 `PlayerModule.cacheDownloadHttpClient` 派生;
 *    `:core:cache` 模块本身不认识这两个常量,派生逻辑放在能同时看到两边的 `:core:player`)。
 *
 * 三处打的是同一条 `/Audio/{id}/universal` URL,同一台服务器上遇到的响应头延迟是同一个分布,
 * 因此共用同一组常量——不是巧合,是同一个根因在三个不同调用路径上分别复现。
 *
 * 见 `docs/superpowers/specs/2026-08-15-high-bitrate-playback-and-scroll-restore-design.md` §5.1/§5.2。
 *
 * ## 为什么必须是两个值而不是一个
 *
 * 连接超时和读取超时在这条链路上语义完全不同:
 * - **连不上** = 服务器不可达,应当**快速失败**,让降级/报错早点发生,不要让用户干等。
 * - **连上了但半天不吐字节** = 服务端正在为一个大文件起转码,这**不是**故障。
 *   MPEG-TS 没有全局索引,ffmpeg 必须先扫描;实测同一台服务器上
 *   1080p h264 mkv 的首字节 1.2–2.4 秒,而 4K HEVC 50fps 的 `.ts` 要 6.3–46.1 秒。
 *
 * 把两者混成一个值,要么在死服务器上白等一分钟,要么把慢转码误判成故障 —— 后者正是
 * 本次修复的那个 bug:OkHttp 默认两个超时都是 10 秒,恰好卡在实测分布中间,
 * 于是同一部剧的不同集时而走 L1 时而掉进 L3。
 *
 * 60 秒覆盖实测最坏值 46.1 秒并留余量。
 */
const val STREAM_CONNECT_TIMEOUT_MS: Int = 10_000

/** 见 [STREAM_CONNECT_TIMEOUT_MS]。 */
const val STREAM_READ_TIMEOUT_MS: Int = 60_000
