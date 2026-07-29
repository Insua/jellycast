package dev.insua.jellycast.e2e

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.insua.jellycast.MainActivity
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.server.JellyfinApiFactory
import dev.insua.jellycast.model.AudioDeliveryLevel
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AudioPlaybackEngineImpl
import dev.insua.jellycast.player.ExoPlayerControl
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.PlaybackSourceResolver
import dev.insua.jellycast.player.StreamProbe
import dev.insua.jellycast.player.asProvider
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * # 端到端播放脚手架 —— 本仓库第一次让「真 ExoPlayer + 真 MediaSessionService + 真 Jellyfin 服务器」
 * 一起跑起来。
 *
 * ## 为什么必须存在
 *
 * v1 有 281 个 JVM 单测全绿,却在真机上四个环节全崩(点播放、播到一半、锁屏/后台、seek/切集)。
 * 之前两个现场 bug 也是同一形状:一个是主线程上的阻塞 socket 读(依赖 TCP 分段时序),一个是
 * 软键盘悄悄改写了密码。**这两个都不可能被 JVM 单测覆盖到** —— 它们只在真设备 + 真网络 + 真
 * 播放器上才存在。所以这套测试的价值不在于"能过",而在于**能自动复现**那些崩溃。
 *
 * ## 断言纪律:断行为,不断"没抛异常"
 *
 * `play()` 正常返回什么也证明不了 —— 用户的抱怨是"没有声音",不是"崩了"。
 * 所有断言都盯着 [AudioPlaybackEngine.absolutePositionMs](条目内绝对位置,项目里位置的唯一权威)
 * **是否真的在前进**,并且一律用「轮询 + 超时」的条件等待,不拿固定 `Thread.sleep` 当断言。
 *
 * ## 线程契约
 *
 * `ExoPlayer` 只能在它的 application thread(本项目 = 主线程)上被读写。所以凡是碰到
 * 播放器/引擎的地方都经 [onMain] 派到主线程,和生产代码里 `viewModelScope`(Main)、
 * `PlaybackService` 的 main-looper scope 是同一条线程。
 *
 * ## 🔴 凭据
 *
 * 服务器地址/账号/密码来自 [TestCredentials](根目录 `testing.properties`,已 gitignore)。
 * 断言消息、失败诊断一律经 [TestCredentials.redact] 脱敏 —— 流 URL 里带着 `api_key=<token>`,
 * 直接贴进失败信息就等于把 token 写进 CI 日志。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlaybackE2eTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    // ⚠️ 全部是**生产**实现,没有一个 fake:
    @Inject lateinit var serverStore: ServerStore
    @Inject lateinit var apiFactory: JellyfinApiFactory
    @Inject lateinit var api: JellyfinApi
    @Inject lateinit var session: JellyfinSession
    @Inject lateinit var engine: AudioPlaybackEngine
    @Inject lateinit var exoPlayer: ExoPlayer
    @Inject lateinit var playQueue: PlayQueue
    @Inject lateinit var playerConnection: MediaControllerPlayerConnection

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var userId: String
    private lateinit var playableItem: MediaItem

    /**
     * 自动连播场景的期望落点:**[playableItem] 所属剧集本季集序里的下一集**(服务端给的顺序),
     * 不是"列表里随便另一条"。和 [playableItem] 来自同一次挑选。
     */
    private lateinit var secondItem: MediaItem

    /**
     * 诱饵:**别的剧**的一集,放进播放队列里排在 [playableItem] 后面。连播如果还跟着队列走,
     * 就会跳到它——那正是要防住的缺陷。服务器上凑不出第二部剧时为 null,断言不受影响。
     */
    private var decoyItem: MediaItem? = null

    @Before
    fun setUp() {
        TestCredentials.assumeConfigured()

        // 媒体通知需要它;拿不到也不让测试红 —— MediaSessionService 的前台服务本身不依赖它。
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        hilt.inject()

        runBlocking {
            seedSession()
            userId = session.userId()
            val picked = pickPlayableItems(userId)
            playableItem = picked.first
            secondItem = picked.second
            decoyItem = pickDecoy(userId, playableItem)
        }

        // Media3 的标准路径:UI 只通过 MediaController 跟会话对话。这里把它拉起来,让
        // PlaybackService / MediaSession 真的参与进这次播放,而不是只测一个裸 ExoPlayer。
        playerConnection.ensureConnected()
    }

    @After
    fun tearDown() {
        if (!TestCredentials.isConfigured) return
        runCatching {
            onMain {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                engine.reset()
            }
        }
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    // ---------------------------------------------------------------- 场景 1

    /**
     * 场景 1:**点下播放之后,位置是不是真的在走。**
     *
     * 分两段断言,因为"根本没开始"和"开始了但卡住"是两个完全不同的 bug,Task 2 需要区分:
     * - 阶段 A:[STARTUP_TIMEOUT_MS] 内位置必须离开 0(拿到流并开始出声)。
     * - 阶段 B:开始之后 10s 内必须再前进 ≥ 5s(不是启动瞬间抖了一下就停)。
     */
    @Test
    fun `开始播放后位置确实前进`() {
        startPlayback(playableItem, startPositionMs = 0L)

        val started = awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L }
        assertTrue(
            "播放从未开始:${STARTUP_TIMEOUT_MS / 1000}s 内 absolutePositionMs 一直是 0。${diagnostics()}",
            started,
        )

        val from = position()
        val advanced = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_MS }
        assertTrue(
            "播放开始后停住了:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms," +
                "要求 ≥ ${MIN_ADVANCE_MS}ms。${diagnostics()}",
            advanced,
        )
    }

    // ---------------------------------------------------------------- 场景 2

    /**
     * 场景 2:**连续播 30 秒不中断。** 每 [SAMPLE_INTERVAL_MS] 采一次样,要求:
     * 位置严格单调递增、引擎不进 `Error`、播放器不冒 `PlaybackException`。
     *
     * 用户报告的"播到一半断"就落在这里 —— 转码流被服务端掐断、或者播放器进 BUFFERING 再也不出来,
     * 都表现为"某一次采样没比上一次大"。
     */
    @Test
    fun `连续播放三十秒不中断`() {
        startPlayback(playableItem, startPositionMs = 0L)
        assertTrue(
            "播放从未开始,无法验证连续性。${diagnostics()}",
            awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
        )

        var previous = position()
        val samples = mutableListOf(previous)
        val deadline = SystemClock.elapsedRealtime() + CONTINUOUS_PLAY_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(SAMPLE_INTERVAL_MS)
            val current = position()
            samples += current
            assertNoPlaybackError("连续播放期间出错。采样(ms)=$samples")
            if (current <= previous) {
                fail(
                    "连续播放中断:第 ${samples.size} 次采样位置没有前进(${previous}ms → ${current}ms)。" +
                        "采样序列=$samples。${diagnostics()}"
                )
            }
            previous = current
        }

        val total = samples.last() - samples.first()
        assertTrue(
            "30s 里总共只前进了 ${total}ms,远低于预期(≥ ${MIN_CONTINUOUS_ADVANCE_MS}ms)。" +
                "采样序列=$samples。${diagnostics()}",
            total >= MIN_CONTINUOUS_ADVANCE_MS,
        )
    }

    // ---------------------------------------------------------------- 场景 3

    /**
     * 场景 3:**seek 之后位置落在目标附近,并且继续前进。**
     *
     * 转码流 `Accept-Ranges: none`,所以 seek 实现成"带新的 startTimeTicks 重新 resolve 一条流"
     * (见 `AudioPlaybackEngineImpl` 类注释)。这个测试盯的正是那条路:目标位置命中,而且新流真的在播,
     * 不是停在目标位置不动。
     *
     * 目标位置按条目时长自适应:够长就用 10 分钟(计划里的值),太短就取时长的一半 ——
     * 对一个 8 分钟的条目 seek 到 10 分钟,测的是服务端的边界行为,不是本项目要验证的东西。
     *
     * ## ⚠️ 复审 Important 2(已闭合):前两段断言**证明不了 seek 真的发生了**
     *
     * [position] = `currentStartPositionMs + 流内位置`,而 `AudioPlaybackEngineImpl` 在 `resolve()`
     * 一返回就把 `currentStartPositionMs` 设成**请求的目标** —— 也就是说"落到目标区间"这个断言
     * 在**一个音频样本都还没出声**的时候就已经满足了;紧接着的"还在前进"也照样满足,因为那条流
     * 确实在放,只不过是**从第 0 秒开始放的**。
     *
     * 这不是假想的漏洞,是一个**已修复的真实缺陷**的形状:L3 兜底的 URL 曾经带 `static=true`,服务端
     * 于是**静默忽略 `startTimeTicks`**,seek 从头重播而进度条照走(见 v2-task-2 报告 §8)。
     * 这两段断言在那种情况下**全绿**。守住它的是场景 3d(强制走 L3)。
     *
     * 所以最后加一段真正有判别力的:见 [assertSeekOffsetHonoredAtTail]。
     */
    @Test
    fun `seek后位置跳到目标并继续前进`() {   // 名字里不能有空格:dex < 040 不允许方法名含空格
        startPlayback(playableItem, startPositionMs = 0L)
        assertTrue(
            "播放从未开始,无法验证 seek。${diagnostics()}",
            awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
        )

        val target = seekTargetMs(playableItem)
        runBlocking(Dispatchers.Main) { engine.seekTo(target) }

        val lower = target - SEEK_TOLERANCE_BEFORE_MS
        val upper = target + SEEK_TOLERANCE_AFTER_MS
        val landed = awaitCondition(SEEK_TIMEOUT_MS) { position() in lower..upper }
        assertTrue(
            "seek 没有落到目标:目标 ${target}ms,允许区间 [$lower, $upper]," +
                "${SEEK_TIMEOUT_MS / 1000}s 后实际 ${position()}ms。${diagnostics()}",
            landed,
        )

        val from = position()
        val keptGoing = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_AFTER_SEEK_MS }
        assertTrue(
            "seek 之后位置停住了:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms。${diagnostics()}",
            keptGoing,
        )

        assertSeekOffsetHonoredAtTail(playableItem, "单次 seek")
    }

    // ---------------------------------------------------------------- 场景 3b

    /**
     * 场景 3b:**连点快进(短时间内连续多次 seek)之后,落点必须是最后一次请求的位置,且仍在播。**
     *
     * 用户报的四个崩溃点里明确有 "seek 与切集"。Task 1 的场景 3 只测了**一次**孤立的 seek,
     * 而现实里用户是**连点**快进键的 —— 那正是生产路径上唯一存在并发的地方:
     * `SeekInterceptingPlayer.seekForward()` → `EngineSeekRouter` → `scope.launch { engine.seekTo() }`,
     * 每一次按键都**新起一个协程**,谁也不等谁。于是 N 次连点 = N 个并发的
     * `resolve()`(每个都是一次 PlaybackInfo 往返 + 一次 L1 探测 GET),
     * 而最终留在播放器上的是**最后完成**的那一条,不是**最后请求**的那一次。
     *
     * 本用例严格复刻这条路(同一个主线程 scope 上 launch,不做任何串行化),断言的是用户的期望:
     * **最后按下的那一次说了算。**
     *
     * ## ⚠️ 复审 Important 2(已闭合)
     *
     * 和场景 3 同一个盲区:落点断言读的是 [position],而它在 `resolve()` 一返回就等于目标了。
     * 所以这里把**最后一次**连点的目标放在距结尾 [TAIL_SEEK_MARGIN_MS] 处,再要求
     * `STATE_ENDED` 真的到来 —— 见 [assertSeekOffsetHonoredAtTail]。这样"最后一次说了算"和
     * "偏移真的生效"两件事被同一段断言一起验掉:落在别的目标上范围断言就红,偏移被忽略
     * ENDED 就不会来。
     */
    @Test
    fun `连点快进之后落在最后一次目标并继续播放`() {
        startPlayback(playableItem, startPositionMs = 0L)
        assertTrue(
            "播放从未开始,无法验证连点快进。${diagnostics()}",
            awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
        )

        val targets = rapidSeekTargets(playableItem)
        val seekScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // ENDED 只发生一次,而且随后 PlaybackService 的自动连播监听器会立刻 prepare 下一条流 ——
        // 必须在 seek **之前**就挂上监听,轮询 playbackState 极容易整个错过这一瞬间。
        val latch = addEndedLatch()
        try {
            targets.forEach { target ->
                // 和 EngineSeekRouter 完全一样:launch 出去就不管了,不等它完成。
                instrumentation.runOnMainSync { seekScope.launch { engine.seekTo(target) } }
                Thread.sleep(RAPID_SEEK_INTERVAL_MS)
            }

            val last = targets.last()
            val lower = last - SEEK_TOLERANCE_BEFORE_MS
            val upper = last + SEEK_TOLERANCE_AFTER_MS
            val landed = awaitCondition(SEEK_TIMEOUT_MS) { position() in lower..upper }
            assertTrue(
                "连点 ${targets.size} 次快进(目标依次为 $targets,间隔 ${RAPID_SEEK_INTERVAL_MS}ms)之后," +
                    "落点不是最后一次请求的位置:期望 [$lower, $upper],实际 ${position()}ms。" +
                    "${SEEK_TIMEOUT_MS / 1000}s 内一直没落到位。${diagnostics()}",
                landed,
            )

            val from = position()
            val keptGoing = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_AFTER_SEEK_MS }
            assertNoPlaybackError("连点快进之后")
            assertTrue(
                "连点快进之后播放停住了:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms。${diagnostics()}",
                keptGoing,
            )

            // 最后一次连点的目标就是尾部落点([rapidSeekTargets]),所以这里不再 seek,直接等 ENDED。
            awaitEnded(latch, "连点快进的最后一次落点(${last}ms,距结尾 ${TAIL_SEEK_MARGIN_MS}ms)")
        } finally {
            removeEndedLatch(latch)
            seekScope.cancel()
        }
    }

    // ---------------------------------------------------------------- 场景 3d

    /**
     * 场景 3d:**强制走 L3 兜底路径时,seek 也必须真的生效。**
     *
     * L3 现在很罕见(一次服务端 500 不再把整个会话钉死在它上面),但它是**保底可播**的那条路,
     * 所以它必须诚实 —— 而在修复前它恰恰是最不诚实的一条:URL 带 `static=true`,服务端因此
     * **静默忽略 `startTimeTicks`**,进度条跳到目标继续走,耳机里却在从头重播。
     *
     * ## 怎么"强制走 L3"而不动生产 DI
     *
     * L1/L3 的分岔点只有一个:[StreamProbe] 的判定。这里就地组装一个**探测恒为 false** 的
     * [PlaybackSourceResolver],再用它驱动一个 [AudioPlaybackEngineImpl] —— 播放器仍然是
     * 注入进来的那个**单例 ExoPlayer**,API / 会话 / 网络栈也全是生产实现。
     * 换掉的只有"要不要降级"这一个判定,正是本用例要钉住的那条路。
     *
     * 断言分两段:先证明**真的**落在 L3(否则这个用例什么也没测到),再用
     * [awaitEnded] 的数量级判别证明偏移真的生效。
     */
    @Test
    fun `强制走L3兜底时seek同样真的从目标位置开始`() {
        val l3Engine = forcedL3Engine()
        playQueue.setQueue(listOf(playableItem), 0)
        runBlocking(Dispatchers.Main) { l3Engine.play(playableItem.id, userId, 0L) }

        val started = awaitCondition(STARTUP_TIMEOUT_MS) { onMain { l3Engine.absolutePositionMs } > 0L }
        assertTrue("强制 L3 时播放从未开始。${diagnostics()}", started)

        val level = onMain { (l3Engine.state.value as? PlaybackEngineState.Ready)?.source?.level }
        assertTrue(
            "本用例的前提是真的落在 L3,实际 level=$level —— 前提不成立时后面的断言证明不了任何事。",
            level == AudioDeliveryLevel.CLIENT_VIDEO_DISABLED,
        )

        val tail = tailSeekTargetMs(playableItem)
        val latch = addEndedLatch()
        try {
            runBlocking(Dispatchers.Main) { l3Engine.seekTo(tail) }
            assertNoPlaybackError("强制 L3:尾部 seek 之后")
            awaitEnded(latch, "强制 L3:seek 到 ${tail}ms(距结尾 ${TAIL_SEEK_MARGIN_MS}ms)")
        } finally {
            removeEndedLatch(latch)
        }
    }

    /**
     * 探测恒为 false 的引擎 —— 除了这一个判定,其余全是生产实现(见 [强制走L3兜底时seek同样真的从目标位置开始])。
     * 播放器刻意复用注入的单例 [exoPlayer],这样 [addEndedLatch] 挂的监听器和它是同一个播放器。
     */
    private fun forcedL3Engine(): AudioPlaybackEngine {
        val neverAudioOnly = object : StreamProbe {
            override suspend fun isAudioOnly(url: String): Boolean = false
        }
        val resolver = PlaybackSourceResolver(
            api = api,
            streamProbe = neverAudioOnly,
            baseUrlProvider = { session.cachedBaseUrlOrNull().orEmpty() },
            tokenProvider = { session.cachedTokenOrNull().orEmpty() },
        )
        return AudioPlaybackEngineImpl(resolver.asProvider(), ExoPlayerControl(exoPlayer))
    }

    // ---------------------------------------------------------------- 场景 3c

    /**
     * 场景 3c:**一集播完自动连播到下一集。**
     *
     * 用户报的"切集异常"就落在这里。这条路和其它三条都不一样:它由 `Player.STATE_ENDED` 触发,
     * 经 `PlaybackService` 的监听器 → `PlaybackEndedAdvancer` → `AutoPlayNextController` →
     * `AudioPlaybackEngine.play`,中间还夹着一次进度上报。
     *
     * 为了不用等一整集播完,这里从**距结尾 [AUTOPLAY_TAIL_MS] 处**开始播 —— 转码流从这个
     * `startTimeTicks` 起,播到头就是真实的 `STATE_ENDED`,和播完整集触发的是同一个回调。
     *
     * 断言分两级:引擎当前 `Ready` 的条目 id 必须换成**本剧的下一集**(状态真的推进了),
     * 而且**新的一集要真的在出声**(位置继续前进)——只换 id 不出声正是"切集之后没声音"。
     *
     * **队列里刻意放一条别的剧的条目当诱饵**([decoyItem]):这正是 2026-07-29 用户报的问题的形状——
     * 首页分区把整行当队列传进来,一行里全是不同的剧。连播必须跟着**剧**走,取
     * [secondItem](服务端给的本剧本季集序里的下一集),而不是队列里排在后面的那条诱饵。
     */
    @Test
    fun `一集播完自动连播到本剧的下一集而不是队列里的下一项`() {
        val next = secondItem
        // 队列 = 被点开的这一集 + 一条别的剧的诱饵(拿不到诱饵时退化成单条队列,断言依旧成立)。
        val queue = listOfNotNull(playableItem, decoyItem)
        playQueue.setQueue(queue, 0)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))

        val nearEnd = ((playableItem.runTimeMs ?: 0L) - AUTOPLAY_TAIL_MS).coerceAtLeast(0L)
        runBlocking(Dispatchers.Main) { engine.play(playableItem.id, userId, nearEnd) }
        assertTrue(
            "第一集都没播起来,自动连播无从谈起。${diagnostics()}",
            awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
        )

        val switched = awaitCondition(AUTOPLAY_TIMEOUT_MS) { currentReadyItemId() == next.id }
        assertTrue(
            "第一集播完之后没有连播到**本剧的下一集**:引擎当前条目是 ${currentReadyItemId()}," +
                "期望 ${next.id}(诱饵是 ${decoyItem?.id})。${diagnostics()}",
            switched,
        )

        val from = position()
        val playing = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_AFTER_SEEK_MS }
        assertNoPlaybackError("自动连播之后")
        assertTrue(
            "连播到下一集之后没有出声:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms。${diagnostics()}",
            playing,
        )
    }

    // ---------------------------------------------------------------- 场景 4

    /**
     * 场景 4:**切后台之后播放继续。**
     *
     * 先真的把 [MainActivity] 拉到前台(这样"切后台"才有意义),开始播放,确认在走,
     * 然后按 HOME 把进程推到后台,再确认位置仍然在前进。
     *
     * 这是 `MediaSessionService` + `foregroundServiceType=mediaPlayback` 唯一真正被验证的地方:
     * 前台服务没起来、或者起晚了(`startForegroundService` 后 5s 内没 `startForeground`),
     * 系统会掐掉服务,表现就是"锁屏就停"。
     */
    @Test
    fun `切后台后播放继续`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()

            startPlayback(playableItem, startPositionMs = 0L)
            assertTrue(
                "前台都没播起来,后台测试无从谈起。${diagnostics()}",
                awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
            )

            // 真的按 HOME —— 比 moveToState(CREATED) 更接近用户行为(进程整体进入后台)。
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            instrumentation.waitForIdleSync()

            val from = position()
            val advanced = awaitCondition(BACKGROUND_WINDOW_MS) { position() - from >= MIN_BACKGROUND_ADVANCE_MS }
            assertTrue(
                "切到后台后播放停了:${BACKGROUND_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms," +
                    "要求 ≥ ${MIN_BACKGROUND_ADVANCE_MS}ms。${diagnostics()}",
                advanced,
            )
        } finally {
            runCatching { scenario.close() }
        }
    }

    // ---------------------------------------------------------------- 会话预置

    /**
     * 预置会话:用真实 API 登录拿 token,直接写进 [ServerStore] 并置为激活。
     *
     * 刻意**绕开登录表单**。理由不是图省事:上一个现场 bug 就是软键盘在密码框里悄悄改写了输入
     * (自动纠错/首字母大写),在 instrumentation 里同样不可控 —— 让它参与进来只会让播放测试
     * 因为一个跟播放无关的原因变红。登录表单本身的验收放在别处。
     */
    private suspend fun seedSession() {
        val endpoint = Endpoint(url = TestCredentials.baseUrl, label = "e2e", priority = 0)
        val loginApi = apiFactory.create(endpoint)
        val auth = try {
            loginApi.authenticate(AuthRequestDto(TestCredentials.username, TestCredentials.password))
        } catch (e: Exception) {
            throw AssertionError("登录真实服务器失败:${TestCredentials.redact(e.toString())}", e)
        }

        serverStore.upsert(
            Server(
                id = E2E_SERVER_ID,
                name = "e2e",
                endpoints = listOf(endpoint),
                userId = auth.user.id,
                accessToken = auth.accessToken,
            )
        )
        serverStore.setActive(E2E_SERVER_ID)
        // 上一条测试可能把会话缓存在别的 endpoint 上;强制下一次请求重新选路。
        session.invalidate()
    }

    /**
     * 挑一对能跑完这几个场景的条目:**一集 + 它在本剧本季集序里真正的下一集**。
     *
     * 为什么必须成对地从服务端集序里挑,而不是"列表里随便两条":自动连播现在跟着**剧**走
     * (见 `AutoPlayNextController`),期望落点只能由服务端的集序决定。条目全部**动态挑选**,
     * 不把任何条目 id / 剧名写死进代码。
     *
     * 第一条优先挑够长的(seek 场景要跳到 10 分钟)。挑不到就让测试**失败**而不是跳过 ——
     * 服务器配好了却凑不出"一集 + 下一集",那是需要人看一眼的事实,不是"环境没配"。
     */
    private suspend fun pickPlayableItems(userId: String): Pair<MediaItem, MediaItem> {
        val episodes = runCatching { api.items(userId = userId, types = "Episode", limit = 50).items }
            .getOrElse { emptyList() }
            .mapNotNull { it.toMediaItem() }
        if (episodes.isEmpty()) {
            throw AssertionError("服务器上找不到任何 Episode 条目,无法验证「连播跟着剧走」。")
        }

        // 够长的优先,其余按原顺序兜底;第一个能问出"下一集"的就是这一对。
        val ordered = episodes.sortedByDescending { (it.runTimeMs ?: 0L) >= MIN_ITEM_RUNTIME_MS }
        return ordered.firstNotNullOfOrNull { candidate ->
            nextEpisodeInSeason(candidate, userId)?.let { candidate to it }
        } ?: throw AssertionError("服务器上找不到「同剧同季后面还有一集」的剧集,无法验证自动连播。")
    }

    /** 服务端集序里 [episode] 的下一集(同剧同季);它已经是本季最后一集时返回 null。 */
    private suspend fun nextEpisodeInSeason(episode: MediaItem, userId: String): MediaItem? {
        val seriesId = episode.seriesId ?: return null
        val seasonId = episode.seasonId ?: return null
        val season = runCatching {
            api.episodes(seriesId, seasonId, userId).items.mapNotNull { it.toMediaItem() }
        }.getOrElse { return null }
        val index = season.indexOfFirst { it.id == episode.id }
        return if (index >= 0 && index < season.lastIndex) season[index + 1] else null
    }

    /** 诱饵:任何一条**别的剧**的剧集(见 [decoyItem])。凑不出来就返回 null。 */
    private suspend fun pickDecoy(userId: String, avoid: MediaItem): MediaItem? =
        runCatching { api.items(userId = userId, types = "Episode", limit = 50).items }
            .getOrElse { emptyList() }
            .mapNotNull { it.toMediaItem() }
            .firstOrNull { it.seriesId != null && it.seriesId != avoid.seriesId }

    /**
     * 严格复刻 `AppSessionViewModel.play()` 的调用序列 —— 队列、前台服务、引擎,一步不差。
     * 测试和生产走的必须是同一条路,否则测出来的绿色毫无意义。
     */
    private fun startPlayback(item: MediaItem, startPositionMs: Long) {
        playQueue.setQueue(listOf(item), 0)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        runBlocking(Dispatchers.Main) { engine.play(item.id, userId, startPositionMs) }
    }

    private fun seekTargetMs(item: MediaItem): Long {
        val runtime = item.runTimeMs ?: 0L
        return if (runtime >= MIN_ITEM_RUNTIME_MS) DEFAULT_SEEK_TARGET_MS else (runtime / 2).coerceAtLeast(30_000L)
    }

    /**
     * 连点快进的目标序列。刻意按**条目时长的比例**取,而不是"600s 起、每次 +30s" ——
     * 后者对一个 8 分钟的条目会请求超过结尾的 `startTimeTicks`,测出来的是服务端边界行为,
     * 不是本项目要验证的东西。
     *
     * **最后一次落在 [tailSeekTargetMs](距结尾 [TAIL_SEEK_MARGIN_MS])**,这样"最后一次说了算"
     * 可以用 `STATE_ENDED` 判别(见 [awaitEnded]);前几次均匀铺在它之前,彼此相距足够远,
     * 落错了目标范围断言就红。
     */
    private fun rapidSeekTargets(item: MediaItem): List<Long> {
        val tail = tailSeekTargetMs(item)
        return (1 until RAPID_SEEK_COUNT).map { tail * it / (RAPID_SEEK_COUNT + 1) } + tail
    }

    // ------------------------------------------------- 偏移是否真的生效(复审 Important 2)

    /**
     * 尾部落点:距结尾 [TAIL_SEEK_MARGIN_MS]。
     *
     * 之所以必须**贴着结尾**取:这是唯一一个"服务端认不认 `startTimeTicks`"会产生**数量级差异**的
     * 可观测点 —— 认了,这条流只剩 30 秒,很快 `STATE_ENDED`;忽略了(L3 曾经的 `static=true` 就是这样),
     * 这条流是整整一集,几十分钟内绝不可能 ENDED。
     */
    private fun tailSeekTargetMs(item: MediaItem): Long {
        val runtime = item.runTimeMs ?: 0L
        // 判别力的前提:忽略偏移时"从头放完整条"必须**明显长于** ENDED 的等待时限,否则两种情况
        // 都可能在时限内 ENDED,这条断言就又变成一句空话了。
        val minimum = TAIL_END_TIMEOUT_MS + TAIL_SEEK_MARGIN_MS
        assertTrue(
            "条目时长 ${runtime}ms 太短(要求 ≥ ${minimum}ms),尾部落点判别不出 startTimeTicks " +
                "是否生效 —— 从头重播也能在 ${TAIL_END_TIMEOUT_MS}ms 内播完。换一个更长的条目。",
            runtime >= minimum,
        )
        return runtime - TAIL_SEEK_MARGIN_MS
    }

    /**
     * 🔴 **本文件唯一能证明"seek 真的发生了"的断言。**
     *
     * [position] = `currentStartPositionMs + 流内位置`,而 `AudioPlaybackEngineImpl` 在 `resolve()`
     * 一返回就把 `currentStartPositionMs` 设成请求的目标 —— 所以"落到目标区间 + 还在前进"这两段
     * 断言在**服务端把 `startTimeTicks` 整个忽略掉、从第 0 秒重播**的时候**照样全绿**。
     * 这正是 v2-task-2 报告 §8 记录的真实缺陷:L3 兜底 URL 曾经带 `static=true`,服务端直接吐原始文件。
     *
     * 判别办法:从距结尾 [TAIL_SEEK_MARGIN_MS] 处开始播,要求 [TAIL_END_TIMEOUT_MS] 内收到
     * `STATE_ENDED`。认了偏移 → 30 秒后就到;忽略了偏移 → 要放完整整一集(挑选条目时已保证
     * ≥ [MIN_ITEM_RUNTIME_MS]),这个时限内不可能到。判别力来自这个数量级差,不依赖任何时序抖动。
     *
     * 为什么用监听器而不是轮询 `exoPlayer.playbackState`:ENDED 只存在一瞬间,紧接着
     * `PlaybackService` 的自动连播监听器就会 prepare 下一条流,轮询会整个错过它。
     */
    private class EndedLatch : Player.Listener {
        @Volatile
        var ended = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) ended = true
        }
    }

    private fun addEndedLatch(): EndedLatch =
        EndedLatch().also { latch -> onMain { exoPlayer.addListener(latch) } }

    private fun removeEndedLatch(latch: EndedLatch) {
        runCatching { onMain { exoPlayer.removeListener(latch) } }
    }

    private fun awaitEnded(latch: EndedLatch, label: String) {
        val ended = awaitCondition(TAIL_END_TIMEOUT_MS) { latch.ended }
        assertTrue(
            "$label:${TAIL_END_TIMEOUT_MS / 1000}s 内没有收到 STATE_ENDED。" +
                "这条流本该只剩 ${TAIL_SEEK_MARGIN_MS / 1000}s —— 收不到 ENDED 说明服务端**忽略了 " +
                "startTimeTicks**,实际在从头重播,而 absolutePositionMs 还在按'起始位置 + 流内位置'" +
                "报数(界面显示末尾,耳朵听到开头)。历史触发路径:落到 L3 时 URL 带 static=true。" +
                "${diagnostics()}",
            ended,
        )
    }

    /** 见 [awaitEnded]。在当前已经在播的条目上再 seek 一次到尾部,然后等 ENDED。 */
    private fun assertSeekOffsetHonoredAtTail(item: MediaItem, label: String) {
        val tail = tailSeekTargetMs(item)
        val latch = addEndedLatch()
        try {
            runBlocking(Dispatchers.Main) { engine.seekTo(tail) }
            assertNoPlaybackError("$label:尾部 seek 之后")
            awaitEnded(latch, "$label:seek 到 ${tail}ms(距结尾 ${TAIL_SEEK_MARGIN_MS}ms)")
        } finally {
            removeEndedLatch(latch)
        }
    }

    /** 引擎当前 `Ready` 的条目 id;没有就绪的源时为 null。 */
    private fun currentReadyItemId(): String? = onMain {
        (engine.state.value as? dev.insua.jellycast.player.PlaybackEngineState.Ready)?.source?.itemId
    }

    // ---------------------------------------------------------------- 工具

    /** 位置的唯一权威;必须在 ExoPlayer 的 application thread 上读。 */
    private fun position(): Long = onMain { engine.absolutePositionMs }

    /**
     * 条件等待:轮询 + 超时。**不要**改成固定 `Thread.sleep` 后断言 ——
     * 那样既拖慢测试,又把"慢"和"根本没动"混成同一种失败。
     */
    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun assertNoPlaybackError(context: String) {
        val error = onMain { exoPlayer.playerError }
        if (error != null) {
            throw AssertionError(
                "$context ExoPlayer 报错:${TestCredentials.redact(error.toString())}。${diagnostics()}",
                error,
            )
        }
        val state = onMain { engine.state.value }
        if (state is dev.insua.jellycast.player.PlaybackEngineState.Error) {
            fail("$context 引擎进入 Error 状态:${TestCredentials.redact(state.toString())}。${diagnostics()}")
        }
    }

    /** 失败时的现场快照。全部经 [TestCredentials.redact] —— 流 URL 里有 token。 */
    private fun diagnostics(): String = onMain {
        buildString {
            append("\n--- 现场 ---")
            append("\n  absolutePositionMs = ").append(engine.absolutePositionMs)
            append("\n  engineState        = ").append(TestCredentials.redact(engine.state.value.toString()))
            append("\n  exo.playbackState  = ").append(playbackStateName(exoPlayer.playbackState))
            append("\n  exo.isPlaying      = ").append(exoPlayer.isPlaying)
            append("\n  exo.playWhenReady  = ").append(exoPlayer.playWhenReady)
            append("\n  exo.currentPos     = ").append(exoPlayer.currentPosition)
            append("\n  exo.playerError    = ").append(TestCredentials.redact(exoPlayer.playerError?.toString()))
            append("\n  controller         = ").append(
                playerConnection.player.value?.let { "connected(isPlaying=${it.isPlaying})" } ?: "null(未连上会话)"
            )
            append("\n  queueCurrent       = ").append(playQueue.current.value?.id ?: "null")
            append("\n------------")
        }
    }

    private fun playbackStateName(state: Int): String = when (state) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    /** 把 [block] 同步派到主线程执行并把异常原样带回调用线程。 */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "runOnMainSync 没有执行 block" }.getOrThrow()
    }

    private companion object {
        const val E2E_SERVER_ID = "e2e-server"

        /** 拿到第一条流并出声的宽限。模拟器 + 服务端起转码,比真机慢。 */
        const val STARTUP_TIMEOUT_MS = 30_000L

        /** 计划里的"10s 内前进 ≥5s"。 */
        const val ADVANCE_WINDOW_MS = 10_000L
        const val MIN_ADVANCE_MS = 5_000L

        const val CONTINUOUS_PLAY_MS = 30_000L
        const val SAMPLE_INTERVAL_MS = 5_000L

        /** 30s 里至少要走 25s —— 留出采样抖动,但走不到 25s 就说明中间卡过。 */
        const val MIN_CONTINUOUS_ADVANCE_MS = 25_000L

        const val DEFAULT_SEEK_TARGET_MS = 600_000L
        const val SEEK_TIMEOUT_MS = 30_000L
        const val SEEK_TOLERANCE_BEFORE_MS = 10_000L
        const val SEEK_TOLERANCE_AFTER_MS = 30_000L
        const val MIN_ADVANCE_AFTER_SEEK_MS = 3_000L

        /** 连点快进:次数与间隔。间隔取得比一次 resolve 的耗时短,才能真的把并发窗口打开。 */
        const val RAPID_SEEK_COUNT = 4
        const val RAPID_SEEK_INTERVAL_MS = 700L

        /**
         * 尾部落点距结尾的距离(见 [tailSeekTargetMs])。够短,ENDED 很快就到;
         * 够长,落点之后仍然能先验一段"还在前进"。
         */
        const val TAIL_SEEK_MARGIN_MS = 30_000L

        /**
         * 尾部落点到 `STATE_ENDED` 的宽限:30s 内容 + 起转码/缓冲/网络往返的余量。
         * 偏移被忽略时这条流是整整一集(≥ [MIN_ITEM_RUNTIME_MS] = 15 分钟),这个时限内不可能 ENDED。
         */
        const val TAIL_END_TIMEOUT_MS = 120_000L

        /** 自动连播:从距结尾这么近的地方开播,不用等一整集。 */
        const val AUTOPLAY_TAIL_MS = 25_000L
        const val AUTOPLAY_TIMEOUT_MS = 120_000L

        const val BACKGROUND_WINDOW_MS = 20_000L
        const val MIN_BACKGROUND_ADVANCE_MS = 8_000L

        /** seek 到 10 分钟需要条目至少这么长。 */
        const val MIN_ITEM_RUNTIME_MS = 900_000L

        const val POLL_INTERVAL_MS = 250L
    }
}
