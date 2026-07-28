package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.JellyfinApi
import kotlinx.coroutines.CancellationException

/**
 * 探测某个 URL 实测吐出的流是否为"纯音频"(不含视频轨)。
 *
 * 三态,`null` 是**没问出结论**(服务端非 2xx、网络异常……),不是"不是纯音频"。
 * 这个区分是必须的:调用方要按媒体源缓存判定,而**只有有结论的判定才可以被记住**。
 * 把"J4125 上并发转码打架时回了个 500"记成"这个源不是纯音频",等于一次瞬时抽风就把整个会话
 * 钉死在 L3(~42 倍带宽)且用户毫无感知 —— 本产品定义的最坏静默降级。
 *
 * [kotlinx.coroutines.CancellationException] 以外的异常都不得向上抛出:探测失败必须表现为 `null`,
 * 由调用方兜底到 L3(铁律 3)。
 */
interface StreamProbe {
    suspend fun isAudioOnly(url: String): Boolean?
}

/**
 * 两级音频降级链决策器 —— JellyCast 的技术核心。
 *
 * 依据 docs/superpowers/specs/2026-07-25-spike-results.md 的实测结论:
 * - L1 `GET /Audio/{itemId}/universal`(把音频接口用在视频条目上,不是 `/Videos/`):
 *   服务端直接转出纯音频,audioBitRate 参数实测生效,128 kbps 相对原始 5.57 Mbps 视频流省 42 倍流量。
 * - L2(HLS 独立音频 rendition)实测不存在,已从降级链中彻底删除
 *   (`/Audio/../master.m3u8` 500;`/Videos/../master.m3u8` 只有一条 1080p 混流)。
 * - L3 `GET /Videos/{itemId}/stream.mkv`(**不带 `static`**):兜底,拉完整流由客户端 TrackSelector
 *   禁用视频轨。L3 必须永远给出可播 URL(项目铁律:降级链必须保底可用)。
 *   `static=true` 已被移除 —— 它让服务端静默忽略 `startTimeTicks`,L3 上的 seek 因此是空操作;
 *   容器强制 mkv —— 不指定时 MP4 源会 remux 成 moov 在末尾的 MP4,chunked 流式播放器根本读不了。
 *   两条都是对真实服务器实测出来的,证据见 [buildVideoStreamUrl] 的 KDoc。
 *
 * 转码流不支持 Range 请求(`Accept-Ranges: none`),所以 seek / 断点续播不能靠字节 range,
 * 必须换成带新的 `startTimeTicks` 重新发起请求。`ticks = ms * 10_000`,这个换算只在这一层做一次,
 * 绝不让 ticks 逃逸到上层。
 */
