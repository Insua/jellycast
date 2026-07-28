package dev.insua.jellycast.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "把播放位置移动到 positionMs"这件事的最小抽象。生产实现见 [EngineSeekRouter](委派给
 * [AudioPlaybackEngine.seekTo] —— 重新 resolve + prepare)。单测里可以直接用 lambda 造假,
 * 完全绕开 AudioPlaybackEngine/ExoPlayer。
 */
fun interface SeekRouter {
    fun seekTo(positionMs: Long)
}

/**
 * 生产环境的 [SeekRouter]:把 seek 委派给 [AudioPlaybackEngine.seekTo]。
 *
 * `engine.seekTo` 是 suspend(内部要重新调 [PlaybackSourceProvider.resolve] 换一个新 URL 再
 * setMediaItem + prepare),而 [Player] 接口的 seek 家族方法都是同步调用(通常在应用主线程 /
 * MediaSession 的 Binder 线程上),所以这里用调用方传入的 [scope] 发起协程,不阻塞调用线程。
 */
class EngineSeekRouter(
    private val engine: AudioPlaybackEngine,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : SeekRouter {

    /**
     * ## seek 防抖 / 连点合并
     *
     * 复现见 `SeekCoalescingTest`:连点快进大约 1/7 会弹出「该条目无法播放」,而耳机里其实还在
     * 正常出声。根因不是"迟到的结果覆盖了更新的状态"——那条路已经被
     * [AudioPlaybackEngineImpl] 既有的 `requestSeq`/`isStale` 令牌机制挡住了。真正的根因是
     * **每一次按键都无条件起一个新的 resolve 请求**,而群晖 J4125 只能扛有限的并发转码探测
     * (`PlaybackSourceResolver`「稳定性根因 #3」的 KDoc)。连点两下快进,**第二下**(真正最新、
     * 按令牌判定完全不 stale 的那一次)可能因为第一下还占着并发名额而竞争失败——它本身就是
     * "最新",令牌机制救不了它,`state` 被打成 Error,而播放器上还在放第一下发起前的那条旧流。
     *
     * 这里在真正调用 [AudioPlaybackEngine.seekTo] 之前,先取消上一次还没落地的请求:如果它还在
     * [debounceMs] 的等待窗口里,直接被取消,连 resolve 都不会发起;如果它已经在 resolve 中,
     * 取消会让协程在下一个挂起点抛 [kotlinx.coroutines.CancellationException],
     * [AudioPlaybackEngineImpl.resolveAndPrepare] 把它原样向上抛出、绝不会落到
     * `catch (e: Exception)` 分支——所以被取消的那次请求**不可能**把 state 打成 Error。
     *
     * 这是在 [AudioPlaybackEngine] 既有的令牌机制之上叠加,不是另起一套并行方案:令牌机制继续
     * 兜底"取消信号还没来得及生效、resolve 已经跑完"那极小的窗口;这一层从根上减少冗余请求
     * 本身,不让陈旧请求有机会去抢并发名额。
     *
     * ## v3 复审 Finding 1(Important):悬而未决的 seek 不能落到发起它时以外的条目上
     *
     * 上面这套防抖只解决了"同一个条目内连点"。debounce 的等待窗口有 250ms,而
     * [EngineQueueNavigator.next]/[EngineQueueNavigator.previous](锁屏/通知栏的上一集下一集按钮)
     * 随时可能在这 250ms 里把 [AudioPlaybackEngine.currentItemId] 切到另一条目——它们各自新起一次
     * `engine.play(...)`,不会去取消这里的 [pendingSeek]。等防抖窗口到期,这次 seek 执行
     * `engine.seekTo(oldPositionMs)`:[AudioPlaybackEngineImpl.seekTo] 内部按**此刻**的
     * `currentItemId` 隐式取值(它的签名不接收调用方传入的 itemId),于是拿着"拖动进度条那一刻"的
     * 旧位置,重新 resolve 出**已经切换到的新条目**——新条目会从旧集数的播放进度开始,而不是从头播。
     *
     * 既有的 `requestSeq`/`isStale` 令牌机制挡不住这次:它落地时没有更晚的同类型请求跟它抢——它自己
     * 就是最新的一次请求,令牌机制只防"迟到的旧结果覆盖新结果",不认识"这次请求从一开始问的就是
     * 错误的条目"这件事。
     *
     * **取舍:在发起 seek 的这一刻捕获目标 itemId,落地前重新核对,对不上就整条丢弃**,而不是反过来
     * 让 [EngineQueueNavigator] 去取消这里的 [pendingSeek]——后者需要给 [QueueNavigator] 接口开一个
     * "我知道 EngineSeekRouter 存在"的洞,并且任何将来新增的、会移动 `currentItemId` 的路径
     * (`PlaybackEndedAdvancer` 自动连播、`AppSessionViewModel.play()` 直接点开另一集……)都要记得
     * 同样去取消,一旦漏掉一处就是同一个缺陷的另一个变种。捕获-核对的写法把"落地前核实条目没变"的
     * 责任收在这一个方法里,谁移动 `currentItemId` 都自动被挡住,不需要挨个通知。
     */
    @Volatile
    private var pendingSeek: Job? = null

    override fun seekTo(positionMs: Long) {
        pendingSeek?.cancel()
        val targetItemId = engine.currentItemId
        pendingSeek = scope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            // 落地前核对:如果 currentItemId 已经不是发起这次 seek 时的那个条目,说明期间发生过
            // play()(切集/自动连播),这次悬而未决的请求已经问错了对象——整条丢弃,不发起 resolve,
            // 不调用 engine.seekTo。engine 的状态此刻已经由那次 play() 正确接管。
            if (engine.currentItemId != targetItemId) return@launch
            engine.seekTo(positionMs)
        }
    }

    private companion object {
        /** 连点快进/拖动进度条的合并窗口。 */
        const val DEFAULT_DEBOUNCE_MS = 250L
    }
}

