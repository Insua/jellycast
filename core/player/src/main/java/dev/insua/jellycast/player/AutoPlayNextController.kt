package dev.insua.jellycast.player

import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.PlaybackSource
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.mapper.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 自动连播:由调用方(播放会话)在收到 `Player.STATE_ENDED` 时调用 [onPlaybackEnded]。
 *
 * 决策顺序(计划 Task 11 Step 5 + 控制者修正 §2):
 * 1. `autoPlayNext` 为 false → 不连播。
 * 2. [PlayQueue.hasNext] → [PlayQueue.next]。
 * 3. 队列已耗尽 → 调 [JellyfinApi.nextUp] 补充队列,取补充后的第一项;NextUp 也空(或调用
 *    失败)则不连播,不向上抛错——和字幕/进度上报同样的"静默降级,绝不打断播放"原则。
 *
 * ## 稳定性根因 #4:本类**不**解析播放源
 *
 * 这里原本会 `resolve()` 一次并返回 [PlaybackSource],而调用方 [PlaybackEndedAdvancer] 把它整个丢掉、
 * 转手经 [AudioPlaybackEngine.play] 再解析一次(那次才是真正拿来播的)。于是**每次换集都白跑一整个
 * `POST Items/{id}/PlaybackInfo` 往返**,并在 Jellyfin 上凭空多开一个等不到 stop 的播放会话。
 * 在家宽上行受限的真机上,这一次多余的往返直接顶在"上一集刚播完、下一集还没出声"的静默窗口上。
 *
 * 而"要不要连播、连播到哪一条"这个决策**根本不需要播放源** —— 它只依赖偏好开关、睡眠定时武装状态
 * 和队列。所以本类只回答"下一条是哪个 [MediaItem]",解析留给唯一真正需要它的地方。
 *
 * 只依赖 [PlayQueue](纯 Kotlin,已单测)、[JellyfinApi](MockK 可造假)、
 * `autoPlayNext: Flow<Boolean>`——不依赖真实播放器,离线可单测。
 * 次构造函数直接接 [PreferencesStore],生产环境用 `PreferencesStore.autoPlayNext` 构造。
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
     * [onPlaybackEnded] 立即消费掉它并直接返回 null——既不检查 `autoPlayNext` 偏好,也不摸队列,
     * 保证"下一集不开始播放"这件事发生在真正决定连播与否的这一处,而不是靠上层 UI 在收到
     * STATE_ENDED 之后再手忙脚乱地暂停(那时下一集可能已经在这里被解析、开始加载了)。
     *
     * 消费后自动清零(一次性):用户下次重新进入播放/手动切下一集,连播行为恢复正常,不需要
     * 记得"回去关掉"这个开关。
     */
    private val stopAfterCurrentEpisode = MutableStateFlow(false)
    val isStopAfterCurrentEpisodeArmed: StateFlow<Boolean> = stopAfterCurrentEpisode.asStateFlow()

    fun armStopAfterCurrentEpisode() {
        stopAfterCurrentEpisode.value = true
    }

    fun disarmStopAfterCurrentEpisode() {
        stopAfterCurrentEpisode.value = false
    }

    /** 返回该接着播的条目;不连播时为 null。见类注释:这里**不**解析播放源。 */
    suspend fun onPlaybackEnded(userId: String): MediaItem? {
        if (stopAfterCurrentEpisode.value) {
            stopAfterCurrentEpisode.value = false
            return null
        }
        if (!autoPlayNext.first()) return null

        return queue.next() ?: refillFromNextUp(userId)
    }

    /**
     * ⚠️ 复审 Important 4(已闭合):这里原来用的是本类自己的一份私有 `BaseItemDto.toMediaItem()`,
     * 和 `:core:network` 的共享 mapper 是两份实现。那份私有实现漏掉了 `imageTag`(也漏掉了
     * Season/Episode 的 IndexNumber 语义分流),于是**每次自动连播换集之后封面就变空白**——
     * `MediaItem.posterUrl()` 拿不到 tag 只能返回 null,迷你条和全屏播放页都只剩占位背景。
     *
     * 现在统一用 `dev.insua.jellycast.network.mapper.toMediaItem()`:它是 DTO→领域模型、ticks→ms 的
     * 唯一换算点(项目铁律),也顺手修掉了设计文档 §5 的边界问题——`:core:player` 不该自己解析
     * Jellyfin DTO。共享 mapper 对不认识的类型返回 null,所以这里用 `mapNotNull` 跳过,不让一条
     * 陌生类型的数据毒死整个连播队列。
     */
    private suspend fun refillFromNextUp(userId: String): MediaItem? {
        val fetched = runCatching { api.nextUp(userId).items.mapNotNull { it.toMediaItem() } }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                emptyList()
            }
        if (fetched.isEmpty()) return null
        queue.setQueue(fetched, 0)
        return queue.current.value
    }
}
