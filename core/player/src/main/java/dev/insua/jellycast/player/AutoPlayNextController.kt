package dev.insua.jellycast.player

import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.mapper.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 播放序列自然走到头的原因。由 [AutoPlayNextController.sequenceEnd] 发出,`:app` 的导航层消费。
 *
 * **[AutoPlayNextController] 不认识导航**(设计文档 §5 的模块边界:`:core:player` 只接受已解析好的
 * 播放 URL + 元数据)。所以这里发的是"发生了什么",不是"去哪个页面"——"回首页 / 弹什么提示"
 * 完全由 `:app` 决定。
 */
enum class PlaybackSequenceEnd {
    /** 整部剧的所有季所有集都播完了。UI:回首页 + 提示「已播放完」。 */
    SERIES_COMPLETED,

    /** 单条目(电影)播完了,后面本来就没有"下一条"。UI:回首页,不额外提示。 */
    ITEM_COMPLETED,
}

/**
 * 自动连播:由调用方(播放会话)在收到 `Player.STATE_ENDED` 时调用 [onPlaybackEnded]。
 *
 * ## 连播**跟着剧走,不跟着队列走**(2026-07-29 用户需求)
 *
 * 以前这里是 `queue.next() ?: refillFromNextUp(userId)` —— 先取播放队列的下一项,队列耗尽才去问
 * `Shows/NextUp`。但首页各分区(继续收听 / 下一集 / 最近添加)把**整行列表**当播放队列传进来,
 * 一行里全是**不同的剧**;于是从「继续收听」点开一集,播完直接跳到了另一部剧。
 *
 * 现在的语义只有一条规则:
 *
 * | 刚播完的条目 | 接下来 |
 * |---|---|
 * | 剧集,本季还有下一集 | 本剧本季的下一集 |
 * | 剧集,本季已完 | 本剧**下一季的第一集** |
 * | 剧集,全剧播完 | 停,发 [PlaybackSequenceEnd.SERIES_COMPLETED] |
 * | 电影(及其他非剧集条目) | 停,发 [PlaybackSequenceEnd.ITEM_COMPLETED] —— **不去 NextUp 抓别的东西** |
 *
 * ### 为什么是"连播不看队列",而不是"把队列换成本剧集序"
 *
 * 两种设计都能修好这个缺陷,这里**只保留一种**,免得后来的人不知道哪一套说了算:
 *
 * - 队列由**调用方**构造,而调用方各有各的正当理由:剧集详情页传整季(「上一集」要能用,有测试守着)、
 *   首页分区传整行(同上)。要让队列同时是"本剧集序"就得逼所有调用方在点击那一刻先把整部剧拉下来,
 *   既慢又把 Jellyfin 的领域知识渗进 UI 层。
 * - 而"本剧的下一集是哪一个"这件事**只有服务端知道**(跨季尤其如此)。让连播直接问服务端,
 *   语义和入口无关:从哪儿点开的都一样正确。
 *
 * 所以:**队列不再参与"连播到哪一条"的决策**,只用来回答"刚播完的是哪一条"([PlayQueue.current])
 * 以及给「上一集/下一集」按钮用。连播成功之后队列会被**重定位到本剧的集序**——不是为了拿它做决策,
 * 而是因为下游按 [PlayQueue.current] 认"现在在放什么":`MediaControllerPlayerConnection.nowPlaying`
 * 按 `queueItem.id == source.itemId` 匹配、`PlayerModule` 的 `metadataProvider` 按它查锁屏元数据,
 * 对不上就是迷你条空白、锁屏封面掉。
 *
 * ## 稳定性根因 #4:本类**不**解析播放源
 *
 * 这里原本会 `resolve()` 一次并返回 `PlaybackSource`,而调用方 [PlaybackEndedAdvancer] 把它整个丢掉、
 * 转手经 [AudioPlaybackEngine.play] 再解析一次(那次才是真正拿来播的)。于是**每次换集都白跑一整个
 * `POST Items/{id}/PlaybackInfo` 往返**,并在 Jellyfin 上凭空多开一个等不到 stop 的播放会话。
 * 本类只回答"下一条是哪个 [MediaItem]",解析留给唯一真正需要它的地方。
 *
 * 同样的理由,常见路径(本季还有下一集)只花**一次** `GET Shows/{seriesId}/Episodes` 往返,
 * 只有真的到了季末才会再去查季列表——换集之间的静默窗口在家宽上行下是用户能直接听出来的。
 *
 * ## 铁律:网络不可用绝不允许崩溃
 *
 * 所有跨季/跨剧查询都包在 [runCatchingQuietly] 里:失败一律"静默停止"(返回 null 且**不发**
 * [sequenceEnd] 信号)——网络断了不是「已播放完」,不能拿这个谎报给用户。
 * [CancellationException] 例外,原样冒泡。
 *
 * 只依赖 [PlayQueue](纯 Kotlin,已单测)、[JellyfinApi](MockK 可造假)、
 * `autoPlayNext: Flow<Boolean>`——不依赖真实播放器,离线可单测。
 */
