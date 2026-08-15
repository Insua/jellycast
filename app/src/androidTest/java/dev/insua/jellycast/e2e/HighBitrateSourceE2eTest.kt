package dev.insua.jellycast.e2e

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import dev.insua.jellycast.player.PlayQueue
import dev.insua.jellycast.player.PlaybackService
import dev.insua.jellycast.player.PlaybackSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * # 端到端 —— 高码率 MPEG-TS 片源真的走 L1 且出声
 *
 * ## 为什么必须存在
 *
 * 本分支修了三处:L1 探测的读超时从 okhttp 默认的 10s 抬到 60s(4K `.ts` 片源服务端要
 * 6–46s 才吐得出响应头,原先的 10s 会被误判成"这个源不是纯音频"而错误降级到 L3)、
 * L3 兜底 URL 加上视频码率上限(不再是 54Mbps 的firehose)、以及 ExoPlayer 自身 HTTP
 * 数据源超时的抬高。**这三处改动此前都没有在真实服务器上验证过**——本文件就是那次验证。
 *
 * ## 挑片源:只按容器,不写死条目 id
 *
 * 严格复刻 `PlaybackE2eTest.pickPlayableItems` 的做法:遍历服务器上的 `Episode` 条目,
 * 逐个调 `playbackInfo` 找第一个 `MediaSources.first().Container` 含 "ts" 的。playbackInfo
 * 只是一次元数据往返,不会像 `/Audio/{id}/universal` 那样在服务端起真实转码任务,
 * 所以这里的顺序遍历不消耗 NAS 的转码资源。凑不出符合条件的条目时测试直接失败
 * (`AssertionError`),不是环境没配好,是"这台服务器上没有能验证这条回归的素材"这件
 * 需要人看一眼的事实。
 *
 * ## 断言纪律:断行为,不断"没抛异常"
 *
 * 和 [PlaybackE2eTest] 同一条纪律——`play()` 正常返回什么也证明不了,用户的抱怨是
 * "没有声音"。真正的判别力在两处:
 * 1. `PlaybackSourceResolver.resolve()` 直接返回的 `level` 必须是 `SERVER_AUDIO_ONLY`——
 *    这就是 R1 的回归点,修复前这里会是 `CLIENT_VIDEO_DISABLED`。
 * 2. 走生产路径 `engine.play()` 之后,`absolutePositionMs` 必须**真的前进**,不是停在原地。
 *
 * ## ⚠️ 不验证分辨率/码率——`MediaStreamDto` 拿不到这两样
 *
 * 类名叫「高码率 MPEGTS」,但这条用例**不验证挑中的片源真的是高码率**,也不验证分辨率:
 * `dev.insua.jellycast.network.dto.MediaStreamDto` 没有 `Width`/`Height`/码率这几个字段(只有
 * `Type`/`Index`/`Codec`/`Language`/`DisplayTitle`/`IsTextSubtitleStream`/`IsExternal`)——不是
 * 疏漏,是 Jellyfin `PlaybackInfo` 响应本身这几个字段就没往 DTO 里映射。日志里能报的只有容器
 * (`mediaSourceContainer`,如 `"mpegts"`)和视频编码(`videoCodec`,如 `"hevc"`),这两个不足以
 * 区分"高码率 4K HEVC"和"随便一个 mpegts 封装的低码率 480p"——低码率的 `.ts` 一样会让这条用例
 * 通过。名字里的「高码率」描述的是这条回归**本来针对**的那类片源(见上面「为什么必须存在」),
 * 不是这条用例**实际验证过**的属性。
 *
 * ## ⚠️ 这条用例目前是冒烟,不是回归护栏——如实记录
 *
 * 按简报 Step 3 做过变异验证:把 [dev.insua.jellycast.player.HttpStreamProbe] 的超时派生临时改回
 * `private val client: OkHttpClient = client`(即恢复 okhttp 默认的 10s 读超时),重跑本用例。
 * **结果仍然是绿的**——`resolve()` 回的 `level` 依旧是 `SERVER_AUDIO_ONLY`。日志显示这台服务器上
 * 探测请求在 10s 超时线内就拿到了响应头,所以本用例目前**问不出**"读超时抬高之后 vs 抬高之前"
 * 的差别——它验证的是"L1 判定 + 播放路径在真实服务器上确实能跑通",而不是"L1 探测超时回归"
 * 本身。真正会暴露 R1(读超时不足导致误降级到 L3)的场景需要一次服务端响应头就是慢
 * (6–46s)的探测——这台测试服务器上跑到的这一集没有慢到那个程度。
 *
 * 不要把这条用例的绿当成"超时回归挡住了"的证据;它证明的是"高码率 MPEG-TS 片源在当前这台
 * 服务器的当前状态下能正确走 L1 并出声",仅此而已。
 *
 * ## 🔴 凭据
 *
 * 服务器地址/账号/密码来自 [TestCredentials]。断言消息、日志一律不带 URL/token——
 * 需要提到片源时只报容器/编码这类不识别具体内容的信息。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HighBitrateSourceE2eTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    // ⚠️ 全部是**生产**实现,没有一个 fake —— 与 PlaybackE2eTest 同一套装配。
    @Inject lateinit var serverStore: ServerStore
    @Inject lateinit var apiFactory: JellyfinApiFactory
    @Inject lateinit var api: JellyfinApi
    @Inject lateinit var session: JellyfinSession
    @Inject lateinit var engine: AudioPlaybackEngine
    @Inject lateinit var exoPlayer: ExoPlayer
    @Inject lateinit var playQueue: PlayQueue
    @Inject lateinit var playerConnection: MediaControllerPlayerConnection

    /**
     * 生产的单例 resolver(见 `PlayerModule.providePlaybackSourceResolver`)——内部的
     * [dev.insua.jellycast.player.HttpStreamProbe] 正是本分支改过超时的那个实例。直接注入
     * 它而不是自己再拼一个,保证「变异验证」改的是同一份被 `engine.play()` 实际使用的代码。
     */
    @Inject lateinit var resolver: PlaybackSourceResolver

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var userId: String
    private lateinit var item: MediaItem

    /** 挑中片源的容器串(如 `"mpegts"`),仅用于日志/断言消息——不带条目 id。 */
    private lateinit var mediaSourceContainer: String

    /** 挑中片源的视频编码(如 `"hevc"`),取不到时为 null。 */
    private var videoCodec: String? = null

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
            val picked = pickMpegTsEpisode(userId)
            item = picked.item
            mediaSourceContainer = picked.container
            videoCodec = picked.videoCodec
        }

        playerConnection.ensureConnected()
    }

    @After
    fun tearDown() {
        if (!TestCredentials.isConfigured) return
        // 释放播放器 + 停掉前台服务,避免在 NAS 上留下孤儿转码任务。
        runCatching {
            onMain {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                engine.reset()
            }
        }
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    @Test
    fun `高码率MPEGTS片源解析为L1且真的出声`() {
        val source = runBlocking { resolver.resolve(item.id, userId, 0L) }
        Log.i(
            TAG,
            "resolve() 结果:level=${source.level},容器=$mediaSourceContainer,视频编码=${videoCodec ?: "未知"}",
        )
        assertTrue(
            "MPEG-TS 片源没有走 L1:level=${source.level}(容器=$mediaSourceContainer," +
                "视频编码=${videoCodec ?: "未知"})。注意:这条用例是冒烟测试,不是 R1(L1 探测读超时" +
                "不足导致误降级到 L3)的回归护栏——见类 KDoc 里的变异验证记录,这台服务器上探测" +
                "响应目前落在 okhttp 默认 10s 超时线以内,问不出'读超时抬高前后'的差别。真正守 R1 的" +
                "是 core/player 的 HttpStreamProbeTimeoutTest。这条断言只说明:今天这个源没能走上 L1。",
            source.level == AudioDeliveryLevel.SERVER_AUDIO_ONLY,
        )

        val playInvokedAt = SystemClock.elapsedRealtime()
        startPlayback(item, startPositionMs = 0L)

        val started = awaitCondition(STARTUP_TIMEOUT_MS) { position() > 0L }
        assertTrue(
            "播放从未开始:${STARTUP_TIMEOUT_MS / 1000}s 内 absolutePositionMs 一直是 0" +
                "(容器=$mediaSourceContainer,视频编码=${videoCodec ?: "未知"})。${diagnostics()}",
            started,
        )
        val firstSoundElapsedMs = SystemClock.elapsedRealtime() - playInvokedAt
        Log.i(
            TAG,
            "高码率 MPEG-TS 片源:play() 到首次观测到位置前进耗时 ${firstSoundElapsedMs}ms" +
                "(容器=$mediaSourceContainer,视频编码=${videoCodec ?: "未知"})",
        )

        val from = position()
        val advanced = awaitCondition(ADVANCE_WINDOW_MS) { position() - from >= MIN_ADVANCE_MS }
        assertTrue(
            "播放开始后停住了:${ADVANCE_WINDOW_MS / 1000}s 内只前进了 ${position() - from}ms," +
                "要求 ≥ ${MIN_ADVANCE_MS}ms。${diagnostics()}",
            advanced,
        )
    }

    // ---------------------------------------------------------------- 会话预置

    /** 严格复刻 `PlaybackE2eTest.seedSession`:绕开登录表单,直接用真实 API 登录拿 token。 */
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

    private data class PickedEpisode(val item: MediaItem, val container: String, val videoCodec: String?)

    /**
     * 动态挑一个 MPEG-TS 容器的剧集。**不写死任何条目 id / 剧名**——见类 KDoc。
     *
     * 挑不到就让测试失败,不是跳过:见类 KDoc「挑片源」一节。
     *
     * `limit` 起初照抄 `PlaybackE2eTest.pickPlayableItems` 的 50——`PlaybackInfo` 虽然是纯元数据
     * 往返、不起转码,但在 J4125 上仍然是真实的 HTTP 请求量,`setUp()` 没有理由比既有先例多扫
     * 4 倍。**实测证明 50 不够**:这台服务器按 `SortName` 排到第一个 MPEG-TS 容器的剧集是第
     * 56 个,`limit=50` 会在扫完全部 50 个候选后一无所获、测试直接失败(真实复现过,见
     * task-4-report.md 的「Fix round 1」一节)。改成 [CANDIDATE_SCAN_LIMIT] = 100——刚好在
     * 实测命中位置(56)之上留出安全边际,而不是像最初那样直接搬 200。走到匹配为止实际扫了
     * 多少个候选,打进日志(不含条目 id)。
     */
    private suspend fun pickMpegTsEpisode(userId: String): PickedEpisode {
        val episodes = runCatching { api.items(userId = userId, types = "Episode", limit = CANDIDATE_SCAN_LIMIT).items }
            .getOrElse { emptyList() }
            .mapNotNull { it.toMediaItem() }
        if (episodes.isEmpty()) {
            throw AssertionError("服务器上找不到任何 Episode 条目,无法验证高码率片源回归。")
        }
        episodes.forEachIndexed { index, candidate ->
            val info = runCatching { api.playbackInfo(candidate.id, userId) }.getOrNull() ?: return@forEachIndexed
            val ms = info.mediaSources.firstOrNull() ?: return@forEachIndexed
            val container = ms.container
            if (container?.contains("ts", ignoreCase = true) == true) {
                val codec = ms.mediaStreams.firstOrNull { it.type == "Video" }?.codec
                Log.i(TAG, "挑片源:扫了 ${index + 1}/${episodes.size} 个候选(limit=$CANDIDATE_SCAN_LIMIT)命中 MPEG-TS 容器")
                return PickedEpisode(candidate, container, codec)
            }
        }
        throw AssertionError(
            "服务器上找不到 MPEG-TS 容器的剧集,无法验证高码率片源回归" +
                "(扫了 ${episodes.size} 个候选,limit=$CANDIDATE_SCAN_LIMIT)。",
        )
    }

    /**
     * 严格复刻 `PlaybackE2eTest.startPlayback`:队列、前台服务、引擎,同一条生产调用序列。
     */
    private fun startPlayback(item: MediaItem, startPositionMs: Long) {
        playQueue.setQueue(listOf(item), 0)
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        runBlocking(Dispatchers.Main) { engine.play(item.id, userId, startPositionMs) }
    }

    // ---------------------------------------------------------------- 工具(与 PlaybackE2eTest 同款)

    private fun position(): Long = onMain { engine.absolutePositionMs }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

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

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "runOnMainSync 没有执行 block" }.getOrThrow()
    }

    private companion object {
        const val TAG = "HighBitrateSourceE2e"
        const val E2E_SERVER_ID = "e2e-server"

        /**
         * 候选扫描上限。**和 `PlaybackE2eTest.pickPlayableItems` 的 `limit=50` 不一致,故意的**——
         * 起初照抄了那个 50,实测这台服务器按 `SortName` 排到的第一个 MPEG-TS 容器剧集是第 56 个,
         * 50 扫不到、测试直接失败,改成了 100。别为了"看齐"把这个值改回 50。详见
         * [pickMpegTsEpisode] 的 KDoc。
         */
        const val CANDIDATE_SCAN_LIMIT = 100

        /** 拿到第一条流并出声的宽限。4K `.ts` 片源实测响应头要 6–46s,留足余量。 */
        const val STARTUP_TIMEOUT_MS = 90_000L

        const val ADVANCE_WINDOW_MS = 15_000L

        /** 简报要求:至少推进 3000ms。 */
        const val MIN_ADVANCE_MS = 3_000L

        const val POLL_INTERVAL_MS = 250L
    }
}
