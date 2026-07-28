package dev.insua.jellycast.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.datastore.PreferencesStore
import dev.insua.jellycast.model.AudioTrack
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.SubtitleTimeline
import dev.insua.jellycast.model.SubtitleTrackRef
import dev.insua.jellycast.subtitle.SubtitleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全屏播放页需要知道"现在在放什么":领域层 [MediaItem] 元数据(标题/系列名/季集号/封面)
 * + 该 mediaSource 的音轨与可选文本字幕轨列表(字幕不在音频流里,必须单独知道 index 才能拉取)。
 *
 * 真正把 [dev.insua.jellycast.player.PlaybackSourceResolver] 的解析结果接进来、驱动一次真实播放
 * 会话,是 Task 22(导航装配)的职责。这里只声明契约——单测/预览可以用假实现完全绕开真实服务器。
 */
interface NowPlayingInfo {
    val mediaItem: MediaItem
    val mediaSourceId: String
    val audioTracks: List<AudioTrack>
    val subtitleTracks: List<SubtitleTrackRef>
}

/**
 * 全屏播放页能拿到的"播放会话"最小抽象:一个标准 [Player](本 Task 的 UI 只调用它暴露的
 * play/pause/seek 家族,不绕过去碰裸 ExoPlayer;seek 由 core:player 的
 * [dev.insua.jellycast.player.SeekInterceptingPlayer] 结构性拦截并改写成"重新 resolve + prepare",
 * 这里完全不需要关心)+ 当前播放的领域元数据。
 *
 * 真正连到 [dev.insua.jellycast.player.PlaybackService] 的 MediaController、把 [NowPlayingInfo]
 * 接上真实播放队列,是 Task 22 提供的 Hilt 绑定的职责。
 */
interface PlayerConnection {
    val player: StateFlow<Player?>
    val nowPlaying: StateFlow<NowPlayingInfo?>

    /**
     * **条目内绝对播放位置(ms)**,由 `:core:player` 的 `AudioPlaybackEngine.absolutePositionMs`
     * 提供(全支线复审 Critical 1)。
     *
     * 为什么不直接读 `player.currentPosition`:Spike 实测转码流 `Accept-Ranges: none`,seek 与续播
     * 都实现成"重新 resolve 一条从目标位置开始的新流",于是 `Player.currentPosition` 每换一条流就
     * 从 0 重新开始——它回答的是"这条流里播了多久",不是"这一集听到第几分钟"。字幕时间戳是绝对的,
     * 快进快退的起点也必须是绝对的,所以位置只有这一个权威来源。
     */
    fun absolutePositionMs(): Long

    /**
     * 「播完本集」睡眠定时模式(Finding 1)的落地接缝:真正决定"下一集要不要开始播放"的地方是
     * `:core:player` 的 `AutoPlayNextController.onPlaybackEnded()`(在收到 `STATE_ENDED` 时被调用),
     * 不是这个 ViewModel。这里只负责把用户的选择转发出去——Task 22 提供的真实实现必须把 [armed]
     * 转发到驱动当前播放会话的那个 `AutoPlayNextController` 实例的
     * `armStopAfterCurrentEpisode()` / `disarmStopAfterCurrentEpisode()`,这样"下一集不开始播放"
     * 这件事发生在队列真正推进之前,而不是等 UI 层在收到结束事件后再手忙脚乱地补一个 pause()。
     */
    fun setStopAfterCurrentEpisode(armed: Boolean)

    /**
     * 「下一集」工具栏按钮的落地接缝(修正 §8f):把"跳到播放队列的下一项并开始播放"这件事委派
     * 给真实实现——真实实现操作的是 `:core:player` 的 `PlayQueue` + `AudioPlaybackEngine`,
     * 这里刻意不直接依赖那两个类型,保持 `:feature:player` 不认识具体播放引擎细节的边界。
     * 队列已耗尽(没有下一项)时什么都不做,不崩溃、不报错——和自动连播队列耗尽时的"静默不连播"
     * 是同一个产品语义,只是这里是用户手动触发。
     */
    fun skipToNext()
}