/**
 * 闭合 Task 9/10 遗留的 Important 缺陷:[PlaybackService] 曾经把 `MediaSession` 建在裸
 * ExoPlayer 上,没有任何拦截层。Media3 的默认 MediaSession 回调会把控制器发来的 seek 命令
 * (锁屏拖动进度条、`MediaController.seekTo`、快进快退按钮)直接转发给 `player.seekTo()`——
 * 而 Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)Jellyfin 转码音频流的
 * 响应头是 `Accept-Ranges: none`:服务端根本不支持字节区间取流,`ExoPlayer.seekTo()` 在这种
 * 流上不可靠(会卡住,或者从头播放)。
 *
 * 用 `ForwardingPlayer` 包一层,覆写全部"能实际移动当前条目内播放位置"的方法:
 * `seekTo(Long)` / `seekTo(Int, Long)` / `seekToDefaultPosition()` / `seekToDefaultPosition(Int)`
 * / `seekBack()` / `seekForward()`。这些覆写**从不调用 `super.xxx()`**,只调用 [seekRouter]——
 * 结构上就不可能落到底层 player 的字节级 seek,和 [PlayerControl] 刻意不暴露 `seekTo` 是同一个
 * "结构上杜绝误用"的思路(见 [AudioPlaybackEngine] 的类注释)。
 *
 * `MediaSession.Builder(context, sessionPlayer)` 接的是这个包装后的 Player,不是裸 ExoPlayer——
 * 这样锁屏/通知栏/蓝牙耳机按键发来的 seek,不管走 MediaSession 默认回调还是自定义回调,最终都
 * 只能落到这一层。
 *
 * `seekToPrevious()` **必须覆盖**(复审 Finding 1):对 media3-common 1.10.1 的 `BasePlayer`
 * 字节码核实过——非直播、单条目场景下(本项目模型:没有上一条目),`seekToPrevious()` 总是走
 * "没有上一条目 或 currentPosition > maxSeekToPreviousPosition"分支,调用
 * `seekToCurrentItem(0, ...)`,即对**当前条目**做一次真正的位置 0 的 seek。这正是蓝牙/锁屏
 * "上一曲"键通过 MediaSession 的 `SEEK_TO_PREVIOUS` 命令触发的路径——不覆盖的话它会绕开这一层,
 * 直接落到裸 ExoPlayer。所以和 `seekToDefaultPosition()` 一样,路由为 seek 到 0。
 *
 * ## 上一集/下一集(设计文档 §3.3)
 *
 * `seekToNextMediaItem()` / `seekToPreviousMediaItem()` **现在覆盖了**(此前刻意不覆盖,理由是
 * "本项目不使用 ExoPlayer 自身的多条目播放列表,单条目场景下这两个方法是 no-op")——但那份理由
 * 恰恰是问题本身:`hasNextMediaItem()` 因此恒为 false,Media3 在通知栏/锁屏/蓝牙/车机上**不生成
 * 任何上一集下一集按钮**。修正**不是**给 Media3 塞一个假的多条目播放列表(那会和"每次重新
 * resolve"的机制冲突,见类顶部 KDoc),而是把这两个方法**委派给 [queueNavigator]**——按
 * [PlayQueue] 的真实状态推进游标 + 经 [AudioPlaybackEngine.play] 重新准备那一条,和自动连播
 * ([PlaybackEndedAdvancer])、`AppSessionViewModel.play()` 是同一条路。[getAvailableCommands]
 * 同步按 [queueNavigator] 的状态在**既有**命令集合上加/删这两个命令(用 `buildUpon()`,不整体
 * 重建),这样通知栏/锁屏/蓝牙/车机才会显示出这两个按钮,而不是 Media3 因为查不到就直接不生成。
 *
 * `seekToNext()`(不带 MediaItem 后缀)依旧刻意不覆盖:会被 `BasePlayer` 转成对当前条目的操作但
 * **不会**触发真正的位置 seek(与 `seekToPrevious()` 的字节码路径不同,见上一段),留着无害。
 */
