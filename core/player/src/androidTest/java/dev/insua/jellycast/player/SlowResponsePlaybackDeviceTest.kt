package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R2 回归(设计文档 §3.2)。
 *
 * media3 1.10.1 的 `DefaultHttpDataSource` 默认 connect/read 各 **8 秒**。而 Jellyfin 为 4K `.ts`
 * 片源起 L1 转码,实测 6.3–46.1 秒才吐响应头 —— 播放器因此在探测**已经**判定可以走 L1 之后
 * 仍然 `Source error`,一秒都没播出来。
 *
 * 这是纯平台行为(v4 铁律:JVM 结构上测不出),需要真实 [ExoPlayer] + 真实网络栈。
 *
 * 用真实的裸 ADTS 字节做响应体(和 `LocalAdtsSeekableDeviceTest` 同一份 fixture),
 * 让 `AdtsExtractor` 走的是和生产完全一样的解析路径。
 */
@RunWith(AndroidJUnit4::class)
class SlowResponsePlaybackDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        val adts = instrumentation.context.assets.open(FIXTURE_ASSET_NAME).use { it.readBytes() }
        server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody(Buffer().write(adts))
                    .setHeadersDelay(HEADERS_DELAY_SECONDS, TimeUnit.SECONDS),
            )
            start()
        }
    }

    @After
    fun tearDown() {
        runCatching { onMain { player?.release() } }
        runCatching { server.shutdown() }
    }

    /**
     * 响应头延迟 12 秒(> media3 默认 8 秒,< 生产值 60 秒),播放器必须仍能把流打开并进入
     * `STATE_READY`,而不是抛 `Source error`。
     *
     * 变异验证:把 [createAudioOnlyPlayer] 里 `setMediaSourceFactory` 的
     * [audioOnlyDataSourceFactory] 接线去掉(退回 media3 默认数据源),这条必须失败。
     */
    @Test
    fun `响应头延迟12秒时音频专用播放器仍能打开流`() {
        val exoPlayer = onMain { createAudioOnlyPlayer(context) }
        player = exoPlayer

        val readyLatch = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>(null)
        onMain {
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) readyLatch.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    readyLatch.countDown()
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(server.url("/Audio/x/universal").toString()))
            exoPlayer.prepare()
        }

        val settled = readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertNull("播放器在慢响应头上报错了:${failure.get()?.errorCodeName}", failure.get())
        assertTrue("等了 ${READY_TIMEOUT_SECONDS}s 播放器仍未进入 STATE_READY", settled)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private companion object {
        /** 大于 media3 默认的 8 秒,小于生产值 60 秒 —— 只有修好了才过得去。 */
        const val HEADERS_DELAY_SECONDS = 12L
        const val READY_TIMEOUT_SECONDS = 40L

        /** 与 `LocalAdtsSeekableDeviceTest` 共用同一份 fixture。 */
        const val FIXTURE_ASSET_NAME = "bare-adts-fixture.aac"
    }
}
