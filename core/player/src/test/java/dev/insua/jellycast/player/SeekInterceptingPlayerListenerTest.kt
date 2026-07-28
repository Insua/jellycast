package dev.insua.jellycast.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * # 缺陷「拖动进度条也不好使」的第二层根因
 *
 * 光让 [SeekInterceptingPlayer.getAvailableCommands] 声明 seek 命令**还不够** —— 真机复测后
 * `MediaController` 依然打 `Controller isn't allowed to call command= 5`。
 *
 * 核对 media3 1.10.1 字节码,`MediaController` 手里的可用命令是**推**过去的,不是现查的:
 * ```
 * MediaSessionImpl$PlayerListener.onAvailableCommandsChanged(Commands c)
 *     → MediaSessionImpl.dispatchOnAvailableCommandsChangedFromPlayer(c)   ← 用的是回调参数 c
 * MediaControllerImplBase.onAvailableCommandsChangedFromPlayer(c)
 *     → intersectedPlayerCommands = intersect(playerCommandsFromSession, c)
 * ```
 * 而 `ForwardingPlayer$ForwardingListener.onAvailableCommandsChanged(c)` 把**底层播放器的原始
 * 命令集合原样**传给上层监听器 —— 它只替换事件里的 `Player` 引用,不替换这个参数。于是
 * `MediaSession` 推给 `MediaController` 的是 ExoPlayer 那份**摘掉了 seek 命令**的集合,
 * [SeekInterceptingPlayer.getAvailableCommands] 的覆写在这条路上被整个绕过。
 *
 * 所以本类还必须在监听器这一层把命令集合纠正过来:注册进来的每个监听器都包一层,
 * `onAvailableCommandsChanged` 改报本类自己的集合,其余回调**原样透传**(用 Kotlin 接口委派,
 * 漏掉任何一个回调都会让播放状态/错误/切歌事件在会话层凭空消失,所以下面有专门的透传测试)。
 */
@Config(sdk = [35])
@ExtendWith(RobolectricExtension::class)
class SeekInterceptingPlayerListenerTest {

    private fun rawCommandsWithoutSeek(): Player.Commands =
        Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .build()

    private fun timeline(durationMs: Long?) = object : AbsoluteTimeline {
        override fun absolutePositionMs(): Long = 0L
        override fun absoluteDurationMs(): Long? = durationMs
    }

    @Test fun `底层汇报的命令集合缺少seek时,上层监听器收到的必须是本类纠正过的集合`() {
        val underlying = mockk<Player>(relaxed = true)
        val captured = slot<Player.Listener>()
        every { underlying.addListener(capture(captured)) } returns Unit
        every { underlying.availableCommands } returns rawCommandsWithoutSeek()

        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, timeline(1_800_000L))
        var reported: Player.Commands? = null
        sessionPlayer.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                reported = availableCommands
            }
        })

        captured.captured.onAvailableCommandsChanged(rawCommandsWithoutSeek())

        val commands = requireNotNull(reported) { "监听器根本没收到 onAvailableCommandsChanged" }
        assertTrue(
            commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            "MediaSession 推给 MediaController 的就是这个集合;它缺 5 号命令,拖动进度条就永远没反应",
        )
        assertTrue(commands.contains(Player.COMMAND_SEEK_BACK))
        assertTrue(commands.contains(Player.COMMAND_SEEK_FORWARD))
    }

    @Test fun `包一层之后,其余回调必须原样透传 —— 漏一个都会让会话层丢事件`() {
        val underlying = mockk<Player>(relaxed = true)
        val captured = slot<Player.Listener>()
        every { underlying.addListener(capture(captured)) } returns Unit
        every { underlying.availableCommands } returns rawCommandsWithoutSeek()

        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, timeline(1_800_000L))
        val seen = mutableListOf<String>()
        sessionPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { seen += "state=$playbackState" }
            override fun onIsPlayingChanged(isPlaying: Boolean) { seen += "playing=$isPlaying" }
            override fun onPlayerError(error: PlaybackException) { seen += "error" }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { seen += "pwr=$playWhenReady" }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) { seen += "discontinuity" }
        })

        val l = captured.captured
        l.onPlaybackStateChanged(Player.STATE_ENDED)
        l.onIsPlayingChanged(true)
        l.onPlayerError(PlaybackException("x", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        l.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        assertEquals(listOf("state=4", "playing=true", "error", "pwr=true"), seen)
    }

    /**
     * 🔴 **升级守卫。** 包装器是手写 37 个覆写(不能用 Kotlin 接口委派:`Player.Listener` 全是 Java
     * default 方法,委派只为抽象成员生成转发,实测一个回调都不转发)。手写就有"media3 升级新增了
     * 回调却忘了透传"的风险,而那种漏掉是**静默**的 —— 事件在会话层凭空消失,没有任何报错。
     *
     * 这里遍历 `Player.Listener` 声明的每一个方法,要求包装器类都真的覆写过。升级 media3 之后
     * 只要新增了回调,这条测试立刻变红。
     */
    @Test fun `包装器必须覆写Player_Listener的每一个回调 —— media3升级新增回调时这条会红`() {
        val wrapperClass = Class.forName(
            "dev.insua.jellycast.player.SeekInterceptingPlayer\$CommandCorrectingListener",
        )
        val overridden = wrapperClass.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()

        val missing = Player.Listener::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name to it.parameterTypes.toList() }
            .filterNot { it in overridden }

        assertTrue(
            missing.isEmpty(),
            "CommandCorrectingListener 没有透传这些回调,它们会在会话层被静默吞掉:" +
                missing.joinToString { it.first },
        )
    }

    @Test fun `移除监听器之后不再收到事件`() {
        val underlying = mockk<Player>(relaxed = true)
        val added = slot<Player.Listener>()
        val removed = slot<Player.Listener>()
        every { underlying.addListener(capture(added)) } returns Unit
        every { underlying.removeListener(capture(removed)) } returns Unit
        every { underlying.availableCommands } returns rawCommandsWithoutSeek()

        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, timeline(1_800_000L))
        var hits = 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { hits++ }
        }
        sessionPlayer.addListener(listener)
        val underlyingListener = added.captured
        sessionPlayer.removeListener(listener)

        assertTrue(removed.isCaptured, "removeListener 必须把当初注册的那个包装器摘掉")
        assertEquals(underlyingListener, removed.captured, "摘掉的必须是当初注册的同一个实例")
        assertFalse(hits > 0)
    }
}
