package dev.insua.jellycast.player

import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "给个 itemId/userId/起始位置,换一个可播放源"这件事的最小抽象。[AudioPlaybackEngine] 依赖它,
 * 而不是具体的 [PlaybackSourceResolver],这样单测编排逻辑时可以完全绕开 JellyfinApi / StreamProbe。
 * 用 [PlaybackSourceResolver.asProvider] 把 Task 8 的 resolver 接到这里,不改动其公开签名/默认参数
 * (resolver 已有测试直接依赖那个默认参数,不能动)。
 */
fun interface PlaybackSourceProvider {
    suspend fun resolve(itemId: String, userId: String, startPositionMs: Long): PlaybackSource
}

fun PlaybackSourceResolver.asProvider(): PlaybackSourceProvider =
    PlaybackSourceProvider { itemId, userId, startPositionMs -> resolve(itemId, userId, startPositionMs) }

/**
 * ExoPlayer 操作的最小接口:换一个 URL 重新准备播放、读当前流内相对位置、直接 seek、释放。
 *
 * [seekTo] 只为**本地缓存文件**而设:本地完整文件支持 Range,seek 可以直接落到播放器身上,
 * 响应是即时的。远端转码流 `Accept-Ranges: none`,`player.seekTo()` 在它身上不可靠(可能卡住
 * 或从头播放)——[AudioPlaybackEngine] 对远端源的 seek 因此**从不**调这个方法,而是重新
 * resolve 一条从目标位置起转码的新流再 prepare(见 [AudioPlaybackEngineImpl] 类注释)。这条
 * 铁律现在靠 [AudioPlaybackEngineImpl] 内部按 `PlaybackSource.isLocalFile` 分支来保证,
 * 不再像早期版本那样靠"接口里压根没有 seekTo"这种结构性手段——
 * `AudioPlaybackEngineTest`「远端源行为完全不变」一条测试是这条铁律的回归防线。
 *
 * 默认实现是空操作:不关心 seek 的假实现(既有测试里那些)不必跟着补方法体。
 * 生产实现见 [ExoPlayerControl]。
 */
interface PlayerControl {
    /**
     * **当前这条流内**的相对播放位置(ms),即 `ExoPlayer.currentPosition`。
     *
     * ⚠️ 这**不是**条目内的绝对位置。因为 seek/续播都实现成"换一条从目标位置开始转码的新流"
     * (见本文件类注释),每换一条流这个值就从 0 重新开始。要"这一集听到第几分钟"必须读
     * [AudioPlaybackEngine.absolutePositionMs],不能读这个值。
     */
    val currentPositionMs: Long

    /**
     * [metadata] 是 Task「媒体元数据」的落地点:锁屏/通知/蓝牙/流体云共同的前提。可以是 `null`
     * (没有对应的 [PlaybackDisplayMetadata] 时,比如引擎还不认识这个 itemId)——生产实现
     * ([ExoPlayerControl])对 `null` 的处理是"标题/副标题/封面都不设",不是崩溃或抛错。
     */
    fun setMediaItemAndPrepare(url: String, metadata: PlaybackDisplayMetadata?)

    /** 见类注释:只应该在本地缓存文件上调用。空操作默认实现,详见类注释。 */
    fun seekTo(positionMs: Long) {}

    fun release()
}

sealed interface PlaybackEngineState {
    data object Idle : PlaybackEngineState

    /**
     * [startPositionMs] 是这条流在**条目内**的起始位置(即 resolve 时传给服务端的
     * `startTimeTicks` 对应的毫秒数)。进度上报需要它才能在"新源就绪"这一刻报出正确的绝对位置,
     * 而不是报一个刚归零的流内相对位置(全支线复审 Critical 1/2)。
     *
     * ⚠️ 复审(Task 6 Finding 1):[emissionSeq] 单调递增,只用来在结构上区分"两次内容完全相同的
     * `Ready`"——调用方**不该**读它做任何业务判断,它纯粹是"强制 `StateFlow` 发射"的技术手段。
     * `MutableStateFlow` 对结构相等的连续赋值会静默丢弃(conflation,`value` 的 setter 内部按
     * `equals()` 比较),而 [Ready]/[PlaybackSource] 都是 `data class`——本地缓存源连续两次 seek
     * 到**同一个**位置时,`source` 和 `startPositionMs` 会完全一样(见
     * [AudioPlaybackEngineImpl.seekTo] 本地分支:复用同一个 [currentSource]、位置又相同),
     * 第二次赋值因此被 `StateFlow` 悄悄吞掉,`playbackReadyEvents()` 收不到第二个事件,
     * `PlaybackProgressCoordinator.onSourceReady` 也就不会跑——这正是这次修复本身要堵住的那个
     * "seek 后立即上报进度"缺口,如果不处理会在"重复 seek 到同一位置"这一种子场景下原样复现。
     * 默认值 `0L` 不影响任何既有测试:没有任何测试对 `Ready` 做完整的结构相等比较(全部是
     * `is Ready` / 读某个字段),这里核对过。
     */
    data class Ready(
        val source: PlaybackSource,
        val startPositionMs: Long = 0L,
        val emissionSeq: Long = 0L,
    ) : PlaybackEngineState

