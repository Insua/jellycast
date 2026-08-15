package dev.insua.jellycast.player

/**
 * L1/L3 流(探测与播放两处)共用的 HTTP 超时。见
 * `docs/superpowers/specs/2026-08-15-high-bitrate-playback-and-scroll-restore-design.md` §5.1/§5.2。
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
