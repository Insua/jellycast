package dev.insua.jellycast.e2e

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.insua.jellycast.MainActivity
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.server.JellyfinApiFactory
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import kotlinx.coroutines.Dispatchers
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
            playableItem = pickPlayableItem(userId)
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
     * 挑一个能跑完这四个场景的条目:优先够长的剧集(seek 到 10 分钟需要足够时长),
     * 没有剧集就退回电影。挑不到就让测试**失败**而不是跳过 —— 服务器配好了却一个可播条目都没有,
     * 那是需要人看一眼的事实,不是"环境没配"。
     */
    private suspend fun pickPlayableItem(userId: String): MediaItem {
        val candidates = mutableListOf<BaseItemDto>()
        candidates += runCatching { api.items(userId = userId, types = "Episode", limit = 50).items }
            .getOrElse { emptyList() }
        if (candidates.none { it.longEnough }) {
            candidates += runCatching { api.items(userId = userId, types = "Movie", limit = 50).items }
                .getOrElse { emptyList() }
        }

        val chosen = candidates.firstOrNull { it.longEnough }
            ?: candidates.firstOrNull()
            ?: throw AssertionError("服务器上找不到任何 Episode / Movie 条目,无法进行端到端播放验证。")

        return chosen.toMediaItem()
            ?: error("条目 ${chosen.id} 的类型 ${chosen.type} 不被 MediaItemMapper 识别")
    }

    private val BaseItemDto.longEnough: Boolean
        get() = (runTimeTicks ?: 0L) / 10_000L >= MIN_ITEM_RUNTIME_MS

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

        const val BACKGROUND_WINDOW_MS = 20_000L
        const val MIN_BACKGROUND_ADVANCE_MS = 8_000L

        /** seek 到 10 分钟需要条目至少这么长。 */
        const val MIN_ITEM_RUNTIME_MS = 900_000L

        const val POLL_INTERVAL_MS = 250L
    }
}