    /** 对应设计文档 §8:「该条目无法播放」。由 Task 8 契约里 resolve() 的异常转换而来,绝不上抛。 */
    data class Error(val itemId: String, val message: String = "该条目无法播放") : PlaybackEngineState
}

interface AudioPlaybackEngine {
    val state: StateFlow<PlaybackEngineState>

    /**
     * **条目内绝对播放位置(ms)的唯一权威**。
     *
     * = 当前这条流的起始位置 + [PlayerControl.currentPositionMs]。
     *
     * 为什么必须有这个东西:转码流 `Accept-Ranges: none`,seek/续播只能"重新 resolve 一条从目标
     * 位置开始的新流",于是 `ExoPlayer.currentPosition` 每换一条流就从 0 重新开始——它只回答
     * "在这条流里播了多久"。从 8:00 续播的那一刻它是 0,谁拿它当绝对位置用就会:快进 30s 变成
     * 往回跳 7 分半、歌词高亮第 1 行、上报进度把用户在 Jellyfin 里的记录冲成 0。
     *
     * 所有位置消费方(歌词定位、进度条、快进快退起点、进度上报)都必须读这里。Idle 时为 0。
     */
    val absolutePositionMs: Long

    /**
     * "此刻引擎认为自己该播哪个条目" —— [play] 一被调用就同步更新,不等 resolve 落地成功/失败。
     * `null` = 还没有任何 [play] 发生过(Idle)。
     *
     * ⚠️ 复审 Finding 1(v3):和 [state] 里 `Ready`/`Error` 携带的 itemId **不是**一回事——那两个
     * 只在一次 resolve **完成**(成功或失败)之后才更新,存在滞后,读不到"`play()` 刚被调用、
     * resolve 还没跑完"这个窗口。[EngineSeekRouter] 的防抖 seek 落地前必须核对的正是这个同步更新的
     * 值:如果拿 [state] 去判断,`play()` 触发的新 resolve 还没完成时,`state` 仍然是旧条目的
     * `Ready`,防抖 seek 会误以为"条目没变"而照常拿旧位置去 resolve 新条目。
     */
    val currentItemId: String?

    suspend fun play(itemId: String, userId: String, startPositionMs: Long = 0L)
    suspend fun seekTo(positionMs: Long)

    /**
     * 播放宿主(`PlaybackService`)销毁、播放器已被 `stop()` + `clearMediaItems()` 之后调用:
     * 把"当前这条流"的一切痕迹清干净——流基准、当前条目/用户、[state] 一律复位。
     *
     * ⚠️ 复审 Finding 2:没有这个方法之前,Service 销毁后引擎会**继续声称自己是 `Ready`**,而播放器
     * 其实已经被清空了。后果有两个:
     * 1. [absolutePositionMs] 衰减成"那条流的起始位置 + 0",也就是一个几分钟前的旧值,谁读它都错;
     * 2. Service 重建后重新订阅 [state],那个陈旧的 `Ready` 被 `StateFlow` 重放进来,进度协调器把它
     *    当成一次新的播放开始,给一个**根本没在播的条目**发 `Sessions/Playing`(服务端因此 `PlayCount`
     *    +1、`Played` 被置回 false、留下一个等不到 stop 的幽灵会话)。
     *
     * 和 [release] 的区别:[release] 会放掉播放器本身,而这个播放器是 `@Singleton`、比 Service 活得久
     * (见 `PlaybackService.onDestroy` 的 KDoc),销毁 Service 时**不能**放掉它。
     */
    fun reset()

    fun release()
}

/**
 * 「条目内绝对时间轴」:位置 + 总时长。给那些只认识标准 [androidx.media3.common.Player] 接口、
 * 不该认识 [AudioPlaybackEngine] 的地方([SeekInterceptingPlayer])用的窄接口,单测里可直接造假。
 *
 * 两个方法都允许返回 null 表示"不知道",调用方回退到底层 player 的原始值——这样它永远只可能让
 * 位置/时长变得更准,不可能因为接线没接上就把播放搞坏。
 */
