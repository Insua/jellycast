package dev.insua.jellycast.player

import io.mockk.every
import io.mockk.mockk
import androidx.media3.common.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * # 缺陷「一加流体云胶囊不显示播放时间」的复现与回归(设计文档 §4 第二项)
 *
 * ## 真机取证(`adb shell dumpsys media_session`,播放进行中)
 *
 * ```
 * state=PlaybackState {state=PLAYING(3), position=-1, buffered position=-1, speed=0.0, ...}
 * ```
 *
 * 位置 `-1`(`PLAYBACK_POSITION_UNKNOWN`)、速度 `0.0`。系统侧既拿不到当前位置,也没有速度可以外推,
 * 于是流体云/锁屏/车机全都无法显示"已听多久 / 共多长"。总时长本身是好的(经
 * [SeekInterceptingPlayer.getDuration] 报的元数据 `runTimeMs`),缺的正是**位置和速度**。
 *
 * ## 机理(核对 media3 1.10.1 `MediaSessionLegacyStub.createPlaybackStateCompat` 字节码)
 *
 * ```
 * canReadPosition = isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM) && !isCurrentMediaItemLive()
 * position = canReadPosition ? player.currentPosition : -1
 * speed    = (isPlaying && canReadPosition) ? playbackParameters.speed : 0f
 * actions &= canReadPosition ? ~0 : ~ACTION_SEEK_TO
 * ```
 *
 * 真机实测 `exo.live=true` —— Jellyfin 转码流没有 `Content-Length`、`Accept-Ranges: none`,
 * ExoPlayer 因此把当前窗口当成**直播**。于是 `canReadPosition` 恒为 false,位置和速度双双被抹掉。
 *
 * ## 修法
 *
 * JellyCast 只播点播条目。元数据里有权威总时长([AbsoluteTimeline.absoluteDurationMs])时,
 * 这条流**就不是**直播——如实汇报 `isCurrentMediaItemLive() = false`。拿不到总时长时不撒谎,
 * 退回底层判定(此时进度条本来也是禁用的,见 `PlayerScreen.ProgressSection`)。
 */
class SeekInterceptingPlayerLivenessTest {

    private fun underlying(live: Boolean, seekable: Boolean): Player =
        mockk<Player>(relaxed = true).also {
            every { it.isCurrentMediaItemLive } returns live
            every { it.isCurrentMediaItemSeekable } returns seekable
        }

    private fun timeline(durationMs: Long?) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = 0L
        override fun absoluteDurationMs(): Long? = durationMs
    }

    @Test fun `底层把转码流判成直播时,会话层必须纠正为非直播 —— 否则PlaybackState里位置和速度都被抹成未知`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(live = true, seekable = false),
            SeekRouter { },
            timeline(1_800_000L),
        )

        assertFalse(
            sessionPlayer.isCurrentMediaItemLive,
            "元数据给了权威总时长,这就是一条点播流;报成直播会让 media3 把 PlaybackState 的 " +
                "position 发成 -1、speed 发成 0.0,胶囊/锁屏因此没有任何时间可显示。",
        )
    }

    @Test fun `底层把转码流判成不可seek时,会话层同样纠正 —— 本类自己实现了seek`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(live = true, seekable = false),
            SeekRouter { },
            timeline(1_800_000L),
        )

        assertTrue(
            sessionPlayer.isCurrentMediaItemSeekable,
            "声明了 COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM 就必须在这里保持自洽",
        )
    }

    @Test fun `拿不到权威总时长时不撒谎,退回底层判定`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(live = true, seekable = false),
            SeekRouter { },
            timeline(null),
        )

        assertTrue(sessionPlayer.isCurrentMediaItemLive, "不知道总时长就没有依据说它不是直播")
        assertFalse(sessionPlayer.isCurrentMediaItemSeekable)
    }
}

/**
 * 位置改报"条目内绝对位置"之后,**缓冲位置也必须跟着换算**,否则系统侧拿到的是一对矛盾的数字。
 *
 * 真机实测(修好 [SeekInterceptingPlayer.isCurrentMediaItemLive] 之后):
 * ```
 * state=PlaybackState {state=PLAYING(3), position=913290, buffered position=5077, speed=1.0}
 * ```
 * 缓冲位置(5 秒)远小于播放位置(15 分钟) —— 因为 `getBufferedPosition()` 报的是**这条转码流内**
 * 的相对值,而 seek/续播每换一条流就归零(和 `currentPosition` 是同一个坑,见
 * [AudioPlaybackEngine.absolutePositionMs])。锁屏/胶囊的缓冲条据此渲染会看起来"缓冲一直是 0"。
 *
 * 换算方式:绝对位置 + (流内缓冲位置 − 流内播放位置),即"从当前位置往前还缓冲了多久"。
 */
class SeekInterceptingPlayerBufferedPositionTest {

    private fun underlying(streamPositionMs: Long, streamBufferedMs: Long): Player =
        mockk<Player>(relaxed = true).also {
            every { it.currentPosition } returns streamPositionMs
            every { it.bufferedPosition } returns streamBufferedMs
            every { it.contentBufferedPosition } returns streamBufferedMs
        }

    private fun timeline(positionMs: Long?, durationMs: Long?) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long? = positionMs
        override fun absoluteDurationMs(): Long? = durationMs
    }

    @Test fun `缓冲位置必须换算成条目内绝对时间轴上的位置`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(streamPositionMs = 5_000L, streamBufferedMs = 65_000L),
            SeekRouter { },
            timeline(positionMs = 913_290L, durationMs = 1_826_581L),
        )

        // 流内已经缓冲到第 65 秒、播到第 5 秒 —— 也就是"往前还有 60 秒"。
        assertEquals(913_290L + 60_000L, sessionPlayer.bufferedPosition)
        assertEquals(913_290L + 60_000L, sessionPlayer.contentBufferedPosition)
    }

    @Test fun `缓冲位置不得越过条目总时长`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(streamPositionMs = 0L, streamBufferedMs = 240_000L),
            SeekRouter { },
            timeline(positionMs = 1_800_000L, durationMs = 1_826_581L),
        )

        assertEquals(1_826_581L, sessionPlayer.bufferedPosition)
    }

    @Test fun `拿不到绝对位置时退回底层的流内缓冲位置`() {
        val sessionPlayer = SeekInterceptingPlayer(
            underlying(streamPositionMs = 5_000L, streamBufferedMs = 65_000L),
            SeekRouter { },
            timeline(positionMs = null, durationMs = null),
        )

        assertEquals(65_000L, sessionPlayer.bufferedPosition)
    }
}