/**
 * 倍速档位表。复审 Important 5:原来是 1.0–2.0,而设计文档 §3.5 明确写的是 **0.5x – 3.0x**。
 * 听不懂的段落要能放慢,这是"把剧当播客听"这个场景的基本诉求,不是可选项。
 * 设置页的滑块也是 0.5–3.0(`SettingsScreen` 的 `valueRange`),两处现在一致了。
 */
internal val playbackSpeedSteps: List<Float> =
    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/**
 * 循环到严格大于 [current] 的下一档,到顶回到最慢档。
 *
 * 容差不能省:倍速是 float 落盘再读回来的,1.25 可能变成 1.2500001——不留容差的话
 * "下一档"会把 1.25 自己算成候选,按一下没反应。
 */
internal fun nextPlaybackSpeed(current: Float): Float =
    playbackSpeedSteps.firstOrNull { it > current + PLAYBACK_SPEED_EPSILON } ?: playbackSpeedSteps.first()

private const val PLAYBACK_SPEED_EPSILON = 0.001f

/**
 * 默认字幕轨选择(设计文档 §3.3)。[tracks] 调用方必须已经排除弹幕轨
 * ([dev.insua.jellycast.model.SubtitleTrackRef.isLikelyDanmaku])——这里不重复过滤,只负责
 * 在"干净"的候选里挑:优先匹配偏好语言,找不到就退到第一条(哪怕语言不匹配也比没有字幕好)。
 * [tracks] 为空(只有弹幕可选,或片源根本没有文本字幕)时返回 null,调用方据此显示空歌词区。
 */
internal fun selectDefaultSubtitleTrack(
    tracks: List<SubtitleTrackRef>,
    preferredLanguage: String?,
): SubtitleTrackRef? = tracks.firstOrNull { it.language == preferredLanguage } ?: tracks.firstOrNull()

/** 没有迫近字幕边界时的轮询基线,和缺陷 3 修复前完全一样——不额外费电。 */
internal const val LYRICS_POLL_INTERVAL_MS = 500L

/** 迫近边界时允许的最短轮询间隔,防止病态输入(边界间距趋近于 0)导致忙轮询耗电。 */
internal const val LYRICS_MIN_POLL_INTERVAL_MS = 80L

/**
 * 缺陷 3(设计文档 §3.5):固定 500ms 轮询漏掉约 85% 的字幕行——很多行比半个轮询周期还短,
 * 采样点两次都落在行外,那一行从未被命中过。
 *
 * 方案(选择"按下一个边界调度",没有选择"无脑提高频率"):不改变没有迫近事件时的轮询节奏
 * ([LYRICS_POLL_INTERVAL_MS],和原来一样),只在
 * [dev.insua.jellycast.model.SubtitleTimeline.nextBoundaryAfter] 报告有一条字幕行的起止边界
 * 即将到来时,把这一次的睡眠精确设成"到那个边界还有多久"(按当前倍速折算),这样每条字幕行的
 * 开始/结束都恰好被采样命中一次,而不是碰运气。
 *
 * 功耗权衡(必须权衡,不能无脑把间隔调到 50ms):naive 50ms 轮询会让 CPU 在整集播放期间
 * 每秒唤醒 20 次。这里的方案是"稀疏、精确的唤醒"——远离边界时维持原来 2Hz 的基线频率,只在
 * 每条字幕行的开始和结束附近各多醒来一次(平均每行 +2 次唤醒),暂停播放或关闭歌词开关时
 * 直接退回基线、不做任何调度计算。[LYRICS_MIN_POLL_INTERVAL_MS] 是地板,防止边界几乎重叠的
 * 病态输入把这个"精确调度"退化成忙轮询。
 */