interface AbsoluteTimeline {
    /** 条目内绝对位置(ms);null = 未知。 */
    fun absolutePositionMs(): Long?

    /** 条目总时长(ms),来自 Jellyfin 元数据 `runTimeMs`;null = 未知。 */
    fun absoluteDurationMs(): Long?

    companion object {
        /** 空实现:全部未知,调用方完全回退到底层 player。 */
        val Unknown: AbsoluteTimeline = object : AbsoluteTimeline {
            override fun absolutePositionMs(): Long? = null
            override fun absoluteDurationMs(): Long? = null
        }
    }
}

/**
 * 把引擎适配成 [AbsoluteTimeline]。总时长不是引擎的职责(引擎不认识 Jellyfin 元数据),由调用方用
 * `PlayQueue.current.value?.runTimeMs` 之类的 lambda 提供——转码流是 chunked AAC,
 * `ExoPlayer.duration` 往往是 `C.TIME_UNSET`,不能作为权威。
 */
fun AudioPlaybackEngine.asAbsoluteTimeline(durationProvider: () -> Long? = { null }): AbsoluteTimeline {
    val engine = this
    return object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = engine.absolutePositionMs
        override fun absoluteDurationMs(): Long? = durationProvider()
    }
}

/**
 * ⚠️ 为什么 seek 不调 `player.seekTo`:
 *
 * Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)Jellyfin 转码音频流响应头是
 * `Accept-Ranges: none`——带 Range 头的请求也返回 HTTP 200 而不是 206,服务端根本不支持按字节
 * 区间取流。`ExoPlayer.seekTo()` 依赖底层数据源能响应 Range 请求做字节级跳转,在这种流上不可靠
 * (会卡住,或者从头播放)。
 *
 * 因此这里把 seek 实现成:带着新的 `startPositionMs` 重新调用 [PlaybackSourceProvider.resolve]
 * 换一个新 URL(服务端用 `startTimeTicks` 参数从目标位置开始转码),再 setMediaItem + prepare。
 *
 * **不要把远端源这条路"优化"回 `player.seekTo` —— 那样在真实服务器上会直接踩坑。**
 *
 * ⚠️ 但这条铁律只管**远端**源。[PlaybackSource.isLocalFile] 为 `true` 时(音频缓存落地后的
 * 本地文件),上面这段限制根本不存在:本地完整文件支持 Range,`player.seekTo()` 直接可用、
 * 即时生效——这正是"拖进度条不跟手"这个体验问题在缓存路径上被彻底根治的原因,不必再等一轮
 * `PlaybackInfo` 往返 + 服务端起转码。[seekTo] 因此按 [currentSourceIsLocalFile] 分两条路:
 * 本地源直接 [PlayerControl.seekTo],远端源维持上面这套"重新 resolve"逻辑一字不变。
 *
 * resolve() 抛出的异常(PlaybackInfo 无媒体源 / 网络或认证失败,见 Task 8 契约)在这里被捕获,
 * 转成 [PlaybackEngineState.Error],绝不向上抛出、绝不崩掉调用方(Service/播放会话)。
 */
