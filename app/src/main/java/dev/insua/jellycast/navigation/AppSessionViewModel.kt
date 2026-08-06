package dev.insua.jellycast.navigation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AutoPlayNextController
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.PlaybackSequenceEnd
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.PlayQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 断网时点播放给用户看的话(设计文档 §3.2 第四行)。
 *
 * 措辞刻意点明「服务器」:用户能采取的行动是"把 VPN 打开 / 回到能连上服务器的网络",
 * 而不是"重试"。不带任何技术细节(不出现 endpoint、超时、异常类名)。
 */
const val PLAYBACK_REQUIRES_SERVER_MESSAGE = "需要连接服务器才能播放"

/** 整部剧的所有季所有集都播完时给用户的话(2026-07-29 用户需求)。 */
const val SERIES_COMPLETED_MESSAGE = "已播放完"

/** 「这一串播完了」在导航层该产生的动作。 */
internal data class PlaybackSequenceEndEffect(val returnToHome: Boolean, val message: String?)

/**
 * `:core:player` 的 [PlaybackSequenceEnd] → 导航层动作的映射。
 *
 * **这是模块边界的落点**(设计文档 §5):`:core:player` 只说"发生了什么"——整部剧播完了 /
 * 单条目播完了——它不认识导航,也不该认识。"回哪个页面、弹什么提示"是这一侧的决定,
 * 而且是个纯函数,离线可单测(项目铁律 6)。
 */
internal fun playbackSequenceEndEffect(end: PlaybackSequenceEnd?): PlaybackSequenceEndEffect? = when (end) {
    null -> null
    // 剧集全部播完:回首页 + 提示。
    PlaybackSequenceEnd.SERIES_COMPLETED ->
        PlaybackSequenceEndEffect(returnToHome = true, message = SERIES_COMPLETED_MESSAGE)
    // 电影:回首页,不必额外提示——用户自己点开的单部片子,播完了是意料之中的事。
    PlaybackSequenceEnd.ITEM_COMPLETED ->
        PlaybackSequenceEndEffect(returnToHome = true, message = null)
}

/** 常驻迷你播放条(修正 §4/§9)需要的最小展示状态。 */
data class MiniPlayerUiState(
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val isPlaying: Boolean,
    val progress: Float,
    // 队列还有没有下一条可以推进(设计文档 §3.6:迷你条下一集按钮)。直接读 Player.hasNextMediaItem()——
    // 这个值是 SeekInterceptingPlayer 按 QueueNavigator.hasNext() 声明 COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
    // 之后 Media3 自己算出来的,和通知栏/锁屏"下一集"按钮是否出现同一个权威来源,不会互相打架。
    val hasNext: Boolean = false,
)

/**
 * 迷你条进度比例。纯函数,可离线单测(项目铁律 6)。
 *
 * 复审 Critical 1:[positionMs] 必须是 `AudioPlaybackEngine.absolutePositionMs` 给的**绝对**位置,
 * [durationMs] 必须是元数据 `MediaItem.runTimeMs` —— 转码流的 `Player.currentPosition` 每次 seek/
 * 续播归零、`Player.duration` 常是 `C.TIME_UNSET`,两个都不能用。
 *
 * 时长未知(null / <= 0)时返回 0f 而不是除零或钉在 100%。
 */