class PlaybackSourceResolver(
    private val api: JellyfinApi,
    private val streamProbe: StreamProbe,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val audioBitRateBps: Int = DEFAULT_AUDIO_BIT_RATE_BPS,
) {
    /**
     * ## 稳定性根因 #3:L1 判定按媒体源缓存
     *
     * seek 只能实现成"带新的 `startTimeTicks` 重新 resolve"(转码流 `Accept-Ranges: none`),
     * 而 [HttpStreamProbe] 每次 resolve 都对 L1 候选 URL 发一次**完整 GET**:
     * **用户每按一次快进,群晖 J4125 上就多起一个转码任务,读完响应头就被丢弃。**
     * 连点几下快进 = 几个孤儿转码在那颗 4 核赛扬上和正在听的那一条抢 CPU —— 用户报的
     * "seek/快进卡死、播放中断"就是这么来的。顺带还白花一整个网络往返,把 seek 拖慢一倍。
     *
     * 而探测结论**和起始位置无关**:「`/Audio/{item}/universal` 在这个 mediaSource 上吐不吐纯音频」
     * 是媒体源自身的属性。所以按 (itemId, mediaSourceId) 记住,同一条流后续所有 seek 直接复用。
     *
     * 用 itemId + mediaSourceId 而不是只用 mediaSourceId:mediaSourceId 是服务端给的标识,
     * 不同条目撞号时宁可多探测一次,也不要拿错的判定去播另一个条目。
     *
     * 键是进程内缓存,不落盘:服务端换了转码配置(装了/掉了 QSV)重启 App 就重新探测,
     * 不需要任何失效逻辑。
     */
    private val audioOnlyByMediaSource = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    suspend fun resolve(itemId: String, userId: String, startPositionMs: Long = 0L): PlaybackSource {
        val info = api.playbackInfo(itemId, userId)
        val ms = info.mediaSources.firstOrNull()
            ?: error("item $itemId has no media source")

        val base = baseUrlProvider().trimEnd('/')
        val token = tokenProvider()
        val startTimeTicks = if (startPositionMs > 0L) startPositionMs * TICKS_PER_MS else null

        val audioTracks = ms.mediaStreams
            .filter { it.type == "Audio" }
            .map { AudioTrack(it.index, it.language, it.displayTitle ?: it.language ?: "音轨 ${it.index}") }

        // 位图字幕(PGS/VobSub)取不到文本,直接过滤,不进入可选字幕列表
        val subtitles = ms.mediaStreams
            .filter { it.type == "Subtitle" && it.isTextSubtitle }
            .map {
                SubtitleTrackRef(
                    index = it.index,
                    language = it.language,
                    displayName = it.displayTitle ?: it.language ?: "字幕 ${it.index}",
                    isTextBased = true,
                )
            }

        // ---- L1:服务端纯音频(GET /Audio/{itemId}/universal) ----
        val l1 = buildAudioUniversalUrl(base, itemId, token, ms.id, audioBitRateBps, startTimeTicks)
        if (isAudioOnly(itemId, ms.id, base, token)) {
            return PlaybackSource(
                itemId = itemId,
                mediaSourceId = ms.id,
                streamUrl = l1,
                level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
                isHls = false,
                playSessionId = info.playSessionId,
                audioTracks = audioTracks,
                textSubtitles = subtitles,
            )
        }

        // ---- L3:兜底,拉完整流由客户端禁用视频轨(GET /Videos/{itemId}/stream.mkv,见 buildVideoStreamUrl)----
        val l3 = buildVideoStreamUrl(base, itemId, token, ms.id, startTimeTicks, info.playSessionId)
        return PlaybackSource(
            itemId = itemId,
            mediaSourceId = ms.id,
            streamUrl = l3,
            level = AudioDeliveryLevel.CLIENT_VIDEO_DISABLED,
            isHls = false,
            playSessionId = info.playSessionId,
            audioTracks = audioTracks,
            textSubtitles = subtitles,
        )
    }

    /**
     * 见 [audioOnlyByMediaSource]。探测用的是**不带 `startTimeTicks` 的**候选 URL —— 判定和起始
     * 位置无关,而且从 0 探测让服务端起的那个(随即被丢弃的)转码任务最轻。
     *
     * 探测异常照旧静默吞掉当"不是纯音频"(铁律 3:降级链必须保底可用,失败不得让用户看到错误)。
     * 但**失败的判定不写进缓存**:那多半是一次偶发的网络抖动,记下来会把整个会话钉死在 L3
     * (~42 倍带宽),这正是本产品最不能接受的静默降级。
     *
     * ⚠️ 只缓存**有结论**的判定([StreamProbe] 返回非 null)。端到端第 2 轮的实证:一个进程里
     * 八个用例全程被钉在 L3,而第 1、3 轮同一个条目全程 `200 audio/aac` 走 L1 —— 代码里能把一个
     * 进程整体钉死在 L3 的路径只有"某次没结论的探测被记进了缓存"这一条。
     *
     * ⚠️ [CancellationException] **必须**穿过去。原来这里是 `runCatching { … }.getOrDefault(false)`,
     * 连协程取消一起吞:`PlaybackService.onDestroy` 取消 `serviceScope` 时,一次正在飞的 seek 探测被
     * 取消,却被当成"不是纯音频"若无其事地走完 L3 分支,把一个播放源交给一个正在被拆掉的播放器。
     * [HttpStreamProbe] 特意重新抛出了它,不能在这一层又吞回去。**取消不是失败。**
     */
    private suspend fun isAudioOnly(itemId: String, mediaSourceId: String, base: String, token: String): Boolean {
        val key = "$itemId/$mediaSourceId"
        audioOnlyByMediaSource[key]?.let { return it }
        val probeUrl = buildAudioUniversalUrl(base, itemId, token, mediaSourceId, audioBitRateBps, startTimeTicks = null)
        val verdict = try {
            streamProbe.isAudioOnly(probeUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false            // 没问出结论:这次兜底 L3,但**不**记住
        audioOnlyByMediaSource[key] = verdict
        return verdict
    }

    private fun buildAudioUniversalUrl(
        base: String,
        itemId: String,
        token: String,
        mediaSourceId: String,
        audioBitRateBps: Int,
        startTimeTicks: Long?,
    ): String = buildString {
        append(base).append("/Audio/").append(itemId).append("/universal")
        append("?api_key=").append(token)
        append("&mediaSourceId=").append(mediaSourceId)
        append("&audioCodec=aac")
        append("&audioBitRate=").append(audioBitRateBps)
        append("&maxAudioChannels=2")
        if (startTimeTicks != null) append("&startTimeTicks=").append(startTimeTicks)
    }

    /**
     * L3 兜底流的 URL:`GET /Videos/{itemId}/stream.mkv`(`docs/jellyfin-openapi.json` 里的
     * `/Videos/{itemId}/stream.{container}`),**不带 `static`**。
     *
     * 两个决定都是对真实服务器实测出来的(2026-07-28,群晖 DS920+ / J4125)。每次请求都带**唯一的
     * `deviceId`** —— 不带的话 Jellyfin 会把上一次转码任务的输出文件原样递回来,量到的是缓存不是行为,
     * 第一轮实测就在这里踩过坑(两个不同的 `startTimeTicks` 拿到字节数完全相同的响应)。
     *
     * ## 🔴 之一:为什么去掉 `static=true`
     *
     * 带上它,服务端走"直出原始文件"那条路,`startTimeTicks` 被**静默忽略** —— 于是 L3 上的 seek
     * 变成空操作:进度条跳到目标继续往前走,耳机里却在从头重播。比报错更糟,因为用户看不出来。
     *
     * 条目 A(mkv 源,时长 1 496 037 ms),`startTimeTicks` 取"距结尾 30s":
     *
     * | 请求 | 响应头 | `ffprobe` 时长 | 结论 |
     * |---|---|---|---|
     * | 带 `static=true` | 200,`Content-Length: 1002873182`,`Accept-Ranges: bytes` | **1496.038 s(整集)** | 偏移被忽略 |
     * | 不带 `static` | 200,`Accept-Ranges: none`,`Transfer-Encoding: chunked` | **32.017 s** | 偏移生效 |
     * | 不带 `static`,偏移改成"距结尾 300s" | 同上 | **301.494 s** | 偏移生效,线性对应 |
     *
     * ## 🔴 之二:为什么必须显式指定 `.mkv` 容器
     *
     * 去掉 `static` 之后服务端按**源容器**决定输出容器。源是 MP4/MOV 时输出也是 MP4,而 remux 出来的
     * MP4 把 `moov` atom 写在**文件末尾**,响应又是 `chunked` + `Accept-Ranges: none` —— 流式播放器
     * 在整条流下完之前根本拿不到 `moov`。实测条目 B(mov/mp4 源)从头播:
     * 下了 441 MB 之后 `ffprobe` 仍然是 `moov atom not found / Invalid data`;
     * 端到端里 ExoPlayer 的表现就是 `ExoPlaybackException: Source error`,一秒都没播出来。
     *
     * Matroska 没有这个问题(头部就带 Segment 时长,天生可流式)。改用 `stream.mkv` 之后,
     * **两种源容器、两种起始位置全部可播且偏移生效**:
     *
     * | 条目 | 起始位置 | 下载 | `ffprobe` |
     * |---|---|---|---|
     * | B(mov/mp4 源) | 0 | 25s 截断,491 MB | 可解析,时长 1826.581 s(整集) |
     * | B(mov/mp4 源) | 距结尾 30s | 完整 13.2 MB | **33.280 s** |
     * | A(mkv 源) | 0 | 25s 截断,468 MB | 可解析,时长 1496.037 s(整集) |
     * | A(mkv 源) | 距结尾 30s | 完整 23.2 MB | **31.990 s** |
     *
     * 用**路径后缀** `.mkv` 而不是 `container=mkv` 查询参数:后者实测输出的 mkv 头里没有时长
     * (`ffprobe` 报 `duration=N/A`),前者有。两者都能播,取信息更全的那个。
     * ⚠️ 后缀必须是**单个**容器名。`MediaSourceDto.container` 是 ffprobe 风格的多值串
     * (`"mkv,webm"` / `"mov,mp4,m4a,3gp,3g2,mj2"`),直接拼进路径服务端回 **500**。
     *
     * ## 🔴 之三:为什么必须带上本次 resolve 的 `playSessionId`
     *
     * 前两条都做完之后,端到端里 seek **依旧**从头重播,而 URL 明明带着正确的 `startTimeTicks`。
     * 根因在服务端的转码产物复用:**Jellyfin 的转码输出文件路径不随 `startTimeTicks` 变化**,
     * 同一设备/会话第二次请求同一条流时,服务端把**上一次那个任务已经落盘的输出**原样递回来。
     * (第一轮 curl 实测里两个不同偏移拿到字节数完全相同的响应,就是这件事;当时误以为只是探测方法的问题。)
     *
     * 条目 B,先请求 offset 0 再请求"距结尾 30s",看第二次请求拿到什么:
     *
     * | 场景 | 第二次的 `ffprobe` 时长 | 结论 |
     * |---|---|---|
     * | 不带 `deviceId`/`playSessionId`(修复前的 App) | **1826.581 s(整集)** | 偏移无效 |
     * | 固定 `deviceId` + 固定 `playSessionId` | **1826.581 s** | 偏移无效 |
     * | 先 `DELETE /Videos/ActiveEncodings`(204)再请求 | **1826.581 s** | 删任务不删产物,仍然无效 |
     * | **每次请求带各自的 `playSessionId`** | **33.280 s** | ✅ 偏移生效 |
     *
     * 而 `POST /Items/{id}/PlaybackInfo` **每次调用都回一个新的 32 位 `PlaySessionId`**(实测三次全不同),
     * [resolve] 每次都会调它 —— 这个"每次唯一"的值本来就在手上,只是没拼进 URL。带上它同时也是正确的
     * 语义:服务端能把转码任务和播放会话对应起来。
     *
     * 实测 **L1 `/Audio/{itemId}/universal` 不受此影响**:带不带 `playSessionId`,"距结尾 30s" 都稳定
     * 给出 29.27 s。所以只改 L3,不动那条已经端到端验证过的路径。
     *
     * ## 参数核对
     *
     * `mediaSourceId` / `startTimeTicks` / `static` / `playSessionId` 均取自 `docs/jellyfin-openapi.json`
     * 的 `/Videos/{itemId}/stream` 参数表(小驼峰)。实测这个端点上 `StartTimeTicks` 大写也被接受,
     * 但仍按文档写法来,不赌服务端的模型绑定行为。
     *
     * ## 已知代价(接受)
     *
     * remux 到 mkv 时**多条音轨只剩服务端选中的那一条**(实测一个 3 条 DTS 音轨的条目,非 static
     * 输出只有 1 条 AAC),内嵌字幕轨也不在流里。用"L3 上的多音轨切换"换"seek 真的生效"是划算的:
     * L3 是罕见兜底,而 seek 是每次收听都要用的基本功能;字幕本来就独立拉取解析(铁律 1)。
     * 真要在 L3 上选音轨,该端点有 `audioStreamIndex` 参数可以走服务端选轨,那是另一件事。
     *
     * 视频/音频编码实测都是**流拷贝**(hevc/h264 与 aac 原样保留,33 s 内容 13 MB、
     * 300 s 内容 200 MB,都远快于实时),J4125 上不会因此多一份转码负担。
     *
     * 起始位置为 0 时形态完全一样 —— 一条 L3 流只有一种形态,免得 seek 前后在
     * "可字节 range 的整文件"和"chunked 流"两种语义之间来回切。
     */
    private fun buildVideoStreamUrl(
        base: String,
        itemId: String,
        token: String,
        mediaSourceId: String,
        startTimeTicks: Long?,
        playSessionId: String?,
    ): String = buildString {
        append(base).append("/Videos/").append(itemId).append("/stream.").append(L3_CONTAINER)
        append("?api_key=").append(token)
        append("&mediaSourceId=").append(mediaSourceId)
        // 见 KDoc 之三:少了它,服务端会把上一次转码任务的产物原样递回来,startTimeTicks 形同虚设。
        if (!playSessionId.isNullOrBlank()) append("&playSessionId=").append(playSessionId)
        if (startTimeTicks != null) append("&startTimeTicks=").append(startTimeTicks)
    }

    private companion object {
        const val DEFAULT_AUDIO_BIT_RATE_BPS = 128_000
        const val TICKS_PER_MS = 10_000L

        /** L3 强制输出的容器,见 [buildVideoStreamUrl]:Matroska 天生可流式,MP4 remux 的 moov 在文件末尾。 */
        const val L3_CONTAINER = "mkv"
    }
}