internal fun nextLyricsPollDelayMs(isPlaying: Boolean, state: PlayerUiState): Long {
    if (!isPlaying || !state.lyricsEnabled) return LYRICS_POLL_INTERVAL_MS
    val boundary = state.subtitleTimeline.nextBoundaryAfter(state.positionMs) ?: return LYRICS_POLL_INTERVAL_MS
    val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
    val untilBoundaryMs = ((boundary - state.positionMs) / speed).toLong()
    return untilBoundaryMs.coerceIn(LYRICS_MIN_POLL_INTERVAL_MS, LYRICS_POLL_INTERVAL_MS)
}

/** 睡眠定时器的可选模式:固定分钟数(墙钟倒计时)或「播完本集」(见设计文档 §3.5)。 */
sealed interface SleepTimerOption {
    data class Minutes(val value: Int) : SleepTimerOption
    data object EndOfEpisode : SleepTimerOption
}

data class PlayerUiState(
    val mediaItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val rewindSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val subtitleTimeline: SubtitleTimeline = SubtitleTimeline(emptyList()),
    val isSubtitleLoading: Boolean = true,
    val subtitleTracks: List<SubtitleTrackRef> = emptyList(),
    val selectedSubtitleTrackIndex: Int? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val sleepTimerOption: SleepTimerOption? = null,
    /** 设置里的「歌词式字幕」开关(复审 Minor 6:此前这个开关是死的,播放页从不查它)。 */
    val lyricsEnabled: Boolean = true,
    /**
     * 全支线复审 Important:候选字幕轨存在,但全部被弹幕信号(标题关键字或解析后密度异常)
     * 排除,最终没有任何轨道能被选中。true 时 UI 应该告诉用户"跳过了一条疑似弹幕的字幕",
     * 而不是让"这一集真的没有字幕"和"有字幕但被误判丢弃了"这两种情况看起来一模一样。
     */
    val subtitleSkippedAsDanmaku: Boolean = false,
)

