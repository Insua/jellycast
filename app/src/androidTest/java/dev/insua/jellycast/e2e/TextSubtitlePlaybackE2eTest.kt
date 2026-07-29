package dev.insua.jellycast.e2e

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
import dev.insua.jellycast.player.PlaybackEngineState
import dev.insua.jellycast.player.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * # 崩溃回归:播放一个**带文本字幕轨**的条目,进程必须存活、位置必须前进。
 *
 * 这条测试直接对应
 * docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2 记录的根因链——
 * `SubtitleTags` 里一个未转义的正则曾经在 Android 14+ 的 ICU 引擎上编译失败,以
 * `ExceptionInInitializerError`(是 `Error`,不是 `Exception`)逃逸,穿透
 * `PlayerViewModel` 未设防的 `viewModelScope.launch`,杀掉了承载这个 instrumentation 进程本身的
 * app 进程——也就是说,这个 bug 复现时,这条测试自己会随着进程一起死掉,而不是报告一个
 * 普通的断言失败。三层修复(见 `:core:subtitle` 的
 * [dev.insua.jellycast.subtitle.SubtitleRepository.load] KDoc)都在这条路径上。
 *
 * ## 条目挑选:动态,不写死
 *
 * 在服务器上找第一个 `MediaStreams` 里存在 `IsTextSubtitleStream = true` 的 Episode/Movie,
 * 不写死任何具体条目 id——服务器内容会变,写死 id 的测试会变成脆弱的摆设,而且题材本身
 * (剧名/电影名)不该出现在代码里。找不到就用 [assumeTrue] 跳过而不是判失败:这是环境依赖
 * (服务器上恰好有没有带文本字幕轨的内容),不是本测试要验证的行为。
 *
 * 🔴 凭据纪律同 [PlaybackE2eTest]:服务器地址/账号/密码来自 [TestCredentials],所有输出经
 * [TestCredentials.redact];条目标题/剧名不打印、不出现在断言消息里。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TextSubtitlePlaybackE2eTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var serverStore: ServerStore
    @Inject lateinit var apiFactory: JellyfinApiFactory
    @Inject lateinit var api: JellyfinApi
    @Inject lateinit var session: JellyfinSession
    @Inject lateinit var engine: AudioPlaybackEngine
    @Inject lateinit var exoPlayer: ExoPlayer
    @Inject lateinit var playQueue: PlayQueue

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var userId: String
    private lateinit var subtitledItem: MediaItem

    @Before
    fun setUp() {
        TestCredentials.assumeConfigured()

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
        }

        val found = runBlocking { findItemWithTextSubtitle(userId) }
        assumeTrue(
            "服务器上找不到任何带文本字幕轨的 Episode/Movie,跳过——这是环境依赖,不是本用例要验证的行为。",
            found != null,
        )
        subtitledItem = found!!
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

    /**
     * 断言纪律与 [PlaybackE2eTest] 一致:断"位置真的在前进",不是"没抛异常"。
     * 这条用例本身能跑到结尾,就是"三层防御生效、进程没被杀"最直接的证据。
     */
    @Test
    fun `播放带文本字幕轨的条目时进程存活且位置前进`() {
        playQueue.setQueue(listOf(subtitledItem), 0)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        runBlocking(Dispatchers.Main) { engine.play(subtitledItem.id, userId, 0L) }

        val started = awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L }
        assertTrue(
            "播放从未开始:${STARTUP_TIMEOUT_MS / 1000}s 内位置一直是 0。${diagnostics()}",
            started,
        )
        assertNoPlaybackError()

        val from = position()
        val advanced = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_MS }
        assertNoPlaybackError()
        assertTrue(
            "播放开始后停住了:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms。${diagnostics()}",
            advanced,
        )
    }

    // ---------------------------------------------------------------- 会话预置

    private suspend fun seedSession() {
        val endpoint = Endpoint(url = TestCredentials.baseUrl, label = "e2e-subtitle", priority = 0)
        val loginApi = apiFactory.create(endpoint)
        val auth = try {
            loginApi.authenticate(AuthRequestDto(TestCredentials.username, TestCredentials.password))
        } catch (e: Exception) {
            throw AssertionError("登录真实服务器失败:${TestCredentials.redact(e.toString())}", e)
        }
        serverStore.upsert(
            Server(
                id = E2E_SERVER_ID,
                name = "e2e-subtitle",
                endpoints = listOf(endpoint),
                userId = auth.user.id,
                accessToken = auth.accessToken,
            )
        )
        serverStore.setActive(E2E_SERVER_ID)
        // 上一条测试可能把会话缓存在别的 endpoint 上;强制下一次请求重新选路。
        session.invalidate()
    }

    // ---------------------------------------------------------------- 条目挑选(动态,不写死 id)

    /**
     * 找第一个带文本字幕轨的条目:按 `PlaybackInfo` 返回的 `MediaStreams` 里是否存在
     * `IsTextSubtitleStream = true` 的 Subtitle 流判断。Episode 优先,找不到再看 Movie。
     * 单个条目的 `playbackInfo` 拉取失败不该中断整条扫描,跳过继续看下一个候选。
     */
    private suspend fun findItemWithTextSubtitle(userId: String): MediaItem? {
        val candidates = mutableListOf<BaseItemDto>()
        candidates += runCatching { api.items(userId = userId, types = "Episode", limit = SCAN_LIMIT).items }
            .getOrElse { emptyList() }
        candidates += runCatching { api.items(userId = userId, types = "Movie", limit = SCAN_LIMIT).items }
            .getOrElse { emptyList() }

        for (candidate in candidates) {
            val hasTextSubtitle = runCatching { api.playbackInfo(candidate.id, userId) }
                .getOrNull()
                ?.mediaSources
                ?.firstOrNull()
                ?.mediaStreams
                ?.any { it.type == "Subtitle" && it.isTextSubtitle }
                ?: false
            if (hasTextSubtitle) return candidate.toMediaItem()
        }
        return null
    }

    // ---------------------------------------------------------------- 工具(与 PlaybackE2eTest 同形)

    private fun position(): Long = onMain { engine.absolutePositionMs }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun assertNoPlaybackError() {
        val error = onMain { exoPlayer.playerError }
        if (error != null) {
            throw AssertionError(
                "ExoPlayer 报错:${TestCredentials.redact(error.toString())}。${diagnostics()}",
                error,
            )
        }
        val state = onMain { engine.state.value }
        if (state is PlaybackEngineState.Error) {
            throw AssertionError("引擎进入 Error 状态:${TestCredentials.redact(state.toString())}。${diagnostics()}")
        }
    }

    /** 失败时的现场快照。全部经 [TestCredentials.redact]。 */
    private fun diagnostics(): String = onMain {
        buildString {
            append("\n--- 现场 ---")
            append("\n  absolutePositionMs = ").append(engine.absolutePositionMs)
            append("\n  engineState        = ").append(TestCredentials.redact(engine.state.value.toString()))
            append("\n  exo.playbackState  = ").append(exoPlayer.playbackState)
            append("\n  exo.playerError    = ").append(TestCredentials.redact(exoPlayer.playerError?.toString()))
            append("\n------------")
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "runOnMainSync 没有执行 block" }.getOrThrow()
    }

    private companion object {
        const val E2E_SERVER_ID = "e2e-subtitle-server"

        /** 扫描候选的上限:够大才有合理概率命中带文本字幕轨的条目。 */
        const val SCAN_LIMIT = 60

        const val STARTUP_TIMEOUT_MS = 30_000L
        const val ADVANCE_WINDOW_MS = 10_000L
        const val MIN_ADVANCE_MS = 5_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