class AutoPlayNextController(
    private val queue: PlayQueue,
    private val api: JellyfinApi,
    private val autoPlayNext: Flow<Boolean>,
) {
    constructor(
        queue: PlayQueue,
        api: JellyfinApi,
        preferencesStore: PreferencesStore,
    ) : this(queue, api, preferencesStore.autoPlayNext)

    /**
     * 「播完本集」睡眠定时模式(设计文档 §3.5)落地处:一次性武装标志,武装后下一次
     * [onPlaybackEnded] 立即消费掉它并直接返回 null——既不检查 `autoPlayNext` 偏好,也不摸队列、
     * 更不发网络请求,保证"下一集不开始播放"这件事发生在真正决定连播与否的这一处,而不是靠上层 UI
     * 在收到 STATE_ENDED 之后再手忙脚乱地暂停(那时下一集可能已经开始加载了)。
     *
     * 消费后自动清零(一次性):用户下次重新进入播放/手动切下一集,连播行为恢复正常。
     */
    private val stopAfterCurrentEpisode = MutableStateFlow(false)
    val isStopAfterCurrentEpisodeArmed: StateFlow<Boolean> = stopAfterCurrentEpisode.asStateFlow()

    fun armStopAfterCurrentEpisode() {
        stopAfterCurrentEpisode.value = true
    }

    fun disarmStopAfterCurrentEpisode() {
        stopAfterCurrentEpisode.value = false
    }

    /**
     * "这一串播完了"的信号,null = 没有待处理的。见 [PlaybackSequenceEnd]:本类不碰导航,
     * 只把事实发出来,由 `:app` 决定回哪个页面、弹什么提示,消费完调 [onSequenceEndHandled] 清空。
     *
     * 用 [StateFlow] 而不是一次性 Channel:和 `AppSessionViewModel.message` 同一个模式——
     * 旋转屏幕/重组不会重复触发,也不会因为此刻没有订阅者(播放页没打开)而把信号整个丢掉。
     */
    private val _sequenceEnd = MutableStateFlow<PlaybackSequenceEnd?>(null)
    val sequenceEnd: StateFlow<PlaybackSequenceEnd?> = _sequenceEnd.asStateFlow()

    /** 导航层已经处理过这次"播完了",清空,免得下一次重组又弹一遍。 */
    fun onSequenceEndHandled() {
        _sequenceEnd.value = null
    }

    /** 返回该接着播的条目;不连播时为 null。见类注释:这里**不**解析播放源。 */
    suspend fun onPlaybackEnded(userId: String): MediaItem? {
        if (stopAfterCurrentEpisode.value) {
            stopAfterCurrentEpisode.value = false
            return null
        }
        if (!autoPlayNext.first()) return null

        // 队列在这里的唯一作用:回答"刚播完的是哪一条"。下一条播什么由剧集顺序决定,不由队列决定。
        val finished = queue.current.value ?: return null
        if (finished.kind != MediaKind.EPISODE) {
            // 电影(以及任何非剧集条目):后面本来就没有"下一条"。绝不去 NextUp 抓个不相干的东西接着放。
            _sequenceEnd.value = PlaybackSequenceEnd.ITEM_COMPLETED
            return null
        }

        return when (val outcome = runCatchingQuietly { locateNextInSeries(finished, userId) }) {
            null, Outcome.Unknown -> null                       // 查不到:静默停止,不谎报"已播放完"
            Outcome.SeriesCompleted -> {
                _sequenceEnd.value = PlaybackSequenceEnd.SERIES_COMPLETED
                null
            }
            is Outcome.Advance -> {
                // 重定位队列(见类注释):下游按 PlayQueue.current 认"现在在放什么"。
                queue.setQueue(outcome.episodes, outcome.index)
                outcome.episodes[outcome.index]
            }
        }
    }

    private sealed interface Outcome {
        /** 找到了下一集:[episodes] 是它所在那一季的完整集序,[index] 是它在其中的位置。 */
        data class Advance(val episodes: List<MediaItem>, val index: Int) : Outcome

        /** 全剧所有季所有集都播完了。 */
        data object SeriesCompleted : Outcome

        /** 数据不足以判断(缺归属 id、集列表里找不到刚播完的这一集……):静默停止,不做任何断言。 */
        data object Unknown : Outcome
    }

    /**
     * 顺序:先在**本季**里找下一集(常见路径,一次往返);本季已完才去查季列表跨季。
     * 归属 id 缺失时用 `GET Items/{itemId}` 补一次(某些列表接口不带 SeriesId/SeasonId)。
     */
    private suspend fun locateNextInSeries(finished: MediaItem, userId: String): Outcome {
        var cachedDetail: BaseItemDto? = null
        suspend fun detail(): BaseItemDto {
            val known = cachedDetail
            if (known != null) return known
            return api.itemDetail(finished.id, userId).also { cachedDetail = it }
        }

        val seriesId = finished.seriesId ?: detail().seriesId ?: return Outcome.Unknown

        var cachedSeasons: List<MediaItem>? = null
        suspend fun seasons(): List<MediaItem> {
            val known = cachedSeasons
            if (known != null) return known
            return api.seasons(seriesId, userId).items
                .mapNotNull { it.toMediaItem() }
                .also { cachedSeasons = it }
        }

        // 季 id:条目自带 → 条目详情 → 退回按季号在季列表里定位(不能因为缺个 id 就漏掉本季剩下的集)。
        val seasonId = finished.seasonId
            ?: detail().seasonId
            ?: seasons().firstOrNull { it.seasonNumber != null && it.seasonNumber == finished.seasonNumber }?.id
            ?: return Outcome.Unknown

        val episodes = episodesOf(seriesId, seasonId, userId)
        val index = episodes.indexOfFirst { it.id == finished.id }
        // 本季集列表里找不到刚播完的这一集:数据对不上,宁可停,也不能凭空跳过一整季。
        if (index < 0) return Outcome.Unknown
        if (index < episodes.lastIndex) return Outcome.Advance(episodes, index + 1)

        return firstEpisodeAfterSeason(seriesId, seasonId, seasons(), userId)
    }

    /** 本季已完 → 下一季的第一集。空季(比如没有内容的特别篇)跳过去继续往后找。 */
    private suspend fun firstEpisodeAfterSeason(
        seriesId: String,
        seasonId: String,
        seasons: List<MediaItem>,
        userId: String,
    ): Outcome {
        val position = seasons.indexOfFirst { it.id == seasonId }
        if (position < 0) return Outcome.Unknown
        for (index in position + 1 until seasons.size) {
            val episodes = episodesOf(seriesId, seasons[index].id, userId)
            if (episodes.isNotEmpty()) return Outcome.Advance(episodes, 0)
        }
        return Outcome.SeriesCompleted
    }

    /**
     * 一季的集序。用 `:core:network` 的共享 mapper(DTO→领域模型、ticks→ms 的唯一换算点,项目铁律)——
     * `:core:player` 不该自己解析 Jellyfin DTO(设计文档 §5)。共享 mapper 对不认识的类型返回 null,
     * 这里用 `mapNotNull` 跳过,不让一条陌生类型的数据毒死整个集列表。
     *
     * 端点与参数大小写已 jq 核对 docs/jellyfin-openapi.json:
     * `GET /Shows/{seriesId}/Episodes`,path `seriesId`,query `seasonId` / `userId`。
     */
    private suspend fun episodesOf(seriesId: String, seasonId: String, userId: String): List<MediaItem> =
        api.episodes(seriesId, seasonId, userId).items.mapNotNull { it.toMediaItem() }

    /** 失败一律当"查不到"处理(返回 null),但取消必须原样冒泡。 */
    private inline fun <T> runCatchingQuietly(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
}
