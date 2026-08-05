package dev.insua.jellycast.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C1 复审(Critical):裸 ADTS AAC(L1/缓存文件真实的 `Content-Type`,见
 * `docs/superpowers/specs/2026-07-25-spike-results.md:44`)喂给 [createAudioOnlyPlayer] 产出的
 * 播放器,必须真的可以 seek——这件事在这个测试存在之前从没有任何一条测试给过 ExoPlayer 一个
 * 真实的缓存文件,`isCurrentMediaItemSeekable` 因此从没被断言过,回归也就一直不可见。
 *
 * 这是一个纯粹的**平台行为**测试(v4 铁律:JVM 单测结构上测不出)——真实 [ExoPlayer] +
 * 真实 `MediaCodec`/`AdtsExtractor`,不能用 Robolectric 或任何假实现替代。
 *
 * 用 ffmpeg 在开发机上预先生成的一份 3 秒静音正弦波、64kbps AAC-LC、裸 ADTS(无容器)封装的
 * 极小文件(`assets/bare-adts-fixture.aac`,约 25 KB)——形状和真实缓存文件完全一致(同样的
 * `AdtsExtractor` 解析路径),不需要连真实 Jellyfin 服务器就能钉死这个回归。
 */
@RunWith(AndroidJUnit4::class)
class LocalAdtsSeekableDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var fixtureFile: File
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        fixtureFile = File(context.cacheDir, "bare-adts-fixture-under-test.aac")
        instrumentation.context.assets.open(FIXTURE_ASSET_NAME).use { input ->
            fixtureFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @After
    fun tearDown() {
        runCatching { onMain { player?.release() } }
        runCatching { fixtureFile.delete() }
    }

    /**
     * 核心回归断言:[createAudioOnlyPlayer] 产出的播放器喂一份真实裸 ADTS 文件之后,
     * `isCurrentMediaItemSeekable` 必须是 `true`。
     *
     * 变异验证(任务报告里有记录实际输出):把 [audioOnlyMediaSourceFactory] 的接线去掉
     * (即 [createAudioOnlyPlayer] 退回 media3 默认的 media-source 工厂),这条用例会失败——
     * `isCurrentMediaItemSeekable` 实测为 `false`。
     */
    @Test
    fun `本地裸ADTS文件在音频专用播放器上真的可以seek`() {
        val exoPlayer = onMain { createAudioOnlyPlayer(context) }
        player = exoPlayer

        val readyLatch = CountDownLatch(1)
        onMain {
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) readyLatch.countDown()
                    if (playbackState == Player.STATE_IDLE && exoPlayer.playerError != null) {
                        readyLatch.countDown()
                    }
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixtureFile)))
            exoPlayer.prepare()
        }

        val reachedTerminalState = readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("${READY_TIMEOUT_SECONDS}s 内没有进入 STATE_READY(也没有报错)", reachedTerminalState)

        val error = onMain { exoPlayer.playerError }
        if (error != null) fail("播放器准备阶段报错,测试前提不成立:$error")

        val seekable = onMain { exoPlayer.isCurrentMediaItemSeekable }
        assertTrue(
            "裸 ADTS 缓存文件应该可以 seek——见 audioOnlyMediaSourceFactory 的 KDoc " +
                "(constantBitrateSeekingEnabled 未开启时 AdtsExtractor 只给 SeekMap.Unseekable)",
            seekable,
        )

        // 顺带验证 seek 本身真的落地(不是"isCurrentMediaItemSeekable=true 但 seekTo 无效"这种半吊子状态)。
        onMain { exoPlayer.seekTo(SEEK_TARGET_MS) }
        val landed = awaitCondition(SEEK_TIMEOUT_MS) {
            val pos = onMain { exoPlayer.currentPosition }
            pos in (SEEK_TARGET_MS - SEEK_TOLERANCE_MS)..(SEEK_TARGET_MS + SEEK_TOLERANCE_MS)
        }
        assertTrue(
            "seekTo(${SEEK_TARGET_MS}ms) 之后位置没有落到目标附近,实际 " +
                "${onMain { exoPlayer.currentPosition }}ms",
            landed,
        )
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result) { "runOnMainSync 没有执行 block" }.getOrThrow()
    }

    private companion object {
        const val FIXTURE_ASSET_NAME = "bare-adts-fixture.aac"
        const val READY_TIMEOUT_SECONDS = 15L
        const val SEEK_TARGET_MS = 1_000L
        const val SEEK_TOLERANCE_MS = 500L
        const val SEEK_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