class AudioPlaybackEngineImpl(
    private val sourceProvider: PlaybackSourceProvider,
    private val playerControl: PlayerControl,
    /**
     * 按 itemId 查一份 [PlaybackDisplayMetadata] 给 [PlayerControl.setMediaItemAndPrepare]。
     * 引擎本身不认识 `:core:model` 之外的任何东西、也不持有"当前队列都有哪些条目"这份状态——
     * 生产实现由 `PlayerModule` 接到 [PlayQueue.current](和 [AbsoluteTimeline] 的
     * `durationProvider` 是同一种"外部只读查表"模式)。默认返回 `null`:不接这根线的调用方
     * (绝大多数既有单测)行为不变,元数据整体缺失时降级为"标题/副标题/封面都不设",不影响播放。
     */
    private val metadataProvider: (itemId: String) -> PlaybackDisplayMetadata? = { null },
) : AudioPlaybackEngine {

    private val _state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    /** 见接口 [AudioPlaybackEngine.currentItemId]:对外只读,`play`/`reset` 在本类内部写。 */
    override var currentItemId: String? = null
        private set
    private var currentUserId: String? = null

    /**
     * 当前这条流在条目内的起始位置;null = 还没有任何流被 prepare 过。
     *
     * 只在 resolve **成功**、播放器真的换到新流之后才更新——resolve 失败时播放器还在放旧流,
     * 基准跟着变会让绝对位置凭空跳一截。也正因为如此,判断"有没有基准"不能看 [state] 是不是
     * `Ready`:一次失败的 seek 会把状态打成 `Error`,但播放器仍在放着上一条流,此时绝对位置必须
     * 继续正确(见 [absolutePositionMs])。
     */
    @Volatile
    private var currentStartPositionMs: Long? = null

    /**
     * 当前这条流是否来自 [PlaybackSource.isLocalFile]。决定 [absolutePositionMs] 与 [seekTo]
     * 走哪条分支(见类注释)。和 [currentStartPositionMs] 同一套更新纪律:只在 resolve **成功**、
     * 播放器真的换到新流之后才更新,道理相同——resolve 失败时播放器还在放旧流,不能把它的
     * 源类型也跟着改了。
     */
    @Volatile
    private var currentSourceIsLocalFile: Boolean = false

    /**
     * 当前这条流对应的 [PlaybackSource]。和 [currentStartPositionMs] / [currentSourceIsLocalFile]
     * 同一套更新纪律——只在 resolve **成功**、播放器真的换到新流之后才更新。
     *
     * ⚠️ Task 6 carry-forward 之二(见任务简报):本地缓存源的 [seekTo] 需要重新发一次
     * [PlaybackEngineState.Ready] 来恢复"seek 后立即上报进度"这条设计文档 §7 的纪律(见 [seekTo]
     * 本地分支的说明),而那次重新发出的 `Ready` 必须携带**同一个** `PlaybackSource`——不能凭空
     * 造一个,也不能重新 resolve(那正是本地源要避免的事)。这个字段就是那次重新发送时的数据来源。
     */
    @Volatile
    private var currentSource: PlaybackSource? = null

    /**
     * ## 稳定性根因 #2:连点快进时"最后请求的那一次说了算"
     *
     * 生产路径上唯一存在并发的地方:`SeekInterceptingPlayer.seekForward()` → [EngineSeekRouter] →
     * `scope.launch { engine.seekTo(...) }` —— **每一次按键都新起一个协程,谁也不等谁**。用户连点
     * 三下快进,就是三个并发的 [resolveAndPrepare] 各自跑一次 `PlaybackInfo` 往返 + 一次 L1 探测。
     *
     * 在这个计数器之前,**哪一次先完成哪一次说了算**。而"先按下的那次"完全可能后完成(撞上一次慢的
     * PlaybackInfo、或者服务端起转码起得慢),于是进度条跳到最后一个目标之后又自己退回去;更糟的是
     * 一个**迟到的失败**会把 `state` 打成 `Error`、UI 弹「该条目无法播放」,而耳机里明明在响。
     *
     * 每次请求领一个单调递增的号,拿到结果时号已经不是最新的就整条丢弃 —— 不 prepare、不动流基准、
     * 不改 state。
     *
     * **线程契约:** "对号"和"落地"之间没有挂起点,而生产上所有 play/seek 都发生在主线程
     * (`PlaybackService` 的 main-looper scope、`viewModelScope`、`Dispatchers.Main.immediate`),
     * 所以这段检查-然后-写入是原子的。
     */
    private val requestSeq = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 见 [PlaybackEngineState.Ready.emissionSeq] 的 KDoc:每一次真正把 `_state` 置成 `Ready`
     * 时领一个新号,保证连续两次 `Ready`(哪怕 `source`/`startPositionMs` 完全相同,比如本地缓存源
     * 连续两次 seek 到同一位置)在结构上永远不相等,不会被 `MutableStateFlow` 的 conflation 悄悄
     * 吞掉一次赋值。
     *
     * 刻意**不**复用 [requestSeq]:那个计数器管的是"迟到的 resolve 结果要不要被丢弃"这件事,
     * 语义完全不同——本地 seek 分支根本不走 [resolveAndPrepare]/[isStale] 那条判定,把两件事的
     * 计数器混在一起会让 [requestSeq] 的"哪次请求最新"语义变得难以推理。
     */
    private val stateEmissionSeq = java.util.concurrent.atomic.AtomicLong(0L)

    override val absolutePositionMs: Long
        get() {
            val relativeMs = playerControl.currentPositionMs.coerceAtLeast(0L)
            // 本地缓存文件:播放器本身就在放完整文件,player 报的位置就是条目内绝对位置,
            // 不需要(也不能)再叠加流起点基准 —— 那是给"每次 seek 都换一条新流,从 0 重新计时"
            // 的远端转码流准备的补偿,本地文件根本没有这个问题。
            return if (currentSourceIsLocalFile) {
                relativeMs
            } else {
                currentStartPositionMs?.plus(relativeMs) ?: 0L
            }
        }

    override suspend fun play(itemId: String, userId: String, startPositionMs: Long) {
        currentItemId = itemId
        currentUserId = userId
        resolveAndPrepare(itemId, userId, startPositionMs)
    }

    override suspend fun seekTo(positionMs: Long) {
        val itemId = currentItemId ?: return
        val userId = currentUserId ?: return
        if (currentSourceIsLocalFile) {
            // 本地缓存文件:完整、可 seek,直接让播放器跳,不重新 resolve/prepare。
            // 见 PlayerControl.seekTo 与类注释 —— 这正是缓存路径下"拖进度条"变得即时的原因。
            playerControl.seekTo(positionMs)

            // ⚠️ Task 6 carry-forward 之二:这条分支以前只挪动了播放器位置,不发新的
            // PlaybackEngineState.Ready —— 于是"seek 后立即上报进度"这条设计文档 §7 的纪律,
            // 在本地缓存源上悄悄消失了(远端源不受影响:它的 seek 走 resolveAndPrepare,天然
            // 发一次 Ready)。10 秒心跳能自愈,但用户 seek 一集缓存好的剧集后 10 秒内被强杀 App,
            // 这次 seek 在服务端就丢了——远端路径上不会发生这个窗口。
            //
            // 补发一次 Ready(同一个 source、新的 positionMs):playbackReadyEvents() 会把它识别成
            // NEW_PLAYBACK,PlaybackProgressCoordinator.onSourceReady 按"同一条目再次就绪"分支
            // 立即发一次 progress——不重新 resolve、不重新 prepare(currentSource 是已经成功过
            // 的那个),只是把已经发生的本地 seek 尽快同步给服务端,和远端路径的行为对齐。
            currentSource?.let { source ->
                _state.value = PlaybackEngineState.Ready(source, positionMs, stateEmissionSeq.incrementAndGet())
            }
            return
        }
        // 远端源:见类注释,这里刻意重新 resolve + prepare,不是 player.seekTo。字面行为不变。
        resolveAndPrepare(itemId, userId, positionMs)
    }

    private suspend fun resolveAndPrepare(itemId: String, userId: String, startPositionMs: Long) {
        val token = requestSeq.incrementAndGet()
        try {
            val source = sourceProvider.resolve(itemId, userId, startPositionMs)
            if (isStale(token)) return
            playerControl.setMediaItemAndPrepare(source.streamUrl, metadataProvider(itemId))
            if (source.isLocalFile) {
                // 本地文件 prepare 后播放器默认从文件开头(0)起播;远端源的"从目标位置起播"
                // 是靠 resolve URL 里的 startTimeTicks 让服务端从那里开始转码,本地文件没有这个
                // 服务端环节,起播位置必须由播放器自己 seek 过去。
                playerControl.seekTo(startPositionMs)
            }
            // 顺序要紧:先换基准,再发 Ready。反过来的话观察 Ready 的进度上报可能读到旧基准 + 已归零
            // 的流内位置,报出一个"倒退"的绝对位置。
            currentStartPositionMs = startPositionMs
            currentSourceIsLocalFile = source.isLocalFile
            currentSource = source
            _state.value = PlaybackEngineState.Ready(source, startPositionMs, stateEmissionSeq.incrementAndGet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 迟到的失败同样要丢弃:此刻在播的是**更新的那一次请求**接上的流,把它打成 Error
            // 会让用户在"耳机里正在响"的同时看到「该条目无法播放」。
            if (isStale(token)) return
            _state.value = PlaybackEngineState.Error(itemId)
        }
    }

    /** 见 [requestSeq]:领号之后又有更新的请求进来,这一次的结果就已经没有意义了。 */
    private fun isStale(token: Long): Boolean = requestSeq.get() != token

    /**
     * 见 [AudioPlaybackEngine.reset]。刻意**不**碰 [playerControl]:那个播放器是 `@Singleton`。
     *
     * 领一个新号(见 [requestSeq]):Service 销毁那一刻可能正有一次 resolve 在飞,它落地时播放器
     * 已经被 `stop()` + `clearMediaItems()` 了,再让它 prepare 一条流 + 发 `Ready` 就是凭空复活
     * 一个没人在听的播放会话。
     */
    override fun reset() {
        requestSeq.incrementAndGet()
        currentItemId = null
        currentUserId = null
        currentStartPositionMs = null
        currentSourceIsLocalFile = false
        currentSource = null
        _state.value = PlaybackEngineState.Idle
    }

    override fun release() {
        playerControl.release()
    }
}
