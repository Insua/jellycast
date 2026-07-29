package dev.insua.jellycast.e2e

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.SeekInterceptingPlayer
import dev.insua.jellycast.player.SeekRouter
import dev.insua.jellycast.player.asAbsoluteTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * # 缺陷「拖动进度条也不好使」/「一加胶囊不显示播放时间」的端到端回归(设计文档 §4)
 *
 * ## 为什么既有的 `PlaybackE2eTest` 覆盖不到
 *
 * 它的 seek 场景直接调 `engine.seekTo(...)`,**绕过了整条 MediaController → MediaSession 链路**。
 * 而 App 里用户能触发的每一次 seek —— 播放页拖进度条、±15s/±30s、锁屏、通知栏、蓝牙 ——
 * 走的都是 `MediaController`。取证时那条路是**完全不通**的:
 *
 * ```
 * ctl.cmdSeekInItem=false ctl.cmdSeekBack=false ctl.cmdSeekFwd=false
 * W MCImplBase: Controller isn't allowed to call command= 5
 * SEEK landedAfterMs=-1        ← 20 秒内位置一步没动
 * ```
 *
 * 根因见 [SeekInterceptingPlayer] 的两段 KDoc(可用命令声明 + 命令变更通知的纠正)。
 * 本用例把那条真实链路钉住:**命令必须可用,而且 `controller.seekTo()` 必须真的让位置落到目标。**
 *
 * ## 🔴 凭据
 *
 * 同 [PlaybackE2eTest]:服务器地址/账号/密码来自 gitignore 的 `testing.properties`,
 * 断言消息一律经 [TestCredentials.redact],条目标题不出现在任何输出里(只用时长/位置这类数字)。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SeekViaMediaControllerE2eTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

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
    private lateinit var item: MediaItem

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
            val endpoint = Endpoint(url = TestCredentials.baseUrl, label = "e2e-seek", priority = 0)
            val auth = try {
                apiFactory.create(endpoint)
                    .authenticate(AuthRequestDto(TestCredentials.username, TestCredentials.password))
            } catch (e: Exception) {
                throw AssertionError("登录真实服务器失败:${TestCredentials.redact(e.toString())}", e)
            }
            serverStore.upsert(
                Server(
                    id = SERVER_ID,
                    name = "e2e-seek",
                    endpoints = listOf(endpoint),
                    userId = auth.user.id,
                    accessToken = auth.accessToken,
                ),
            )
            serverStore.setActive(SERVER_ID)
            session.invalidate()
            userId = session.userId()
            item = pickLongItem(userId)
        }
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

    /**
     * 🔴 **本文件的核心断言。** 修复前这条必红:`landedAfterMs` 永远是 -1,位置在 20 秒里一步没动。
     */
    @Test
    fun 经MediaController拖动进度条必须真的把位置移到目标() {
        startPlaybackAndWait()
        val controller = requireNotNull(playerConnection.player.value) { "MediaController 没连上会话" }

        assertTrue(
            "拖动进度条走的就是 COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM。转码流不可 seek,ExoPlayer 因此把它" +
                "从可用命令里摘掉了;SeekInterceptingPlayer 必须如实声明自己支持(它自己实现了 seek)," +
                "否则 MediaController 会打一行 warning 然后什么都不做。",
            onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) },
        )
        assertTrue(
            "锁屏/通知栏/蓝牙的快退键走 COMMAND_SEEK_BACK",
            onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_BACK) },
        )
        assertTrue(
            "锁屏/通知栏/蓝牙的快进键走 COMMAND_SEEK_FORWARD",
            onMain { controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) },
        )

        val target = (item.runTimeMs ?: 0L) / 2
        onMain { controller.seekTo(target) }

        val landed = awaitCondition(SEEK_TIMEOUT_MS) { position() in (target - TOLERANCE_MS)..(target + TOLERANCE_MS) }
        assertTrue(
            "controller.seekTo(${target}ms) 之后位置没有落到目标:${SEEK_TIMEOUT_MS / 1000}s 后实际 " +
                "${position()}ms。${diagnostics()}",
            landed,
        )

        val from = position()
        assertTrue(
            "seek 落点之后播放停住了。${diagnostics()}",
            awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_MS },
        )
    }

    /**
     * 缺陷「一加胶囊不显示播放时间」的判据。
     *
     * media3 1.10.1 `MediaSessionLegacyStub.createPlaybackStateCompat`(核对过字节码):
     * ```
     * canReadPosition = isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM) && !isCurrentMediaItemLive()
     * position = canReadPosition ? currentPosition : -1
     * speed    = (isPlaying && canReadPosition) ? speed : 0f
     * ```
     * 修复前真机 `dumpsys media_session` 实测 `position=-1, speed=0.0` —— 系统侧没有任何东西可显示。
     * 这里断言的正是那个判据的两个输入项。胶囊在 ColorOS 上的实际观感只能由用户在自己的设备上验收。
     */
    @Test
    fun 会话层Player必须报非直播且带绝对时长否则系统侧拿不到播放位置() {
        startPlaybackAndWait()

        // 和 PlaybackService.onCreate 里交给 MediaSession.Builder 的是同一种装配。
        val sessionPlayer = onMain {
            SeekInterceptingPlayer(
                exoPlayer,
                SeekRouter { },
                engine.asAbsoluteTimeline { playQueue.current.value?.runTimeMs },
            )
        }

        assertFalse(
            "转码流没有 Content-Length,ExoPlayer 把它当直播窗口;会话层必须纠正,否则 media3 会把 " +
                "PlaybackState 的 position 发成 -1、speed 发成 0.0。",
            onMain { sessionPlayer.isCurrentMediaItemLive },
        )
        assertTrue(
            "总时长必须来自元数据 runTimeMs,底层 duration 是 TIME_UNSET",
            onMain { sessionPlayer.duration } > 0L,
        )
        assertTrue(
            "位置必须是条目内绝对位置",
            onMain { sessionPlayer.currentPosition } > 0L,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun startPlaybackAndWait() {
        playQueue.setQueue(listOf(item), 0)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        runBlocking(Dispatchers.Main) { engine.play(item.id, userId, 0L) }
        assertTrue(
            "播放从未开始,后面的断言无从谈起。${diagnostics()}",
            awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L },
        )
    }

    private suspend fun pickLongItem(userId: String): MediaItem {
        val candidates = mutableListOf<BaseItemDto>()
        candidates += runCatching { api.items(userId = userId, types = "Episode", limit = 50).items }
            .getOrElse { emptyList() }
        candidates += runCatching { api.items(userId = userId, types = "Movie", limit = 50).items }
            .getOrElse { emptyList() }
        val mapped = candidates.mapNotNull { it.toMediaItem() }
        return mapped.firstOrNull { (it.runTimeMs ?: 0L) >= MIN_ITEM_RUNTIME_MS }
            ?: throw AssertionError("服务器上找不到时长 ≥ ${MIN_ITEM_RUNTIME_MS / 60_000} 分钟的可播条目。")
    }

    private fun position(): Long = onMain { engine.absolutePositionMs }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    /** 失败现场。全部经 [TestCredentials.redact] —— 流 URL 里带 token。 */
    private fun diagnostics(): String = onMain {
        buildString {
            append("\n--- 现场 ---")
            append("\n  absolutePositionMs = ").append(engine.absolutePositionMs)
            append("\n  engineState        = ").append(TestCredentials.redact(engine.state.value.toString()))
            append("\n  exo.playbackState  = ").append(exoPlayer.playbackState)
            append("\n  exo.playerError    = ").append(TestCredentials.redact(exoPlayer.playerError?.toString()))
            append("\n  controller         = ").append(playerConnection.player.value?.let { "connected" } ?: "null")
            append("\n------------")
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "runOnMainSync 没有执行 block" }.getOrThrow()
    }

    private companion object {
        const val SERVER_ID = "e2e-seek-server"
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val SEEK_TIMEOUT_MS = 30_000L

        /** seek 落点容差:目标之前给一点(重新起转码的对齐),之后给多一点(落点后还在播)。 */
        const val TOLERANCE_MS = 30_000L

        const val ADVANCE_WINDOW_MS = 15_000L
        const val MIN_ADVANCE_MS = 3_000L

        /** 需要够长的条目:seek 到一半才有判别力。 */
        const val MIN_ITEM_RUNTIME_MS = 900_000L

        const val POLL_INTERVAL_MS = 250L
    }
}
