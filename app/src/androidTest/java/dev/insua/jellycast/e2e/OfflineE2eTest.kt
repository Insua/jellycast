package dev.insua.jellycast.e2e

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.insua.jellycast.MainActivity
import dev.insua.jellycast.database.JellyCastDatabase
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.designsystem.OfflineBannerTestTags
import dev.insua.jellycast.feature.home.HomeUiState
import dev.insua.jellycast.feature.home.HomeViewModel
import dev.insua.jellycast.feature.library.LibraryViewModel
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.feature.server.JellyfinApiFactory
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.navigation.AppSessionViewModel
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.AudioPlaybackEngine
import dev.insua.jellycast.player.AutoPlayNextController
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * # 断网场景端到端验证 —— 关掉 VPN 打开 App 到底会不会闪退
 *
 * 用户报的头号缺陷只有一句话:**没开 Tailscale 时进 App 直接闪退。** 这个文件就是它的自动复现器。
 *
 * ## 断言纪律:第一断言永远是"进程还活着"
 *
 * "屏幕上出现了错误提示"这种断言毫无价值 —— 提示画出来了、一秒后进程照样死掉,它一样是绿的。
 * 所以每个用例都:
 * 1. 记下 `@Before` 时的 pid([startPid]),结束时断言它没变 —— instrumentation 跑在**被测应用
 *    自己的进程**里,应用进程死掉等于测试进程死掉,pid 不变是"从头到尾没重启过"的直接证据;
 * 2. 断网状态下真的把 [MainActivity] 拉起来,并要求它到达 `RESUMED` **并停在那里**
 *    ([assertActivitySurvives])—— 冷启动崩溃正是发生在这一段。
 *
 * ## 覆盖设计文档 §3.2 的四个状态
 *
 * | 状态 | 用例 |
 * |---|---|
 * | 有缓存 + 网络正常 → 直接显示、后台刷新 | [有缓存断网时显示上次内容并标记离线] 的预热段 |
 * | 有缓存 + 断网 → 立刻显示缓存 + 离线提示 | [有缓存断网时显示上次内容并标记离线] |
 * | 无缓存 + 断网 → 可重试错误页,不崩溃 | [无缓存断网时进入可重试错误态] |
 * | 断网时点播放 → 明确提示,不静默失败 | [断网时点播放给出明确提示] |
 *
 * ## 模拟冷启动
 *
 * 断网之后必须调一次 [JellyfinSession.invalidate]:进程里已经解析好的 endpoint 会一直被复用,
 * 不 invalidate 的话测的是"用着旧连接的热进程",而用户遇到的是**冷启动**——那一刻 endpoint
 * 还没解析过,所有依赖选路的东西(包括读缓存)都要在没有网络的情况下走一遍。
 *
 * ## 🔴 凭据 / 网络恢复
 *
 * 服务器地址与账号来自 [TestCredentials],一律经 `redact` 脱敏后才允许进入断言消息。
 * 网络恢复见 [NetworkControl] —— `@After` 里**无条件**执行,不允许被任何提前 return 挡住。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OfflineE2eTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    /**
     * 用 [createEmptyComposeRule] 而不是 `createAndroidComposeRule<MainActivity>()`:后者会在规则
     * 开始时**立刻**拉起 Activity,也就是在 `@Before` 预置会话/预热缓存之前 —— 那样测的是一个还
     * 没有服务器、也没有缓存的首页,四个状态一个都复现不出来。这里由用例自己决定何时 launch。
     */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    // 全部是生产实现,没有一个 fake。
    @Inject lateinit var serverStore: ServerStore
    @Inject lateinit var apiFactory: JellyfinApiFactory
    @Inject lateinit var api: JellyfinApi
    @Inject lateinit var session: JellyfinSession
    @Inject lateinit var repository: MediaRepository
    @Inject lateinit var database: JellyCastDatabase
    @Inject lateinit var playQueue: PlayQueue
    @Inject lateinit var engine: AudioPlaybackEngine
    @Inject lateinit var autoPlayNextController: AutoPlayNextController
    @Inject lateinit var playerConnection: PlayerConnection
    @Inject lateinit var lastPlayedStore: LastPlayedStore

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private var startPid: Int = 0

    private var trackedCount: Int = 0

    /**
     * 本用例手工 new 出来的 ViewModel 都放进这里,`@After` 统一 [ViewModelStore.clear]。
     *
     * 不这么做的后果是**实测到的**:被丢下的 [AppSessionViewModel] 的 `viewModelScope` 永远不会
     * 取消,它那条 500ms 的迷你条轮询协程和引擎状态订阅会活到整个 instrumentation 进程结束,
     * 继续在主线程上读同一个 `@Singleton` 的 ExoPlayer —— 于是**后面**跑的播放端到端用例开始
     * 莫名其妙地红,而失败信息里没有任何线索指向这里。测试之间不许互相下毒。
     */
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        TestCredentials.assumeConfigured()
        startPid = Process.myPid()

        hilt.inject()

        // 预置阶段需要网络:上一个用例万一留下了飞行模式,这里先无条件恢复。
        NetworkControl.goOnline()
        runBlocking { seedSession() }
    }

    /**
     * 🔴 无条件恢复网络。**不要**在这里写 `if (!TestCredentials.isConfigured) return` 之类的
     * 提前返回:哪怕 `@Before` 在 `assumeConfigured` 就跳过了,只要 `@After` 跑到了就必须恢复,
     * 否则一次跳过也可能把设备留在飞行模式里。
     */
    @After
    fun tearDown() {
        runCatching { instrumentation.runOnMainSync { viewModelStore.clear() } }
        runCatching {
            instrumentation.runOnMainSync {
                runCatching { engine.reset() }
            }
        }
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        NetworkControl.goOnline()

        // 「系统重新有了默认网络」不等于「到服务器的那条路已经通」。下一个用例(真播放的端到端)
        // 一上来就要连服务器起转码 —— 交给它一台"看起来在线、实际还没通"的设备,失败会落在
        // **别人的**用例上,而失败信息里不会有任何线索指向这里。所以这里等到一次真实请求成功为止。
        if (TestCredentials.isConfigured) {
            session.invalidate()
            assertTrue(
                "恢复联网后 ${SERVER_REACHABLE_TIMEOUT_MS / 1000}s 内没能连上服务器 ${HEALTHY_PROBES} 次 —— " +
                    "设备被留在了一个半通的状态,后续用例会连带失败",
                awaitServerHealthy(),
            )
        }
    }

    // ---------------------------------------------------------------- 状态 1 + 2

    /**
     * **有缓存 + 断网:立刻显示上次的内容,并标记离线。**
     *
     * 三段:
     * - 联网预热(状态 1:直接显示 + 后台刷新写回缓存);
     * - 断网 + `invalidate()` 模拟冷启动,新建一个首页 ViewModel,要求它**仍然有内容**
     *   且 `isOffline == true`、`error == null`(状态 2);
     * - 断网状态下真的把 Activity 拉起来,要求它活着(头号缺陷的直接复现点)。
     */
    @Test
    fun 有缓存断网时显示上次内容并标记离线() {
        // ── 状态 1:联网时正常取数,顺带把缓存写满 ──
        val warmUp = homeViewModel()
        val online = awaitHome(warmUp) { !it.isLoading }
        assertTrue(
            "联网预热就没拿到内容,断网场景无从验证(sections=${online.sections.size}," +
                "error=${TestCredentials.redact(online.error)})",
            online.sections.isNotEmpty(),
        )
        assertTrue("联网时不该标记离线", !online.isOffline)

        // ── 断网 + 模拟冷启动 ──
        NetworkControl.goOffline()
        session.invalidate()

        // ── 状态 2:仍然要看到上次的内容 ──
        val offline = homeViewModel()
        val state = awaitHome(offline) { !it.isLoading }
        assertTrue(
            "断网冷启动时首页是空的 —— 缓存没有被用上。用户看到的就是白屏(或更糟,闪退)。" +
                "sections=${state.sections.size},error=${TestCredentials.redact(state.error)}",
            state.sections.isNotEmpty(),
        )
        assertNull("有缓存可显示时不该盖上整页错误", TestCredentials.redact(state.error).takeIf { state.error != null })
        assertTrue(
            "刷新明明失败了,却没有标记离线 —— 顶部那条「离线,显示的是上次内容」提示不会出现",
            state.isOffline,
        )

        // ── 断网下真的开 App:不许崩,而且离线提示条要真的看得见 ──
        assertActivitySurvives("有缓存 + 断网") {
            val appeared = compose.runCatching {
                waitUntil(BANNER_TIMEOUT_MS) {
                    onAllNodesWithTag(OfflineBannerTestTags.ROOT).fetchSemanticsNodes().isNotEmpty()
                }
            }.isSuccess
            assertTrue("断网 + 有缓存时,首页顶部应出现「离线,显示的是上次内容」提示条", appeared)
            // 🔴 `assertIsDisplayed` 而不是 `assertExists`:组合出来了但被顶到视口外,对用户等于
            // 不存在。真机上真出现过这种形状 —— 状态是对的、屏幕上什么都没有。
            compose.onNodeWithTag(OfflineBannerTestTags.ROOT).assertIsDisplayed()
        }
        assertProcessAlive()
    }

    // ---------------------------------------------------------------- 状态 3

    /**
     * **无缓存 + 断网:可重试的错误态,绝不崩溃。**
     *
     * 先把缓存表整个清空,再断网 —— 这是"第一次装上 App 就没开 VPN"的形状。
     * 要求:首页给出错误文案(可点重试),媒体库同样收敛到错误态,而不是抛异常/白屏/闪退。
     */
    @Test
    fun 无缓存断网时进入可重试错误态() {
        runBlocking { database.clearAllTables() }

        NetworkControl.goOffline()
        session.invalidate()

        val home = awaitHome(homeViewModel()) { !it.isLoading }
        assertTrue("无缓存 + 断网时首页不该有内容", home.sections.isEmpty())
        assertNotNull("无缓存 + 断网时首页必须给出可重试的错误提示,而不是一片空白", home.error)

        val library = libraryViewModel()
        val libraryFailed = awaitCondition(STATE_TIMEOUT_MS) {
            library.uiState.value.series.error != null
        }
        assertTrue(
            "无缓存 + 断网时媒体库必须收敛到错误态(可重试),实际:" +
                "isLoading=${library.uiState.value.series.isLoading}," +
                "items=${library.uiState.value.series.items.size}",
            libraryFailed,
        )

        assertActivitySurvives("无缓存 + 断网")
        assertProcessAlive()
    }

    // ---------------------------------------------------------------- 状态 4

    /**
     * **断网时点播放:给出明确提示,不静默失败、不崩溃。**
     *
     * 走的是生产里唯一的播放入口 [AppSessionViewModel.play](首页/媒体库点条目最终都落到它)。
     * 断言的是"用户看得见的提示",而不是"没抛异常" —— 静默无反应和闪退一样,都是用户口中的
     * "点了没用"。
     */
    @Test
    fun 断网时点播放给出明确提示() {
        val item = runBlocking { pickPlayableItem() }

        NetworkControl.goOffline()
        session.invalidate()

        val sessionViewModel = appSessionViewModel()
        instrumentation.runOnMainSync { sessionViewModel.play(item, listOf(item)) }

        val messaged = awaitCondition(PLAY_MESSAGE_TIMEOUT_MS) { sessionViewModel.message.value != null }
        assertTrue(
            "断网时点播放没有给出任何提示 —— 用户面对的是「点了没反应」。" +
                "这条路必须收敛到「需要连接服务器才能播放」,不许静默 return。",
            messaged,
        )
        assertTrue(
            "提示文案必须点明「要连服务器」,实际是:${TestCredentials.redact(sessionViewModel.message.value)}",
            sessionViewModel.message.value.orEmpty().contains("服务器"),
        )

        assertProcessAlive()
    }

    /**
     * 等到"到服务器的路真的健康了",而不只是"第一次请求碰巧通了"。
     *
     * 为什么要连着成功 [HEALTHY_PROBES] 次(跨度约 30 秒):飞行模式关掉之后,这台模拟器的连通性在**分钟级**的
     * 时间里都是半死不活的(`logcat` 里 NetworkMonitor 的 generate_204 探针接连 10s 超时)。
     * 第一次请求成功并不代表吞吐恢复了 —— 实测的后果是**下一个**用例(播完一集自动连播,窗口
     * 120s)因为起流太慢而超时,失败落在别人身上、失败信息里毫无线索。要求连续多次成功、且每次
     * 之间留出间隔,才能把"刚好通了一下"和"真的恢复了"区分开。
     */
    private fun awaitServerHealthy(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SERVER_REACHABLE_TIMEOUT_MS
        var consecutive = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val ok = runCatching { runBlocking { session.userId(); api.items(userId = session.userId(), types = "Episode", limit = 1) } }.isSuccess
            consecutive = if (ok) consecutive + 1 else 0
            if (consecutive >= HEALTHY_PROBES) return true
            SystemClock.sleep(HEALTHY_PROBE_INTERVAL_MS)
        }
        return false
    }

    // ---------------------------------------------------------------- 断言工具

    /**
     * 断网状态下把 [MainActivity] 拉起来,要求它到达 `RESUMED`,并且在 [ACTIVITY_SETTLE_MS]
     * 之后**仍然**是 `RESUMED`。
     *
     * 为什么要"停一会儿再看一眼":冷启动的网络请求是异步的,崩溃往往发生在首帧之后 ——
     * 只断言"启动成功"会在真正的闪退场景里照样绿。
     */
    private fun assertActivitySurvives(label: String, onScreen: () -> Unit = {}) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            onScreen()
            SystemClock.sleep(ACTIVITY_SETTLE_MS)
            instrumentation.waitForIdleSync()
            assertEquals(
                "$label:MainActivity 在断网状态下没能停留在 RESUMED —— 这就是用户报的「进 App 直接闪退」",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        } finally {
            runCatching { scenario.close() }
        }
    }

    /** 见类注释:instrumentation 与被测应用同进程,pid 不变 = 应用进程从头到尾没有崩溃重启过。 */
    private fun assertProcessAlive() {
        assertEquals(
            "应用进程重启过(pid 变了)—— 断网期间发生了崩溃",
            startPid,
            Process.myPid(),
        )
    }

    // ---------------------------------------------------------------- 构造被测对象

    /** 和生产完全一样的构造参数;这里手工 new 只是因为 `hiltViewModel()` 需要 Composition。 */
    private fun homeViewModel(): HomeViewModel = HomeViewModel(api, session, repository).track()

    private fun libraryViewModel(): LibraryViewModel = LibraryViewModel(api, session, repository).track()

    private fun appSessionViewModel(): AppSessionViewModel =
        AppSessionViewModel(
            serverStore, session, playQueue, engine, autoPlayNextController, playerConnection,
            lastPlayedStore, context,
        ).track()

    /** 交给 [viewModelStore] 保管,`@After` 时统一取消它们的 `viewModelScope`(见字段 KDoc)。 */
    private fun <T : ViewModel> T.track(): T = also {
        viewModelStore.put("vm-${trackedCount++}", it)
    }

    // ---------------------------------------------------------------- 会话预置

    /** 与 [PlaybackE2eTest.seedSession] 同一套:用真实 API 登录拿 token,直接写进 [ServerStore]。 */
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
        session.invalidate()
    }

    private suspend fun pickPlayableItem(): MediaItem {
        val userId = session.userId()
        val items = runCatching { api.items(userId = userId, types = "Episode,Movie", limit = 20).items }
            .getOrElse { emptyList() }
        return items.mapNotNull { it.toMediaItem() }.firstOrNull()
            ?: throw AssertionError("服务器上找不到任何 Episode / Movie 条目,无法验证「断网时点播放」")
    }

    // ---------------------------------------------------------------- 轮询

    private fun awaitHome(viewModel: HomeViewModel, predicate: (HomeUiState) -> Boolean): HomeUiState {
        val settled = awaitCondition(STATE_TIMEOUT_MS) { predicate(viewModel.uiState.value) }
        assertTrue(
            "${STATE_TIMEOUT_MS / 1000}s 内首页状态没有稳定下来:${TestCredentials.redact(viewModel.uiState.value.toString())}",
            settled,
        )
        return viewModel.uiState.value
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private companion object {
        const val E2E_SERVER_ID = "e2e-server"

        /** 断网时一轮选路探测 + 请求超时会走满,给足余量。 */
        const val STATE_TIMEOUT_MS = 90_000L

        const val PLAY_MESSAGE_TIMEOUT_MS = 60_000L

        /** Activity 起来之后再观察这么久,让异步的网络失败有机会把进程带崩(如果它会的话)。 */
        const val ACTIVITY_SETTLE_MS = 8_000L

        /** 冷启动 → 读缓存 → 一轮选路探测失败 → 提示条出现,这一串在模拟器上的宽限。 */
        const val BANNER_TIMEOUT_MS = 60_000L

        /** 恢复联网之后,等到一次真实请求打通服务器的宽限(含一轮并发选路)。 */
        const val SERVER_REACHABLE_TIMEOUT_MS = 120_000L

        /** 见 [awaitServerHealthy]:连着这么多次真实请求都成功,才算网络真的恢复了。 */
        const val HEALTHY_PROBES = 6
        const val HEALTHY_PROBE_INTERVAL_MS = 5_000L

        const val POLL_INTERVAL_MS = 250L
    }
}
