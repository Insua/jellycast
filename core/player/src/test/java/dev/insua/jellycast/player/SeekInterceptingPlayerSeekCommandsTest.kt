package dev.insua.jellycast.player

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * # 缺陷「拖动进度条不好使」的复现与回归(设计文档 §4 第一项)
 *
 * ## 真机取证(模拟器 + 真实服务器,`SeekBufferDiagnosticsTest`)
 *
 * ```
 * FLAGS exo.seekable=false exo.live=true exo.duration=TIME_UNSET
 *       exo.cmdSeekInItem=false ctl.cmdSeekInItem=false
 *       ctl.cmdSeekBack=false  ctl.cmdSeekFwd=false
 * W MCImplBase: Controller isn't allowed to call command= 5
 * SEEK landedAfterMs=-1     ← 20 秒内位置一步没动
 * ```
 *
 * ## 机理
 *
 * Jellyfin 的转码流 `Accept-Ranges: none`、没有 `Content-Length`,ExoPlayer 因此认定当前条目
 * **不可 seek、时长未知**,于是从 `getAvailableCommands()` 里**摘掉**
 * `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM`(5)/`COMMAND_SEEK_BACK`(11)/`COMMAND_SEEK_FORWARD`(12),
 * 并且把当前窗口标成 **live**。
 *
 * 而 App 里所有的 seek 都走 `MediaController`(播放页拖进度条、±15s/±30s 按钮、锁屏、通知栏、蓝牙):
 * `MediaController.seekTo()` 在发命令**之前**先查命令是否可用,不可用就打一行 warning **直接返回** ——
 * 请求根本到不了 [SeekInterceptingPlayer.seekTo],那一层精心设计的"重新 resolve"从未被触发过。
 * 也就是说进度条不是"不跟手",而是**完全没有反应**。
 *
 * ## 修法
 *
 * [SeekInterceptingPlayer] **自己实现了 seek**(委派给 [SeekRouter] → 重新 resolve 一条带
 * `startTimeTicks` 的新流),所以它必须**如实声明自己支持这些命令**,而不是把底层那条"不可 seek 的
 * 字节流"的能力原样转发出去。这不是绕过 Media3 的检查,而是纠正一份错误的能力声明:
 * 底层流不支持字节级 seek 是事实,而**这个 Player 支持 seek** 同样是事实。
 *
 * 同理 [SeekInterceptingPlayer.isCurrentMediaItemLive]:JellyCast 只播点播条目,元数据里
 * 有权威总时长时它就**不是**直播流。这个判定同时决定了 MediaSession 发布的 `PlaybackState`
 * 里有没有位置(见 [SeekInterceptingPlayerLivenessTest])。
 */
@Config(sdk = [35])
@ExtendWith(RobolectricExtension::class)
class SeekInterceptingPlayerSeekCommandsTest {

    /**
     * 严格复刻真机取证到的底层能力集合:ExoPlayer 在这条不可 seek、时长未知的转码流上汇报的命令里
     * **没有** 5 / 11 / 12。
     */
    private fun unseekableUnderlyingPlayer(): Player {
        val baseline = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
        return mockk<Player>(relaxed = true).also { every { it.availableCommands } returns baseline }
    }

    private fun sessionPlayer(underlying: Player) =
        SeekInterceptingPlayer(underlying, SeekRouter { }, absoluteTimeline = timelineWithDuration(1_800_000L))

    private fun timelineWithDuration(durationMs: Long?) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = 0L
        override fun absoluteDurationMs(): Long? = durationMs
    }

    @Test fun `底层流不可seek时,仍然声明支持条目内seek —— 否则MediaController会直接吞掉拖动`() {
        val commands = sessionPlayer(unseekableUnderlyingPlayer()).availableCommands

        assertTrue(
            commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            "拖动进度条走的就是这条命令;不声明它,MediaController 会打一行 " +
                "\"Controller isn't allowed to call command= 5\" 然后什么都不做。",
        )
    }

    @Test fun `底层流不可seek时,仍然声明支持快退快进 —— 锁屏和蓝牙按键走的是这两条`() {
        val commands = sessionPlayer(unseekableUnderlyingPlayer()).availableCommands

        assertTrue(commands.contains(Player.COMMAND_SEEK_BACK), "seekBack() 本类已覆写并委派给 SeekRouter")
        assertTrue(commands.contains(Player.COMMAND_SEEK_FORWARD), "seekForward() 本类已覆写并委派给 SeekRouter")
    }

    @Test fun `声明seek能力不影响既有的上一集下一集判定`() {
        val navigator = object : QueueNavigator {
            override fun hasNext(): Boolean = false
            override fun hasPrevious(): Boolean = false
            override fun next() {}
            override fun previous() {}
        }
        val commands = SeekInterceptingPlayer(
            unseekableUnderlyingPlayer(),
            SeekRouter { },
            timelineWithDuration(1_800_000L),
            navigator,
        ).availableCommands

        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }
}
