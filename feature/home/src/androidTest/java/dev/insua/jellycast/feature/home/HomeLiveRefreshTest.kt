package dev.insua.jellycast.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设计文档 §3.1:首页变为可见时必须立刻静默刷新一次 —— 这是「打开就是最新位置」的关键。
 * §3.2:离开可见状态后定时器必须停,不在后台耗电耗流量。
 *
 * 直接测 [HomeLiveRefreshEffect](被 [HomeScreen] 内部调用的那段生命周期驱动),不测整个
 * [HomeScreen] —— 后者的 `viewModel: HomeViewModel = hiltViewModel()` 需要真实 Hilt 装配 +
 * 网络依赖,而这里要验证的只是"什么时候该调 onRefresh",与真实取数逻辑无关。用一个只记录
 * 调用次数的假驱动(`refreshCount++`),把两件事解耦——这也是 [HomeViewModelTest] 已经覆盖
 * 「刷什么」、这个文件只覆盖「什么时候刷」的分工依据。
 *
 * 用手工 [LifecycleRegistry.createUnsafe] 构造一个可摆布的 [LifecycleOwner],通过
 * [CompositionLocalProvider] 换掉宿主 Activity 的生命周期,这样才能在测试里精确控制
 * STARTED / CREATED 之间的切换,而不依赖真实 Activity 的生命周期时机。
 *
 * ⚠️ **判定"不再刷新"不能靠 `Thread.sleep()` 干等。** `ComposeTestRule` 底下的协程/时钟只有
 * 测试主动发起同步动作(`waitForIdle()`/`waitUntil()`)时才会被"泵"一次去推进;`Thread.sleep()`
 * 期间没有任何东西驱动它——实测过:不管 [HomeLiveRefreshEffect] 有没有随生命周期取消,
 * 单纯 `Thread.sleep()` 之后 `refreshCount` 都纹丝不动。这正是 Task 3 Step 5 变异验证第一次
 * 踩到的坑,一个看起来合理的用例其实毫无判别力。改用 `waitUntil` 主动"等一个不该发生的事件":
 * 在等待窗口内真的等到了(`refreshCount` 涨了),说明协程还在跑,判定失败;等不到(超时抛
 * [ComposeTimeoutException])才说明协程真的停了——`waitUntil` 内部的轮询本身就会持续"泵"
 * 协程调度器,所以这段等待窗口是有效的观测,不是又一次静默的干等。
 */
@RunWith(AndroidJUnit4::class)
class HomeLiveRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun 首页变为可见时立刻触发一次静默刷新() {
        var refreshCount = 0
        val owner = FakeLifecycleOwner()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // 间隔给得比这个用例的等待窗口长得多,这样只关心"变为可见的第一下",
                // 不会有第二次触发混进来干扰断言。
                HomeLiveRefreshEffect(intervalMs = 60_000L, onRefresh = { refreshCount++ })
            }
        }

        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeTestRule.waitForIdle()
        assertEquals("还没到 STARTED 之前不应该刷新", 0, refreshCount)

        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { refreshCount == 1 }

        assertEquals("变为可见后应该立刻刷新恰好一次", 1, refreshCount)
    }

    @Test
    fun 离开可见状态后不再触发静默刷新() {
        var refreshCount = 0
        val owner = FakeLifecycleOwner()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // 间隔给得很短,好在测试时限内证明"确实在重复刷",而不只是刷了一次就停。
                HomeLiveRefreshEffect(intervalMs = 50L, onRefresh = { refreshCount++ })
            }
        }

        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        // 等到至少发生过两次,证明定时循环真的在跑,不是巧合的一次性调用。
        composeTestRule.waitUntil(timeoutMillis = 3_000) { refreshCount >= 2 }

        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeTestRule.waitForIdle()
        val countAfterLeaving = refreshCount

        // 主动等一个"不该发生"的事件,而不是干等一段时间:等待窗口(800ms)远大于刷新间隔
        // (50ms),如果协程没有随 repeatOnLifecycle 一起取消,这里会等到 refreshCount 继续
        // 增长;真的停了则会超时。
        val keptRefreshingAfterLeaving = try {
            composeTestRule.waitUntil(timeoutMillis = 800) { refreshCount > countAfterLeaving }
            true
        } catch (timeout: ComposeTimeoutException) {
            false
        }

        assertTrue(
            "离开可见状态之后不应该再刷新(离开时是 $countAfterLeaving,等待窗口内变成了 $refreshCount)",
            !keptRefreshingAfterLeaving,
        )
    }

    /**
     * 直接钉住 `isHomeVisible` 这个新增的门:全屏播放页是 `JellyCastNavHost` 里的
     * `ModalBottomSheet`,不是 `NavHost` 目的地——展开/收起完全不产生 [Lifecycle] 转换,
     * 上面两条用例用的 [FakeLifecycleOwner] 天生看不出这个 bug(它们只能证明"效果会响应生命周期
     * 转换",证明不了"真实路径真的会产生一次生命周期转换")。
     *
     * 这里固定 `owner` 全程停在 STARTED(真实场景中首页在返回栈上、播放页盖在它上面时正是这个
     * 状态),只翻动 [isHomeVisible] ——对应"点开一集展开播放页"到"收起播放页回到首页"这一段
     * 真实交互。展开期间(`isHomeVisible = false`)一次刷新都不该有(Finding 2:定时器不该
     * 在播放页盖住首页时继续跑),收起那一刻(`isHomeVisible` 变回 `true`)应该立刻补一次
     * (Finding 1:回到首页必须看见最新的继续收听/下一集)。
     */
    @Test
    fun 播放页展开时不刷新_收起后立刻刷新一次() {
        var refreshCount = 0
        val owner = FakeLifecycleOwner()
        val isHomeVisible = mutableStateOf(false) // 起始态:播放页展开,盖住首页

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // 间隔给得很短,好在测试时限内证明"展开期间确实一直没有在刷",而不只是巧合
                // 还没等到第一下。
                HomeLiveRefreshEffect(
                    intervalMs = 50L,
                    onRefresh = { refreshCount++ },
                    isHomeVisible = isHomeVisible.value,
                )
            }
        }

        // 生命周期从始至终停在 STARTED——真实的 ModalBottomSheet 展开/收起就是这样,
        // 唯一变化的信号只有 isHomeVisible。
        composeTestRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeTestRule.waitForIdle()

        // 主动等一个"不该发生的事件":等待窗口(500ms)远大于刷新间隔(50ms),如果
        // isHomeVisible = false 没有真正拦住定时器,这里会等到 refreshCount 变化;
        // 真的拦住了才会超时。
        val refreshedWhileExpanded = try {
            composeTestRule.waitUntil(timeoutMillis = 500) { refreshCount > 0 }
            true
        } catch (timeout: ComposeTimeoutException) {
            false
        }
        assertTrue(
            "播放页展开、首页被盖住期间不应该刷新(实际 refreshCount=$refreshCount)",
            !refreshedWhileExpanded,
        )

        // 收起播放页——对应用户点了一下收起手柄。这一步不产生任何 Lifecycle 转换,
        // 能让效果重新跑起来的只有这个参数本身。
        composeTestRule.runOnUiThread { isHomeVisible.value = true }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { refreshCount == 1 }

        assertEquals("收起播放页、回到首页后应该立刻刷新恰好一次", 1, refreshCount)
    }
}