internal fun miniPlayerProgress(positionMs: Long, durationMs: Long?): Float {
    if (durationMs == null || durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * [JellyCastNavHost] 顶层需要、但不属于任何单一 `:feature` 模块的会话/播放启动状态:
 * - 起点判定([startDestination]):有已登录服务器 → `home`,否则 → `servers`(修正 §9)。
 * - 常驻迷你播放条的数据源([miniPlayer])。
 * - 从 Home/Library 点击条目 → 真正驱动一次播放([play])——这是"打开 App 到开始播放'下一集'
 *   不超过 3 次点击"这个设计目标在导航层的落地:启动 [PlaybackService]、把队列交给 [PlayQueue]、
 *   调 [AudioPlaybackEngine.play]。
 */
@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val serverStore: ServerStore,
    private val session: JellyfinSession,
    private val playQueue: PlayQueue,
    private val audioPlaybackEngine: AudioPlaybackEngine,
    private val autoPlayNextController: AutoPlayNextController,
    private val playerConnection: PlayerConnection,
    private val lastPlayedStore: LastPlayedStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _miniPlayer = MutableStateFlow<MiniPlayerUiState?>(null)
    val miniPlayer: StateFlow<MiniPlayerUiState?> = _miniPlayer.asStateFlow()

    /**
     * 一条待展示给用户的提示(Snackbar),null = 没有。
     *
     * 目前唯一的来源是"点了播放但连不上服务器"(设计文档 §3.2 第四行)。用 [StateFlow] 而不是
     * 一次性 Channel:导航层用 `LaunchedEffect(message)` 消费,展示完调 [onMessageShown] 清空,
     * 旋转屏幕/重组不会重复弹,也不会因为此刻没有订阅者而把提示整个丢掉。
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 「播完了,回首页」——true = 导航层该把播放页收起来并回到首页。
     *
     * 和 [message] 同一个模式(StateFlow + 消费后清空):导航层用 `LaunchedEffect` 消费,
     * 处理完调 [onReturnToHomeHandled]。旋转屏幕/重组不会重复导航,也不会因为此刻没有订阅者
     * 而把信号整个丢掉。
     */
    private val _returnToHome = MutableStateFlow(false)
    val returnToHome: StateFlow<Boolean> = _returnToHome.asStateFlow()

    /**
     * 见 [restoreLastPlayed] / [observeMiniPlayer]:恢复出来的迷你条还没有对应的真实播放会话时为
     * `true` —— 这是两者之间的握手位,防止 [observeMiniPlayer] 在 [PlayerConnection.nowPlaying]
     * 还是 `null`(冷启动、真播放从未发生过)这个必然会先收到一次的初始值上,把刚恢复出来的状态
     * 冲成 `null`。一旦真实播放开始([observeMiniPlayer] 收到非 null 的 `info`)就永久置回 `false`,
     * 之后再收到 `null`(真会话断开)就照旧清空迷你条,和改动前的行为一致。
     */
    private var restoredWithoutSession = false

    /**
     * [restoreLastPlayed] 恢复出来的那个条目的 id,只在 [restoredWithoutSession] 仍是 `true`
     * 期间有意义。
     *
     * [observeBaseUrlForRestoredPoster] 用它核对"此刻 [playQueue] 头部的条目,是不是当初被恢复、
     * 现在还摆在 `_miniPlayer` 里的那一个"——光看 [restoredWithoutSession] 不够:用户完全可能在
     * baseUrl 解析完成**之前**就点开了另一个条目 B。[play] 会先同步把 [playQueue] 换成 B,
     * 但要等 `audioPlaybackEngine.play(...)` 的网络往返落地、[observeMiniPlayer] 收到真实
     * `nowPlaying` 之后才会碰 `_miniPlayer`/[restoredWithoutSession]——这个窗口期里
     * `playQueue.current` 已经是 B,`_miniPlayer` 却还是 A 的快照。这时候若只按
     * `playQueue.current.value` 取条目去补封面,补出来的会是"A 的标题/副标题 + B 的封面"。
     */
    private var restoredItemId: String? = null

    init {
        viewModelScope.launch {
            val activeId = serverStore.activeServerId.first()
            _startDestination.value = if (activeId != null) Routes.HOME else Routes.SERVERS
            if (activeId != null) refreshBaseUrl()
        }
        restoreLastPlayed()
        observeMiniPlayer()
        observeBaseUrlForRestoredPoster()
        observePlaybackFailure()
        observePlaybackSequenceEnd()
    }

    /**
     * 冷启动恢复迷你播放条(设计文档 §3):不用先进首页找到"继续收听"再点进去,迷你条直接就是
     * 上次那一集、停在离开时的位置。
     *
     * 🔴 三条硬约束(任务报告有变异验证):
     * 1. **绝不自动开始播放** —— 这里只读 [LastPlayedStore],不调 [AudioPlaybackEngine.play],
     *    `isPlaying` 恒为 `false`。真正开始播放只发生在用户点了迷你条播放按钮之后([onMiniPlayerPlayPause])。
     * 2. **绝不启动前台服务、绝不发通知** —— 同样因为这里不碰 [audioPlaybackEngine] / [context]
     *    里任何会拉起 `PlaybackService` 的调用。
     * 3. **绝不发网络请求** —— [LastPlayedStore] 是纯本地 DataStore,不碰 [session]/[JellyfinApi]。
     *    断网、退出登录、服务器不可达,这条路都能工作。
     *
     * [MediaItem.resumePositionMs] 设成记录里的位置(关键复用,见类注释):点迷你条播放时走的是
     * 和首页点条目完全相同的 [play] 路径,那条路已经用这个字段当播放起点,不需要另开一条特殊逻辑。
     *
     * `kind` 解析自 [LastPlayed.kind](复审 Task 5 Important 2 之前这里固定给过 [MediaKind.EPISODE]、
     * 并声称"真实类型会在播放开始后被覆盖"——**这句话是假的**:`AudioPlaybackEngineImpl.play()`
     * 根本不碰 [PlayQueue],没有任何地方会在播放开始后回填这个字段。电影是一等公民,恢复出来的
     * 电影如果被当成剧集,播完时 [dev.insua.jellycast.player.AutoPlayNextController.onPlaybackEnded]
     * 会去找"下一集"而不是判定为整部片子播完,"播完回首页"永远不会触发。`valueOf` 失败(未来
     * 新增枚举值、记录被手工改坏等边缘情况)时退化成 [MediaKind.EPISODE]——这条路径本来就是
     * "尽力而为的本地缓存",容错值随手挑一个不会崩的即可,不值得为它另设一种更精确的降级。
     *
     * `seriesName`/`seasonNumber`/`episodeNumber`/`seriesId`/`seasonId`(Task 1,2026-08-06)同样
     * 原样从 [record] 回填:播放页顶栏读 `MediaItem.seriesName`、副标题靠
     * `MediaItem.displaySubtitle` 从季集编号现拼,不回填这五个字段的话恢复出来的迷你条点开播放页
     * 后顶栏剧名和 S01E02 副标题都是空的;`seriesId`/`seasonId` 还决定离线时自动连播/缓存预取的
     * 兜底路径是否可用(两者拿不到这两个字段都会去发一次网络请求找剧集归属,断网时那次兜底也失败)。
     */
    private fun restoreLastPlayed() {
        viewModelScope.launch {
            val record = lastPlayedStore.lastPlayed.first() ?: return@launch
            val item = MediaItem(
                id = record.itemId,
                kind = runCatching { MediaKind.valueOf(record.kind) }.getOrDefault(MediaKind.EPISODE),
                name = record.title,
                seriesName = record.seriesName,
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,
                runTimeMs = record.runTimeMs,
                resumePositionMs = record.positionMs,
                imageTag = record.imageTag,
                seriesId = record.seriesId,
                seasonId = record.seasonId,
            )
            playQueue.setQueue(listOf(item), 0)
            restoredWithoutSession = true
            restoredItemId = item.id
            _miniPlayer.value = MiniPlayerUiState(
                title = record.title,
                subtitle = record.subtitle,
                posterUrl = baseUrl.value.takeIf { it.isNotBlank() }?.let { item.posterUrl(it) },
                isPlaying = false,
                progress = miniPlayerProgress(record.positionMs, record.runTimeMs),
                hasNext = false,
            )
        }
    }

    /**
     * baseUrl 就绪后,给 [restoreLastPlayed] 恢复出来的迷你条补上封面(设计文档 §3.2)。
     *
     * **背景:** [restoreLastPlayed] 在 `init` 里跑,读 [baseUrl] 时它还是初始空串——
     * [refreshBaseUrl] 是另一条独立异步,此刻多半还没跑完。于是 `posterUrl` 算出来是 `null`,
     * 而 `_miniPlayer` 是一次性快照,`baseUrl` 后来解析出来了也没人重算。这里就是那个"重算"。
     *
     * **和 [observeMiniPlayer] 的边界(读者需要先确认再改这两个函数)：**
     * 只要 [restoredWithoutSession] 还是 `true`——也就是真实播放会话还没接管 `_miniPlayer`——
     * 这里就在 [baseUrl] 每次变化时尝试给"恢复出来的那个快照"补 `posterUrl`(不改
     * `title`/`subtitle`/`progress` 等其余字段)。一旦 [observeMiniPlayer] 收到过一次非 `null`
     * 的 `nowPlaying`([restoredWithoutSession] 因此永久置回 `false`),这个函数即便之后还会被
     * [baseUrl] 的新值触发,也会在第一行直接短路退出——绝不再碰 `_miniPlayer`。单线程 dispatcher
     * 下两条协程本就不会真的同时写同一个 `StateFlow`,但这不代表状态一定正确——见下一段。
     *
     * **必须核对身份,不能只信 [playQueue] 头部是"恢复出来的那个条目"。** 复审 Critical:
     * 用户完全可能在 baseUrl 解析完成**之前**就点开了另一个条目 B——[play] 会先同步把
     * [playQueue] 换成 B,直到 `audioPlaybackEngine.play(...)` 的网络往返落地、
     * [observeMiniPlayer] 收到真实 `nowPlaying` 之前都不会碰 `_miniPlayer`/[restoredWithoutSession]。
     * 那个窗口期里 `playQueue.current` 已经是 B,`_miniPlayer` 却还是 A 的快照;若这里直接拿
     * `playQueue.current.value` 当"要补封面的条目",补出来的就是"A 的标题/副标题 + B 的封面"。
     * 用 [restoredItemId] 核对 [playQueue] 头部条目的 id 是否仍是当初恢复出来的那一个,
     * 不一致就放弃这次补写——宁可继续显示"没有封面",也不能显示"错的封面"。
     *
     * **不引入网络请求。** 只订阅已有的 [baseUrl] `StateFlow`,不主动触发选路——那是
     * [refreshBaseUrl] 的职责,兜底顺序见其 KDoc,后两级都不联网。
     */
    private fun observeBaseUrlForRestoredPoster() {
        viewModelScope.launch {
            baseUrl.collect { url ->
                if (!restoredWithoutSession) return@collect
                if (url.isBlank()) return@collect
                val restoredItem = playQueue.current.value ?: return@collect
                if (restoredItem.id != restoredItemId) return@collect
                val current = _miniPlayer.value ?: return@collect
                _miniPlayer.value = current.copy(posterUrl = restoredItem.posterUrl(url))
            }
        }
    }

    /** 提示已经展示过了,清空,免得下一次重组又弹一遍。 */
    fun onMessageShown() {
        _message.value = null
    }

    /** 导航层已经回到首页了,清空信号。 */
    fun onReturnToHomeHandled() {
        _returnToHome.value = false
    }

    /**
     * "这一串播完了"(整部剧播完 / 电影播完)时回首页并按需提示 —— 见 [playbackSequenceEndEffect]。
     *
     * 信号来自 `:core:player` 的 [AutoPlayNextController.sequenceEnd]:那一侧只说"发生了什么",
     * 由这里翻译成导航动作,`:core:player` 因此始终不认识导航(设计文档 §5)。
     */
    private fun observePlaybackSequenceEnd() {
        viewModelScope.launch {
            autoPlayNextController.sequenceEnd.collect { end ->
                val effect = playbackSequenceEndEffect(end) ?: return@collect
                effect.message?.let { _message.value = it }
                if (effect.returnToHome) _returnToHome.value = true
                autoPlayNextController.onSequenceEndHandled()
            }
        }
    }

    /**
     * 引擎解析播放源失败时给出提示。
     *
     * 为什么光靠 [play] 里那次 `runCatching` 不够:进程在联网时已经解析过 endpoint,
     * [JellyfinSession] 会一直复用那份缓存,所以"用着用着断网再点播放"这条路上
     * `session.userId()` 照样成功,失败发生在更里面的 `AudioPlaybackEngine.resolve()` ——
     * 而它按设计**不抛异常**,只是把状态置成 [PlaybackEngineState.Error](故意的:迟到的失败
     * 不许打断正在响的那条流)。不监听这个状态,用户点下播放就是彻底的"没反应"。
     */
    private fun observePlaybackFailure() {
        viewModelScope.launch {
            audioPlaybackEngine.state.collect { state ->
                if (state is PlaybackEngineState.Error) _message.value = PLAYBACK_REQUIRES_SERVER_MESSAGE
            }
        }
    }

    /**
     * 登录/添加服务器成功后由导航层调用,让首页/媒体库尽快拿到正确的封面 baseUrl。
     *
     * 顺带清掉本地的「上次播放」记录并重置进程内已经装填好的迷你条/播放队列(设计文档 §3
     * 「记录失效」+ 复审 Task 5 Important 1):这条记录属于**上一次**登录的服务器,连上一台
     * 新的(或换一台)服务器之后继续把它摆在迷你条上没有意义——轻则条目对不上这台服务器,
     * 重则续播时拿着别的服务器的 itemId 去发请求。
     *
     * **只清 [lastPlayedStore] 这一份磁盘记录不够。** [restoreLastPlayed] 在 `init` 里已经把
     * 上一台服务器的记录灌进了 [_miniPlayer] 和 [playQueue]——这两个是这次进程存活期间的
     * 内存状态,`lastPlayedStore.clear()` 清的是磁盘上的下一次冷启动,两者互不相干、互不覆盖。
     * 复审发现的可复现路径:设置 → 管理服务器 → 删除当前活跃服务器 → 连一台新服务器 → 回首页——
     * 迷你条会继续显示旧服务器那一集,点播放会把旧服务器的 itemId 发给刚连上的新服务器。
     * 同时把 [restoredWithoutSession] 置回 `false`:否则 [observeMiniPlayer] 收到的下一次
     * `nowPlaying == null`(真实会话此刻确实不存在)会被误判成"还没来得及覆盖恢复状态"而放过
     * 这次本该执行的清空。
     */
    fun onServerConnected() {
        refreshBaseUrl()
        _miniPlayer.value = null
        playQueue.setQueue(emptyList(), 0)
        restoredWithoutSession = false
        restoredItemId = null
        viewModelScope.launch { lastPlayedStore.clear() }
    }

    /**
     * 解析出用于拼封面 URL 的 baseUrl。
     *
     * **断网时必须有兜底。** [JellyfinSession.baseUrl] 走的是并发选路,断网必然失败;而这个值一旦
     * 是空串,[dev.insua.jellycast.feature.home.HomeScreen] / `LibraryScreen` 就**根本不会去拼图片
     * URL**(见它们的 `imageUrl = if (baseUrl.isBlank()) null else ...`),于是 Coil 连缓存键都拿不到,
     * 磁盘缓存里明明躺着这些封面也一张都用不上 —— 列表内容从 Room 缓存里出来了,却是一整屏灰色
     * 占位图,和白屏差不了多少。真机验证时看到的正是这个现象。
     *
     * 兜底顺序:
     * 1. 这一刻选路的结果(联网时的正常路径);
     * 2. 本进程上一次成功解析的地址([JellyfinSession.cachedBaseUrlOrNull])—— 覆盖"用着用着断网";
     * 3. 激活服务器优先级最高的那个 endpoint —— 覆盖"冷启动就没网"。
     *
     * 后两条给出的地址此刻当然是连不上的,但这**正是想要的**:URL 只是 Coil 的缓存键,
     * 命中磁盘缓存的请求根本不会走网络;没命中的那张本来也加载不出来,退回占位图,和现在一样。
     */
    private fun refreshBaseUrl() {
        viewModelScope.launch {
            val resolved = runCatching { session.baseUrl() }.getOrNull()
                ?: session.cachedBaseUrlOrNull()
                ?: activeServerFallbackBaseUrl()
            if (!resolved.isNullOrBlank()) _baseUrl.value = resolved
        }
    }

    private suspend fun activeServerFallbackBaseUrl(): String? = runCatching {
        val activeId = serverStore.activeServerId.first() ?: return@runCatching null
        serverStore.servers.first()
            .find { it.id == activeId }
            ?.endpoints
            ?.minByOrNull { it.priority }
            ?.url
    }.getOrNull()

    /**
     * 和 [dev.insua.jellycast.feature.player.PlayerViewModel.observePlaybackState] 同样的
     * "绑定 nowPlaying 生命周期的轻量轮询"模式:一旦 [PlayerConnection.nowPlaying] 变化(新条目/
     * 置空),[collectLatest] 自动取消上一个轮询协程,不会有多个轮询并存。
     *
     * **和 [observeBaseUrlForRestoredPoster] 的边界**见后者 KDoc:一旦这里收到过一次非 `null` 的
     * `info`([restoredWithoutSession] 置回 `false`),`_miniPlayer` 之后只由这个函数写,
     * [observeBaseUrlForRestoredPoster] 会自行短路退出,不会再插手。
     */
    private fun observeMiniPlayer() {
        viewModelScope.launch {
            playerConnection.nowPlaying.collectLatest { info ->
                if (info == null) {
                    // 见 [restoredWithoutSession]:恢复出来但还没真正开始播的迷你条不能被这里冲掉。
                    if (!restoredWithoutSession) _miniPlayer.value = null
                    return@collectLatest
                }
                restoredWithoutSession = false
                val posterUrl = baseUrl.value.takeIf { it.isNotBlank() }?.let { info.mediaItem.posterUrl(it) }
                while (currentCoroutineContext().isActive) {
                    val player = playerConnection.player.value
                    _miniPlayer.value = MiniPlayerUiState(
                        title = info.mediaItem.name,
                        subtitle = info.mediaItem.displaySubtitle,
                        posterUrl = posterUrl,
                        isPlaying = player?.isPlaying == true,
                        // 复审 Critical 1:位置走引擎的绝对位置、时长走元数据 runTimeMs。
                        progress = miniPlayerProgress(
                            positionMs = audioPlaybackEngine.absolutePositionMs,
                            durationMs = info.mediaItem.runTimeMs,
                        ),
                        hasNext = player?.hasNextMediaItem() == true,
                    )
                    delay(MINI_PLAYER_POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 迷你条播放/暂停按钮。
     *
     * 「恢复出来但还没真正开始播」是 [restoreLastPlayed] 引入的新状态:此时根本没有 MediaController
     * 会话可以切换([playerConnection.player] 仍是 `null`),必须改为发起一次真正的播放——复用和
     * 首页点条目完全相同的 [play] 路径,起点是 [restoreLastPlayed] 里塞进 [MediaItem.resumePositionMs]
     * 的记录位置。
     *
     * 判据用 [AudioPlaybackEngine.currentItemId] 是否为空,**不用** `player?.isPlaying`:一个被用户
     * 手动暂停的真实会话同样 `isPlaying == false`,那种情况必须走下面的切换分支,不能被误判成
     * "还没开始播"而重新起播,把用户暂停的位置弄丢。
     */
    fun onMiniPlayerPlayPause() {
        val restoredItem = playQueue.current.value
        if (audioPlaybackEngine.currentItemId == null && restoredItem != null) {
            play(restoredItem, listOf(restoredItem))
            return
        }
        playerConnection.player.value?.let { player -> if (player.isPlaying) player.pause() else player.play() }
    }

    /** 迷你条下一集按钮:复用和通知栏/锁屏/全屏播放页同一条路径([PlayerConnection.skipToNext])。 */
    fun onMiniPlayerSkipNext() {
        playerConnection.skipToNext()
    }

    /**
     * 首页/媒体库点条目播放的唯一入口(见类注释)。[queue] 必须是整季/单集自身,由调用方决定。
     *
     * **断网时不许静默失败。** 解析不出会话(冷启动 + 没网 → 一轮选路探测全部失败)时,
     * 以前是 `?: return@launch`,用户点下去什么也不会发生、也没有任何解释 —— 那和闪退一样,
     * 都是用户口中的"点了没用"。现在收敛到 [message](设计文档 §3.2 第四行)。
     *
     * 请求发出之后的失败(引擎解析播放源失败)由 [observePlaybackFailure] 兜住,不在这里。
     */
    fun play(item: MediaItem, queue: List<MediaItem>) {
        viewModelScope.launch {
            playQueue.setQueue(queue, queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            val userId = runCatching { session.userId() }.getOrNull()
            if (userId == null) {
                _message.value = PLAYBACK_REQUIRES_SERVER_MESSAGE
                return@launch
            }
            _message.value = null
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            audioPlaybackEngine.play(item.id, userId, item.resumePositionMs)
            refreshBaseUrl()
        }
    }

    private companion object {
        const val MINI_PLAYER_POLL_INTERVAL_MS = 500L
    }
}