/**
 * 播放页 ViewModel。歌词状态本身(当前行索引)由 [dev.insua.jellycast.model.SubtitleTimeline.indexAt]
 * 计算,在 [LyricsView] 里直接调用——这里不重复实现一遍查找逻辑,只负责把字幕轨拉下来解析成
 * [SubtitleTimeline] 交给它。
 *
 * 字幕铁律:[SubtitleRepository.load] 任何失败都已经降级成空 timeline;[fetchSubtitleTimeline]
 * 里仍然包了一层兜底 `catch (e: Throwable)`,作为三层防御(见设计文档 §2)的最后一道 ——
 * 不允许把任何字幕相关异常变成播放中断或进程崩溃。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlayerConnection,
    private val subtitleRepository: SubtitleRepository,
    private val preferencesStore: PreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var sleepTimerJob: Job? = null

    init {
        observeNowPlaying()
        observePlaybackState()
        observePreferences()
    }

    private fun observeNowPlaying() {
        viewModelScope.launch {
            connection.nowPlaying.collectLatest { info ->
                val rawTracks = info?.subtitleTracks.orEmpty()
                // 缺陷 1(设计文档 §3.3):弹幕类外挂轨永远不当"字幕"处理——不进入可选列表,
                // 默认选轨、手动循环切换([onCycleSubtitleTrack])都看不到它。这样"选不出真字幕
                // 就不显示字幕"这条规则只用写一处,不会有第二条路径又把弹幕漏进来。这是信号 1
                // (标题关键字,[SubtitleTrackRef.isLikelyDanmaku]);信号 2(密度)在
                // [resolveSubtitle] 里解析完文件之后才二次判定,见该方法 KDoc。
                val realTracks = rawTracks.filterNot { it.isLikelyDanmaku }
                _uiState.update {
                    it.copy(
                        mediaItem = info?.mediaItem,
                        // 元数据总时长是权威(见 resolveDurationMs);换集时立刻生效,不等下一次轮询。
                        durationMs = info?.mediaItem?.runTimeMs?.coerceAtLeast(0L) ?: 0L,
                        audioTracks = info?.audioTracks.orEmpty(),
                        subtitleTracks = realTracks,
                        isSubtitleLoading = info != null,
                    )
                }
                if (info == null) {
                    _uiState.update {
                        it.copy(
                            subtitleTimeline = SubtitleTimeline(emptyList()),
                            isSubtitleLoading = false,
                            selectedSubtitleTrackIndex = null,
                            subtitleSkippedAsDanmaku = false,
                        )
                    }
                } else {
                    val preferredLanguage = preferencesStore.preferredSubtitleLanguage.first()
                    val runTimeMs = info.mediaItem.runTimeMs ?: 0L
                    val resolution = resolveSubtitle(info.mediaItem.id, info.mediaSourceId, runTimeMs, realTracks) { candidates ->
                        selectDefaultSubtitleTrack(candidates, preferredLanguage)
                    }
                    _uiState.update {
                        it.copy(
                            subtitleTracks = resolution.tracks,
                            subtitleTimeline = resolution.timeline,
                            isSubtitleLoading = false,
                            selectedSubtitleTrackIndex = resolution.selectedIndex,
                            // 候选本来就不空,但最终没选出任何一条——全被弹幕信号排除了,这件事
                            // 要暴露给 UI,而不是和"这一集压根没有字幕轨"表现得一模一样。
                            subtitleSkippedAsDanmaku = rawTracks.isNotEmpty() && resolution.selectedIndex == null,
                        )
                    }
                }
            }
        }
    }

    private data class SubtitleResolution(
        val tracks: List<SubtitleTrackRef>,
        val timeline: SubtitleTimeline,
        val selectedIndex: Int?,
    )

    /**
     * 选轨 + 拉取 + 密度二次判定(设计文档 §3.3 信号 2)的共享循环。默认选轨
     * ([observeNowPlaying])和手动循环切换([onCycleSubtitleTrack])都走这一个方法——两者只有
     * "怎么从候选里挑一个"([pick])不同,"挑完发现是弹幕就从候选池永久剔除、换下一个"这套降级
     * 逻辑只写一遍。
     *
     * ## 排序问题:选轨发生在拉取字幕文件之前,密度这时候还不知道
     *
     * 密度只有解析完字幕文件才能算出来,但"选哪一条候选"必须在那之前发生(不然连该拉哪个 index
     * 都不知道)。这里选择**乐观选中,解析后视密度降级**,而不是"选中前先探测每个候选的密度"。
     *
     * 取舍:探测式的方案需要把候选按顺序逐个下载解析,直到找到一个密度正常的才算"选定"——
     * 如果排在最前面的候选(通常也是最可能被选中的那个,例如用户偏好语言匹配到的那条)本来就不是
     * 弹幕,这个方案会白白多等一次往返。乐观方案只在候选**真的**被判定为弹幕时才会触发第二次
     * 请求,绝大多数情况(候选本来就不是弹幕)一次请求就够;代价是候选恰好是弹幕时,用户会经历
     * 一次几乎不可感知的"选中又被换掉"的短暂延迟——但这不违反字幕铁律,播放本身完全不受影响,
     * 最坏情况只是歌词区多转一小会儿。
     *
     * [fetchSubtitleTimeline] 已经把 [SubtitleRepository.load] 的失败(网络异常/非 2xx/解析异常)
     * 兜到空 timeline;空 timeline 的 `isSuspiciouslyDense` 恒为 false,不会被误判成"密度异常"
     * 从而错误地剔除一个只是暂时请求失败的正常轨道。
     */
    private suspend fun resolveSubtitle(
        itemId: String,
        mediaSourceId: String,
        runTimeMs: Long,
        initialCandidates: List<SubtitleTrackRef>,
        pick: (List<SubtitleTrackRef>) -> SubtitleTrackRef?,
    ): SubtitleResolution {
        var candidates = initialCandidates
        while (true) {
            val track = pick(candidates)
            val timeline = fetchSubtitleTimeline(itemId, mediaSourceId, track)
            if (track != null && timeline.isSuspiciouslyDense(runTimeMs)) {
                candidates = candidates.filterNot { it.index == track.index }
                continue
            }
            return SubtitleResolution(candidates, timeline, track?.index)
        }
    }

    /**
     * 字幕铁律落地处:[track] 为 null(无可用文本字幕轨)时直接给空 timeline,不发请求;
     * 有轨道时调 [SubtitleRepository.load] —— 它内部已经把网络异常/非 2xx/解析异常统统降级成
     * 空 timeline。
     *
     * 这里仍然包一层 `catch (e: Throwable)` 兜底(第三层防线,见
     * docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2 的"防御纵深"表格)。
     * 不是因为不信任 [SubtitleRepository.load] 的契约,而是因为调用方的
     * `viewModelScope.launch` 本身没有 `CoroutineExceptionHandler`——任何在这条调用链上
     * 逃逸的 `Throwable`(哪怕来自未来的重构、来自这个方法之外目前想不到的路径)都会直接杀掉
     * 进程,而不只是让字幕消失。字幕是纯装饰功能,失败必须收敛成"无字幕"的 UI 状态,不能变成
     * 崩溃。`CancellationException` 仍然无条件重抛,不属于这里要兜底的范畴。
     */
    private suspend fun fetchSubtitleTimeline(itemId: String, mediaSourceId: String, track: SubtitleTrackRef?): SubtitleTimeline {
        if (track == null) return SubtitleTimeline(emptyList())
        return try {
            subtitleRepository.load(itemId, mediaSourceId, track.index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SubtitleTimeline(emptyList())
        }
    }

    /**
     * media3 [Player] 没有原生的位置 Flow,这里用一个轻量轮询把 isPlaying/position/duration
     * 同步进 [uiState]。用 collectLatest 绑定到具体的 player 实例——一旦 [PlayerConnection.player]
     * 发出新值(重连/置空),旧的轮询协程自动取消,不会有两个轮询并存。
     */
    /**
     * 位置与时长的来源(全支线复审 Critical 1):
     * - `positionMs` 读 [PlayerConnection.absolutePositionMs] —— **不是** `player.currentPosition`
     *   (那是转码流内的相对位置,每次 seek/续播归零,见该方法 KDoc)。歌词定位、进度条、快进快退
     *   全部基于这个值,所以三者不可能互相矛盾。
     * - `durationMs` 以元数据 [MediaItem.runTimeMs] 为权威,只在元数据缺失时才回退到
     *   `player.duration`;chunked AAC 转码流的 `duration` 常常是 `C.TIME_UNSET`(负数),
     *   直接用会让进度条钉在 100%、一拖就从头播。
     */
    private fun observePlaybackState() {
        viewModelScope.launch {
            connection.player.collectLatest { player ->
                if (player == null) return@collectLatest
                while (currentCoroutineContext().isActive) {
                    _uiState.update {
                        it.copy(
                            isPlaying = player.isPlaying,
                            positionMs = connection.absolutePositionMs().coerceAtLeast(0L),
                            durationMs = resolveDurationMs(it.mediaItem, player),
                            playbackSpeed = player.playbackParameters.speed,
                        )
                    }
                    // 缺陷 3(设计文档 §3.5):不再固定 500ms 碰运气,按下一个字幕边界自适应调度——
                    // 见 [nextLyricsPollDelayMs] KDoc 里的功耗权衡说明。
                    delay(nextLyricsPollDelayMs(player.isPlaying, _uiState.value))
                }
            }
        }
    }

    private fun resolveDurationMs(mediaItem: MediaItem?, player: Player): Long {
        mediaItem?.runTimeMs?.takeIf { it > 0L }?.let { return it }
        return player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesStore.rewindSeconds.collectLatest { seconds -> _uiState.update { it.copy(rewindSeconds = seconds) } }
        }
        viewModelScope.launch {
            preferencesStore.forwardSeconds.collectLatest { seconds -> _uiState.update { it.copy(forwardSeconds = seconds) } }
        }
        // 复审 Minor 6:歌词开关。字幕照常拉取解析(它已经是"任何失败都降级为空 timeline"的,
        // 关掉开关只影响渲染),这样用户在播放中打开开关就能立刻看到歌词,不用退出重进。
        viewModelScope.launch {
            preferencesStore.lyricsEnabled.collectLatest { enabled -> _uiState.update { it.copy(lyricsEnabled = enabled) } }
        }
    }

    fun onPlayPause() {
        val player = connection.player.value ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * 点击歌词行 / 拖动进度条统一走这一个方法——都只调标准 [Player.seekTo],绝不自己实现 seek
     * 逻辑。真正"转码流不支持 Range,必须重新 resolve+prepare"的处理在 core:player 的
     * SeekInterceptingPlayer 里,这层完全不用关心。
     */
    fun onSeek(positionMs: Long) {
        connection.player.value?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * 快退/快进的起点是**绝对位置**(复审 Critical 1)。用 `player.currentPosition` 的旧实现在
     * 从 8:00 续播后会算出 0:30——往回跳 7 分半;钳制上界也必须用元数据总时长,不是
     * `player.duration`(转码流常为 `C.TIME_UNSET`)。
     */
    fun onSkipBack() {
        if (connection.player.value == null) return
        val target = connection.absolutePositionMs() - _uiState.value.rewindSeconds * 1000L
        onSeek(target.coerceAtLeast(0L))
    }

    fun onSkipForward() {
        if (connection.player.value == null) return
        val duration = _uiState.value.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = connection.absolutePositionMs() + _uiState.value.forwardSeconds * 1000L
        onSeek(target.coerceAtMost(duration))
    }

    /**
     * 倍速切换。除了立刻作用到当前播放器,还要写回偏好——`PlaybackService` 订阅
     * `PreferencesStore.playbackSpeed` 并应用到播放器(复审 Important 5),所以这个写入既是
     * "记住上次设置",也是让设置页与播放页两条路最终收敛到同一个值。
     */
    fun onCycleSpeed() {
        val player = connection.player.value ?: return
        val next = nextPlaybackSpeed(_uiState.value.playbackSpeed)
        player.setPlaybackSpeed(next)
        viewModelScope.launch { preferencesStore.setPlaybackSpeed(next) }
    }

    /**
     * 音轨切换(修正 §8f):在 [Player.getCurrentTracks] 报告的音频轨道组里循环切换选中项,用
     * Media3 现代 API [Player.trackSelectionParameters] 的 [TrackSelectionOverride] 覆盖选择,
     * 不触碰底层 ExoPlayer 内部状态。
     *
     * 诚实说明:**当前两条投递路径都只吐一条音轨,所以这里在生产上基本恒为 no-op**
     * (`audioGroups.size <= 1` 直接返回)——这是符合预期的行为,不是 bug。
     * - L1(`/Audio/{id}/universal`):服务端转码/remux 成的单一音频输出。
     * - L3(`/Videos/{id}/stream`):曾经带 `static=true` 直通原始容器,多条音轨确实都在流里;
     *   但那条路会让服务端**静默忽略 `startTimeTicks`**,L3 上的 seek 变成空操作(实测证据见
     *   `PlaybackSourceResolver.buildVideoStreamUrl` 的 KDoc)。为了让 seek 真的生效,L3 已改走
     *   非 static 的转码路径,代价就是原容器的多音轨只剩服务端选中的那一条。
     *
     * 要在 L3 上重新支持选音轨,得走服务端选轨(`/Videos/{id}/stream` 的 `audioStreamIndex` 参数)
     * 而不是客户端 TrackSelector,那是独立的一件事,不在本方法的范围内。
     */
    fun onCycleAudioTrack() {
        val player = connection.player.value ?: return
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.size <= 1) return

        val currentIndex = audioGroups.indexOfFirst { group -> (0 until group.length).any { group.isTrackSelected(it) } }
        val next = audioGroups[(currentIndex + 1).mod(audioGroups.size)]

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, 0))
            .build()
    }

    /** 「下一集」工具栏按钮(修正 §8f):转发给 [PlayerConnection.skipToNext]。 */
    fun onSkipToNext() {
        connection.skipToNext()
    }

    /**
     * 字幕语言:在已知的文本字幕轨里循环切换到"当前选中的下一条",重新拉取解析,并记住偏好供
     * 下次默认选中。复用 [resolveSubtitle] 的密度降级循环——手动选中的下一条如果标题信号没拦住
     * 但解析后密度异常,同样不会被留下当"当前字幕",而是继续跳到再下一个候选,和默认选轨遵循
     * 同一条规则(这就是两者共用 [resolveSubtitle] 而不是各写一遍的原因)。
     */
    fun onCycleSubtitleTrack() {
        val state = _uiState.value
        val tracks = state.subtitleTracks
        if (tracks.isEmpty()) return
        val mediaItem = state.mediaItem ?: return
        val mediaSourceId = connection.nowPlaying.value?.mediaSourceId ?: return
        val runTimeMs = mediaItem.runTimeMs ?: 0L
        val startIndex = state.selectedSubtitleTrackIndex

        viewModelScope.launch {
            _uiState.update { it.copy(isSubtitleLoading = true) }
            val resolution = resolveSubtitle(mediaItem.id, mediaSourceId, runTimeMs, tracks) { candidates ->
                if (candidates.isEmpty()) {
                    null
                } else {
                    val position = candidates.indexOfFirst { it.index == startIndex }
                    candidates[(position + 1).mod(candidates.size)]
                }
            }
            resolution.selectedIndex?.let { selectedIndex ->
                val language = resolution.tracks.first { it.index == selectedIndex }.language
                preferencesStore.setPreferredSubtitleLanguage(language)
            }
            _uiState.update {
                it.copy(
                    subtitleTracks = resolution.tracks,
                    subtitleTimeline = resolution.timeline,
                    isSubtitleLoading = false,
                    selectedSubtitleTrackIndex = resolution.selectedIndex,
                    subtitleSkippedAsDanmaku = tracks.isNotEmpty() && resolution.selectedIndex == null,
                )
            }
        }
    }

    /**
     * 睡眠定时器统一入口。固定分钟数模式的行为和之前一样——本地起一个墙钟倒计时协程,到点
     * `pause()`。「播完本集」模式(Finding 1)完全不同路:不起协程、不等墙钟时间,而是立即把
     * 武装信号转发给 [PlayerConnection.setStopAfterCurrentEpisode] ——真正的停止逻辑在
     * `AutoPlayNextController` 里,见该接口方法的文档。
     *
     * 切换到任何新选项(包括关闭)都先无条件 disarm 一次「播完本集」,再按新选项决定要不要重新
     * 武装/起新的倒计时——这样不会出现"选了播完本集又切回 15 分钟,但播完本集的武装还留着"的
     * 状态泄漏。
     */
    fun onSetSleepTimer(option: SleepTimerOption?) {
        sleepTimerJob?.cancel()
        connection.setStopAfterCurrentEpisode(false)
        _uiState.update { it.copy(sleepTimerOption = option) }
        when (option) {
            null -> Unit
            is SleepTimerOption.EndOfEpisode -> connection.setStopAfterCurrentEpisode(true)
            is SleepTimerOption.Minutes -> {
                sleepTimerJob = viewModelScope.launch {
                    delay(option.value * 60_000L)
                    connection.player.value?.pause()
                    _uiState.update { it.copy(sleepTimerOption = null) }
                }
            }
        }
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        super.onCleared()
    }
}
