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
 * v3 复审 Finding 2(Important):[SeekInterceptingPlayerTest] 里那个"只验证接线"的测试
 * (`getAvailableCommands 会查询 QueueNavigator 的 hasNext 和 hasPrevious`)是空心的——它只断言
 * [QueueNavigator.hasNext]/[QueueNavigator.hasPrevious] 被**查询过**,即使
 * [SeekInterceptingPlayer.getAvailableCommands] 把 `add`/`remove` 写反(该加的时候删、该删的时候加,
 * 也就是"没有下一集时按钮反而出现")它也照样通过。
 *
 * 根因(见该测试类头注释):`Player.Commands.Builder` 内部用 `android.util.SparseBooleanArray`
 * (真实 Android 框架类)存值,本模块 `testOptions.unitTests.isReturnDefaultValues = true` 的纯 JVM
 * 环境下它是静默空桩——`.add()`/`.contains()` 都不报错但也不真的存取,`.build()` 出来的 `Commands`
 * 不管加没加过命令,`.contains()` 永远回 `false`,断言毫无意义。
 *
 * 这里用 Robolectric 提供的**真实** `SparseBooleanArray` shadow(经
 * `tech.apter.junit5.jupiter:robolectric-extension` 接进 JUnit5,Robolectric 官方没有 JUnit5 扩展)
 * 关掉这个环境限制,直接断言 [Player.Commands.contains] 的真实结果——不再是"查过 QueueNavigator
 * 就算过",而是"加对了、删对了"。
 */
// sdk = [35]:本模块 compileSdk = 36(见 build.gradle.kts),没有显式声明 targetSdk 时合并清单会
// 把它当 targetSdk,而 Robolectric 4.14(本扩展内部拉取的版本)最高只影子到 API 35 ——
// 不改 compileSdk(那是整个模块的编译目标),只在 Robolectric 的模拟环境里钉住它认识的最高版本,
// 和 CLAUDE.md 记录的项目 targetSdk = 35 一致。
@Config(sdk = [35])
@ExtendWith(RobolectricExtension::class)
class SeekInterceptingPlayerCommandsTest {

    private fun fakeNavigator(hasNext: Boolean, hasPrevious: Boolean) = object : QueueNavigator {
        override fun hasNext(): Boolean = hasNext
        override fun hasPrevious(): Boolean = hasPrevious
        override fun next() {}
        override fun previous() {}
    }

    /**
     * `mockk<Player>(relaxed = true)` 对 `getAvailableCommands()` 这种返回值类型的方法,不会真的
     * 调一次真实的 `Player.Commands.Builder`——它自己合成一个"松弛"的 `Commands` 桩,`buildUpon()`/
     * `add()`/`.build()` 在那个桩上不是真实字节码路径,验不出真假。这里改成显式打桩:让底层 player
     * 汇报一份**真实**构造出来的基线命令集合(模拟正常播放器会有的一批标准命令),
     * [SeekInterceptingPlayer.getAvailableCommands] 的 `buildUpon()` 才会拿到真东西去加/删。
     */
    private fun underlyingPlayerWithBaselineCommands(): Player {
        val baseline = Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .build()
        return mockk<Player>(relaxed = true).also { every { it.availableCommands } returns baseline }
    }

    @Test fun `有下一条且没有上一条时,COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 被真的加入,PREVIOUS 不在`() {
        val underlying = underlyingPlayerWithBaselineCommands()
        val navigator = fakeNavigator(hasNext = true, hasPrevious = false)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        val commands = sessionPlayer.availableCommands

        assertTrue(
            commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
            "有下一条队列项时,COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 应该真的在命令集合里",
        )
        assertFalse(
            commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            "没有上一条队列项时,COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM 不应该在命令集合里",
        )
    }

    @Test fun `有上一条且没有下一条时,COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM 被真的加入,NEXT 不在`() {
        val underlying = underlyingPlayerWithBaselineCommands()
        val navigator = fakeNavigator(hasNext = false, hasPrevious = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        val commands = sessionPlayer.availableCommands

        assertTrue(
            commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            "有上一条队列项时,COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM 应该真的在命令集合里",
        )
        assertFalse(
            commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
            "没有下一条队列项时,COMMAND_SEEK_TO_NEXT_MEDIA_ITEM 不应该在命令集合里",
        )
    }

    @Test fun `既没有下一条也没有上一条时,两个命令都不在集合里`() {
        val underlying = underlyingPlayerWithBaselineCommands()
        val navigator = fakeNavigator(hasNext = false, hasPrevious = false)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        val commands = sessionPlayer.availableCommands

        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test fun `两条都有时,两个命令都真的在集合里`() {
        val underlying = underlyingPlayerWithBaselineCommands()
        val navigator = fakeNavigator(hasNext = true, hasPrevious = true)
        val sessionPlayer = SeekInterceptingPlayer(underlying, SeekRouter { }, queueNavigator = navigator)

        val commands = sessionPlayer.availableCommands

        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }
}
