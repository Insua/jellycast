package dev.insua.jellycast.navigation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.model.MediaItem
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

    init {
        viewModelScope.launch {
            val activeId = serverStore.activeServerId.first()
            _startDestination.value = if (activeId != null) Routes.HOME else Routes.SERVERS
            if (activeId != null) refreshBaseUrl()
        }
        observeMiniPlayer()
        observePlaybackFailure()
        observePlaybackSequenceEnd()
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

    /** 登录/添加服务器成功后由导航层调用,让首页/媒体库尽快拿到正确的封面 baseUrl。 */
    fun onServerConnected() {
        refreshBaseUrl()
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
     */
    private fun observeMiniPlayer() {
        viewModelScope.launch {
            playerConnection.nowPlaying.collectLatest { info ->
                if (info == null) {
                    _miniPlayer.value = null
                    return@collectLatest
                }
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

    fun onMiniPlayerPlayPause() {
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
