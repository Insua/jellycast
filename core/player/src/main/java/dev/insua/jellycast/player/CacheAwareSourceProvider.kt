package dev.insua.jellycast.player

import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.network.JellyfinApi
import kotlinx.coroutines.CancellationException

/**
 * 缓存感知的 [PlaybackSourceProvider] 装饰器:命中本地缓存就直接播本地文件,未命中原样委派给
 * [delegate](生产环境是 [PlaybackSourceResolver.asProvider] 那条 L1/L3 降级链——委派路径的行为
 * 字面不变,这个类不重新实现,也不修饰它的返回值)。
 *
 * ## 设计决定:命中缓存时,音轨/字幕轨元数据从哪来?
 *
 * [AudioCacheStore] 落地的只是纯音频字节流本身,不带任何轨道元数据——没有 `mediaSourceId`、
 * 没有 `playSessionId`、更没有字幕轨列表。而字幕是本产品的主打功能(铁律 1:字幕独立拉取渲染),
 * 缓存路径绝不能因为"没有元数据"就退化成"缓存的条目反而放不出字幕"。有两个选项:
 *
 * 1. **把元数据和音频一起缓存**——下载时顺带把 `PlaybackInfo` 的响应存一份到本地。问题是一致性:
 *    服务端那边条目的字幕轨/音轨随时可能变(用户在 Jellyfin 里重新扫描、换了字幕文件、转码设置
 *    变了),缓存的元数据会不知不觉过期,而这里完全没有机制去感知"该失效了"、也没有地方触发重新拉取。
 * 2. **命中缓存时仍然发起一次轻量的 `PlaybackInfo` 查询**,只是把最终 [PlaybackSource.streamUrl]
 *    换成本地文件路径——**采用这个**。`POST /Items/{id}/PlaybackInfo` 本身不触发任何转码(转码是
 *    访问 `/Audio/.../universal` 或 `/Videos/.../stream` 才会发生的事,只有 [PlaybackSourceResolver]
 *    的 L1 探测才会真的在群晖上起一个转码作业——这里刻意**不**调 [delegate],就是为了避免命中
 *    缓存还要在 NAS 上再起一次那样的探测),所以这次查询对 J4125 而言几乎零成本,换来的是元数据
 *    永远和服务端保持一致,不需要任何额外的失效逻辑。
 *
 * 断网/服务端不可达时这次查询会失败——按铁律 4(字幕失败不得影响播放),这里静默降级为
 * "能播、没有音轨/字幕轨信息",而不是退化成"不能播"。这一点尤其重要:断网时正是缓存这个功能
 * 最该发挥作用的场景,如果元数据查询一失败就不返回本地源,缓存存在的意义就没了。见
 * `CacheAwareSourceProviderTest`「断网时元数据查询失败仍返回本地源只是没有字幕」用例。
 *
 * Task 6 的预取控制器不依赖这个类,只依赖 [AudioCacheStore] 本身。
 *
 * @param serverIdProvider 复审 I3(Important):**不是**装配时刻的一次性快照(旧实现是构造时固定
 *   的 `serverId: String`)。缓存的文件系统真相按服务器隔离(设计文档 §6),`AppSessionViewModel
 *   .onServerConnected` 切换激活服务器又不重启进程——快照如果不跟着变,切换之后这里会拿着**旧**
 *   服务器的 id 去查**新**服务器条目的缓存(而 `CachePrefetchController` 那边如果没跟着一起改
 *   就会拿同一个旧 id 把新服务器的整季音频写进旧服务器的目录——两处必须同步修复,是同一个缺陷
 *   的两个面)。生产环境接的是 `{ serverStore.activeServerId.first() }`,每次 resolve 都重新问
 *   一次"现在的激活服务器是谁"。查不到(`null`)时直接委派给 [delegate],不去查一个不知道
 *   对不对的分区——和 [pathIfComplete] 本身查询失败时的降级是同一种保守默认值。
 */
class CacheAwareSourceProvider(
    private val delegate: PlaybackSourceProvider,
    private val cacheStore: AudioCacheStore,
    private val api: JellyfinApi,
    private val serverIdProvider: suspend () -> String?,
) : PlaybackSourceProvider {

    override suspend fun resolve(itemId: String, userId: String, startPositionMs: Long): PlaybackSource {
        // 缓存查询自身失败一律静默降级为"当作没有缓存"——铁律:不得用 runCatching 包 suspend
        // 调用,CancellationException 必须重抛(仓库已经因为把取消当失败吞掉返工过一次)。
        val serverId = try {
            serverIdProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return delegate.resolve(itemId, userId, startPositionMs)

        val cachedPath = try {
            cacheStore.pathIfComplete(serverId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return delegate.resolve(itemId, userId, startPositionMs)

        // 驱逐策略按 lastAccessAt 排序决定先删谁,命中缓存必须记一次访问,否则常听的集反而先被删。
        // AudioCacheStore.touch 内部已经把所有非取消异常吞成"静默什么都不做",这里不需要再包一层。
        cacheStore.touch(serverId, itemId)

        // 见类 KDoc 的设计决定:轻量的元数据查询,失败就退化成"能播、没有轨道信息",绝不影响本地
        // 源的返回——不能调 delegate.resolve(),那条路径为了判定 L1/L3 会真的在 NAS 上起探测。
        val metadata = try {
            api.playbackInfo(itemId, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val mediaSource = metadata?.mediaSources?.firstOrNull()

        val audioTracks = mediaSource?.mediaStreams
            ?.filter { it.type == "Audio" }
            ?.map { AudioTrack(it.index, it.language, it.displayTitle ?: it.language ?: "音轨 ${it.index}") }
            .orEmpty()

        // 位图字幕(PGS/VobSub)取不到文本,和 PlaybackSourceResolver 保持同一条过滤规则。
        val subtitles = mediaSource?.mediaStreams
            ?.filter { it.type == "Subtitle" && it.isTextSubtitle }
            ?.map {
                SubtitleTrackRef(
                    index = it.index,
                    language = it.language,
                    displayName = it.displayTitle ?: it.language ?: "字幕 ${it.index}",
                    isTextBased = true,
                    isExternal = it.isExternal,
                )
            }
            .orEmpty()

        return PlaybackSource(
            itemId = itemId,
            // 元数据查询失败时没有真实的 mediaSourceId 可用——退回 itemId 只是保证这个字段非空,
            // 不代表和服务端的媒体源标识对得上;此时反正也没有字幕/多音轨可选,不影响播放。
            mediaSourceId = mediaSource?.id ?: itemId,
            streamUrl = "file://$cachedPath",
            level = AudioDeliveryLevel.SERVER_AUDIO_ONLY,
            isHls = false,
            playSessionId = metadata?.playSessionId,
            audioTracks = audioTracks,
            textSubtitles = subtitles,
            isLocalFile = true,
        )
    }
}