class SeekInterceptingPlayer(
    player: Player,
    private val seekRouter: SeekRouter,
    private val absoluteTimeline: AbsoluteTimeline = AbsoluteTimeline.Unknown,
    private val queueNavigator: QueueNavigator = QueueNavigator.None,
) : ForwardingPlayer(player) {

    /**
     * 全支线复审 Critical 1:底层 `currentPosition` 是**流内相对位置**,每次 seek/续播换流都从 0
     * 重新开始。锁屏进度条、蓝牙"快进 30 秒"、`MediaController` 报给 UI 的位置全都读这个方法,
     * 所以这里必须改报 [AbsoluteTimeline] 给的条目内绝对位置——否则从 8:00 续播时锁屏显示 0:00,
     * 按一下快进键会往回跳 7 分半。
     *
     * 下面 [seekBack] / [seekForward] 用的 `currentPosition` 也就是这个覆写(虚方法分派),
     * 于是"所有 seek 起点都过绝对位置"这件事在这一层是结构性的,不靠调用方自觉。
     */
    override fun getCurrentPosition(): Long = absoluteTimeline.absolutePositionMs() ?: super.getCurrentPosition()

    /** 本项目没有广告插播,内容位置恒等于播放位置;`MediaSession` 读的是这个,一并覆写保持一致。 */
    override fun getContentPosition(): Long = absoluteTimeline.absolutePositionMs() ?: super.getContentPosition()

    /**
     * 转码流是 chunked AAC,底层 `duration` 往往是 `C.TIME_UNSET`——锁屏/通知栏拿它当总时长会
     * 让进度条钉在 100%,一拖就把当前集从头开始(复审 Critical 1 的第四条)。权威总时长是
     * Jellyfin 元数据的 `runTimeMs`,由 [AbsoluteTimeline] 提供。
     */
    override fun getDuration(): Long = absoluteTimeline.absoluteDurationMs()?.takeIf { it > 0L } ?: super.getDuration()

    override fun getContentDuration(): Long =
        absoluteTimeline.absoluteDurationMs()?.takeIf { it > 0L } ?: super.getContentDuration()

    /**
     * ## 🔴 缺陷「一加流体云胶囊不显示播放时间」的根因(设计文档 §4 第二项,真机取证后确认)
     *
     * 播放进行中 `adb shell dumpsys media_session`:
     * ```
     * state=PlaybackState {state=PLAYING(3), position=-1, buffered position=-1, speed=0.0, ...}
     * ```
     * 位置 `-1`(`PLAYBACK_POSITION_UNKNOWN`)、速度 `0.0` —— 系统侧既没有位置也没有速度可以外推,
     * 于是流体云/锁屏/车机全都显示不出"已听多久"。总时长本身是好的(经 [getDuration] 报的
     * 元数据 `runTimeMs`),缺的正是位置和速度。
     *
     * 核对 media3 1.10.1 `MediaSessionLegacyStub.createPlaybackStateCompat` 的字节码:
     * ```
     * canReadPosition = isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM) && !isCurrentMediaItemLive()
     * position = canReadPosition ? currentPosition : -1
     * speed    = (isPlaying && canReadPosition) ? playbackParameters.speed : 0f
     * ```
     * 真机实测 `exo.live=true`:没有 `Content-Length` 的 chunked 转码流被 ExoPlayer 当成**直播**,
     * `canReadPosition` 因此恒为 false。
     *
     * **JellyCast 只播点播条目。** 元数据给了权威总时长时,这条流就不是直播,这里如实纠正。
     * 拿不到总时长时不撒谎、退回底层判定(那种情况下进度条本来也是禁用的,
     * 见 `PlayerScreen.ProgressSection`)。
     */
    override fun isCurrentMediaItemLive(): Boolean =
        if (hasAuthoritativeDuration()) false else super.isCurrentMediaItemLive()

    /**
     * 和 [getAvailableCommands] 里声明 `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` 保持自洽:
     * 本类**自己实现了 seek**(委派 [seekRouter] → 重新 resolve 一条带 `startTimeTicks` 的新流)。
     * 底层那条字节流不支持 Range 请求是另一回事,不该由它来回答"这个 Player 能不能 seek"。
     */
    override fun isCurrentMediaItemSeekable(): Boolean =
        if (hasAuthoritativeDuration()) true else super.isCurrentMediaItemSeekable()

    private fun hasAuthoritativeDuration(): Boolean = (absoluteTimeline.absoluteDurationMs() ?: 0L) > 0L

    override fun seekToDefaultPosition() {
        seekRouter.seekTo(0L)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekRouter.seekTo(0L)
    }

    override fun seekTo(positionMs: Long) {
        seekRouter.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekRouter.seekTo(positionMs)
    }

    /**
     * 设计文档 §3.3:在**既有**命令集合上加/删这两个命令,不整体重建——`buildUpon()` 保留了
     * Media3/底层 player 需要的其它命令(播放/暂停/音量……),这里只按 [queueNavigator] 的真实
     * 状态决定 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` 在不在。
     * 没有这一步,`hasNextMediaItem()`/`hasPreviousMediaItem()` 恒为 false,Media3 在通知栏/锁屏/
     * 蓝牙/车机上根本不会生成上一集下一集按钮。
     *
     * ## 🔴 缺陷「拖动进度条也不好使」的根因(设计文档 §4 第一项,真机取证后确认)
     *
     * 模拟器 + 真实服务器实测(`SeekBufferDiagnosticsTest`):
     * ```
     * exo.seekable=false exo.live=true exo.duration=TIME_UNSET
     * exo.cmdSeekInItem=false  ctl.cmdSeekInItem=false ctl.cmdSeekBack=false ctl.cmdSeekFwd=false
     * W MCImplBase: Controller isn't allowed to call command= 5
     * SEEK landedAfterMs=-1        ← 20 秒内位置一步没动
     * ```
     *
     * 转码流 `Accept-Ranges: none` 且没有 `Content-Length`,ExoPlayer 于是判定当前条目不可 seek、
     * 时长未知,把 `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM`(5)/`COMMAND_SEEK_BACK`(11)/
     * `COMMAND_SEEK_FORWARD`(12) 从可用命令里**摘掉**。而 App 里所有 seek 都经 `MediaController`
     * (播放页拖进度条、±15s/±30s、锁屏、通知栏、蓝牙),`MediaController` 在发命令前先查可用性,
     * 查不到就打一行 warning **直接返回** —— 请求根本到不了本类的 [seekTo],那一整套"重新 resolve"
     * 从来没有被触发过。用户看到的不是"慢",是**完全没反应**。
     *
     * 所以这里必须**如实声明本类自己实现了的那几条 seek 命令**。这不是绕过 Media3 的检查:
     * "底层字节流不支持 Range 请求"和"这个 Player 支持 seek"两件事同时为真,而 `getAvailableCommands()`
     * 描述的是**后者**。它们各自的覆写([seekTo] / [seekBack] / [seekForward] / [seekToDefaultPosition])
     * 从不调用 `super`,只走 [seekRouter],所以声明它们不会把任何请求引到底层的字节级 seek 上。
     *
     * ⚠️ `Player.Commands.Builder` 内部用 `android.util.SparseBooleanArray` 存储,在本模块
     * `testOptions.unitTests.isReturnDefaultValues = true` 的纯 JVM 环境下是静默空桩。所以这个方法
     * 的加/删正确性用 Robolectric 提供真实 shadow 来测(见 `SeekInterceptingPlayerCommandsTest` /
     * `SeekInterceptingPlayerSeekCommandsTest`),不是普通 JVM 单测。
     */
    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
        // 见上:本类自己实现了这几条,底层流可不可 seek 与它们无关。
        builder.addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
        )
        if (queueNavigator.hasNext()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (queueNavigator.hasPrevious()) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        return builder.build()
    }

    /** 委派给 [queueNavigator]——见类顶部"上一集/下一集"一节。 */
    override fun seekToNextMediaItem() {
        queueNavigator.next()
    }

    /**
     * 委派给 [queueNavigator]。⚠️ 和 [seekToPrevious](不带 MediaItem 后缀,下方覆写)是两个不同的
     * 方法、两套不同的语义——不要合并到一起。
     */
    override fun seekToPreviousMediaItem() {
        queueNavigator.previous()
    }

    // 注意:下面两个方法里的 `currentPosition` 是本类覆写的那个(绝对位置),不是底层 player 的
    // 流内相对位置。这是复审 Critical 1 的修正点——锁屏与蓝牙耳机的快进快退走的就是这条路。
    override fun seekBack() {
        val target = (currentPosition - seekBackIncrement).coerceAtLeast(0L)
        seekRouter.seekTo(target)
    }

    /**
     * 复审 Minor:上界钳制不能省。`PlayerViewModel.onSkipForward()` 一直有 `coerceAtMost(durationMs)`,
     * 这一层却没有——于是锁屏/通知栏/蓝牙耳机的快进键在快结尾处按一下,会请求一个**超过条目结尾**的
     * `startTimeTicks`,服务端只能给出一条空流或直接失败。两条路现在一致了。
     *
     * 上界取本类覆写的 [getDuration](权威值来自 Jellyfin 元数据 `runTimeMs`)。它可能是"未知"——
     * 元数据缺失时会回退到底层 `duration`,而 chunked AAC 转码流的 `duration` 常是 `C.TIME_UNSET`
     * (一个很大的负数)。所以只有在 `> 0` 时才钳制,否则照常快进,绝不会因为"不知道多长"就把目标
     * 位置钳成 0。
     */
    override fun seekForward() {
        val target = currentPosition + seekForwardIncrement
        val durationMs = duration
        seekRouter.seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
    }

    override fun seekToPrevious() {
        seekRouter.seekTo(0L)
    }

    // ------------------------------------------------------------------ 命令变更通知的纠正

    /**
     * ## 🔴 缺陷「拖动进度条也不好使」的第二层根因
     *
     * 只覆写 [getAvailableCommands] **不够**:真机复测后 `MediaController` 依然打
     * `Controller isn't allowed to call command= 5`。核对 media3 1.10.1 字节码,`MediaController`
     * 手里的可用命令是**推**过去的、不是现查的:
     * ```
     * MediaSessionImpl$PlayerListener.onAvailableCommandsChanged(Commands c)
     *     → dispatchOnAvailableCommandsChangedFromPlayer(c)          ← 用的是回调参数 c
     * MediaControllerImplBase.onAvailableCommandsChangedFromPlayer(c)
     *     → intersectedPlayerCommands = intersect(playerCommandsFromSession, c)
     * ```
     * 而 `ForwardingPlayer$ForwardingListener.onAvailableCommandsChanged(c)` 把**底层播放器的原始
     * 集合原样**往上传(它只替换事件里的 `Player` 引用,不替换这个参数)。于是推给控制器的仍然是
     * ExoPlayer 那份摘掉了 seek 的集合,[getAvailableCommands] 在这条路上被整个绕过。
     *
     * 因此注册进来的每个监听器都包一层:`onAvailableCommandsChanged` 改报本类的集合,其余回调
     * **逐个原样透传**。
     *
     * ⚠️ 这里**不能**用 Kotlin 的 `Player.Listener by delegate` 接口委派 —— 实测(本类第一版)
     * 一个回调都没转发出去:`Player.Listener` 的成员**全部是 Java default 方法**,Kotlin 的委派只为
     * *抽象* 成员生成转发,default 方法直接继承 Java 那份空实现,于是所有事件被静默吞掉。
     * 也不用 `java.lang.reflect.Proxy`(按方法名匹配,一旦开启混淆就会失效)。
     *
     * 手写 37 个覆写的代价是"media3 升级新增回调时可能漏掉一个",这一点由
     * `SeekInterceptingPlayerListenerTest` 里的反射守卫钉死:该测试遍历 `Player.Listener` 声明的
     * 每一个方法,要求本类都覆写过,漏一个就红。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "TooManyFunctions")
    private class CommandCorrectingListener(
        private val delegate: Player.Listener,
        private val correctedCommands: () -> Player.Commands,
    ) : Player.Listener {

        /** 唯一被改写的那一个:见类外层 KDoc。 */
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            delegate.onAvailableCommandsChanged(correctedCommands())

        // 以下全部原样透传。顺序与 Player.Listener 的声明顺序一致,便于和反射守卫对照。
        override fun onEvents(player: Player, events: Player.Events) = delegate.onEvents(player, events)
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            delegate.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) =
            delegate.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = delegate.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) =
            delegate.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) =
            delegate.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = delegate.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = delegate.onLoadingChanged(isLoading)
        override fun onTrackSelectionParametersChanged(
            parameters: androidx.media3.common.TrackSelectionParameters,
        ) = delegate.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            delegate.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = delegate.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            delegate.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(reason: Int) =
            delegate.onPlaybackSuppressionReasonChanged(reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = delegate.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = delegate.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = delegate.onPlayerError(error)
        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) =
            delegate.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = delegate.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(
            playbackParameters: androidx.media3.common.PlaybackParameters,
        ) = delegate.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
            delegate.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) =
            delegate.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: androidx.media3.common.AudioAttributes) =
            delegate.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = delegate.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
            delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) =
            delegate.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
            delegate.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) =
            delegate.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) =
            delegate.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = delegate.onRenderedFirstFrame()
        override fun onCues(cues: MutableList<androidx.media3.common.text.Cue>) = delegate.onCues(cues)
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = delegate.onCues(cueGroup)
        override fun onMetadata(metadata: androidx.media3.common.Metadata) = delegate.onMetadata(metadata)
    }

    /**
     * 原监听器 → 包装器。[removeListener] 必须摘掉当初注册进底层的**同一个实例**,否则监听器永远
     * 摘不掉:`PlaybackService` 每次重建都会往 `@Singleton` 播放器上再挂一份(见其 `onDestroy` 注释)。
     *
     * 线程契约:所有 add/remove 都在 ExoPlayer 的 application thread(本项目 = 主线程)上发生,
     * 和播放器自身的约束一致,所以这里用普通 `LinkedHashMap` 即可。
     */
    private val listenerWrappers = LinkedHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = listenerWrappers.getOrPut(listener) {
            CommandCorrectingListener(listener) { availableCommands }
        }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = listenerWrappers.remove(listener) ?: listener
        super.removeListener(wrapper)
    }
}
