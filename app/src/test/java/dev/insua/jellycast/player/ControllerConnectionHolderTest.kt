package dev.insua.jellycast.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 复审 Finding 1:`MediaControllerPlayerConnection` 以前在 `@Singleton` 的 `init` 里**一次性**建好
 * `MediaController` 就再也不管了,也没有处理 `MediaController.Listener.onDisconnected`。
 *
 * 触发路径(media3-session 1.10.1 已核实):暂停 → 从最近任务划掉 App → `MediaSessionService.
 * onTaskRemoved` 在"没在播放"时 `stopSelf` → `onDestroy` 里 `mediaSession.release()` → 那个
 * controller **永久断开** → 重新打开 App → 点一集。音频照样响(引擎那条路绕开了 controller),
 * 但所有经 `connection.player` 的传输命令(迷你条播放/暂停、进度条 seek、点歌词行、上一集/下一集)
 * 全都变成**静默 no-op**,而且 `player.isPlaying` 恒为 false —— UI 显示"已暂停",耳机里在响。
 * 这比崩溃更难诊断,而且验收清单里"划掉→重开→还能播"这一项**测不出来**(音频确实在播)。
 *
 * 这个类是那次修正的核心状态机,故意做成不认识 `MediaController` 的纯逻辑(`MediaController` 是
 * media3 的 final 类,:app 的单测里既没有 Robolectric 也没有真实 Looper),于是"断开要重建、
 * 重建不能重复、陈旧实例必须 release 掉"这三件事可以离线验证(项目铁律 6)。
 *
 * 线程契约:所有方法只在**同一个线程**上调用(生产环境是主线程,也就是 controller 的
 * application looper —— `onDisconnected` 回调和 `buildAsync` 的 future 回调都投递到那里),
 * 所以状态机内部不加锁也不可能出现"同时存在两个 controller"。
 */
class ControllerConnectionHolderTest {

    /** 假 controller:`connected` 可以被外部翻掉,模拟"会话已释放但还没收到回调"。 */
    private class FakeController(val name: String, var connected: Boolean = true) {
        var releaseCount = 0
    }

    /** 把 connect 做成可控的:调用方手动决定第几次连接何时完成、成功还是失败。 */
    private class Harness {
        val published = mutableListOf<FakeController?>()
        val connectCalls = mutableListOf<(FakeController?) -> Unit>()

        val holder = ControllerConnectionHolder<FakeController>(
            isConnected = { it.connected },
            release = { it.releaseCount++ },
            publish = { published += it },
            connect = { onResult -> connectCalls += onResult },
        )

        fun completeLastConnect(controller: FakeController?) {
            connectCalls.last().invoke(controller)
        }
    }

    @Test fun `连接成功后把 controller 发布出去`() {
        val h = Harness()
        val c = FakeController("c1")

        h.holder.ensureConnected()
        h.completeLastConnect(c)

        assertEquals(1, h.connectCalls.size)
        assertEquals(listOf<FakeController?>(c), h.published)
    }

    @Test fun `已经连上时再调 ensureConnected 不会建第二个 controller`() {
        val h = Harness()
        h.holder.ensureConnected()
        h.completeLastConnect(FakeController("c1"))

        h.holder.ensureConnected()
        h.holder.ensureConnected()

        assertEquals(1, h.connectCalls.size)
    }

    /** 单飞:连接还在进行中时的重复请求必须被吞掉,否则会同时建出两个 controller(两份 binder)。 */
    @Test fun `连接进行中时的重复 ensureConnected 不会并发建出两个 controller`() {
        val h = Harness()

        h.holder.ensureConnected()
        h.holder.ensureConnected()
        h.holder.ensureConnected()

        assertEquals(1, h.connectCalls.size)
    }

    @Test fun `收到 onDisconnected 时释放掉那个 controller 并发布 null`() {
        val h = Harness()
        val c = FakeController("c1")
        h.holder.ensureConnected()
        h.completeLastConnect(c)

        c.connected = false
        h.holder.onDisconnected(c)

        assertEquals(1, c.releaseCount)                      // 不泄漏被断开的 controller
        assertEquals(listOf(c, null), h.published)           // UI 立刻知道"没有可控制的播放器"
    }

    @Test fun `断开之后 ensureConnected 建出新的 controller 并重新发布`() {
        val h = Harness()
        val old = FakeController("c1")
        h.holder.ensureConnected()
        h.completeLastConnect(old)
        old.connected = false
        h.holder.onDisconnected(old)

        h.holder.ensureConnected()
        val new = FakeController("c2")
        h.completeLastConnect(new)

        assertEquals(2, h.connectCalls.size)
        assertEquals(listOf(old, null, new), h.published)
        assertSame(new, h.holder.controller)
    }

    /** 同一个实例的重复断开回调(或已被换掉的旧实例的回调)不得二次 release、不得误伤当前 controller。 */
    @Test fun `陈旧的 onDisconnected 回调既不重复 release 也不影响当前 controller`() {
        val h = Harness()
        val old = FakeController("c1")
        h.holder.ensureConnected()
        h.completeLastConnect(old)
        old.connected = false
        h.holder.onDisconnected(old)
        h.holder.ensureConnected()
        val new = FakeController("c2")
        h.completeLastConnect(new)

        h.holder.onDisconnected(old)                         // 迟到的重复回调

        assertEquals(1, old.releaseCount)
        assertEquals(0, new.releaseCount)
        assertSame(new, h.holder.controller)
        assertEquals(listOf(old, null, new), h.published)     // 没有多发一次 null
    }

    /**
     * 兜底:万一 `onDisconnected` 根本没送到(进程被换出、回调丢失),下一次 ensureConnected 必须
     * 自己发现手里这个 controller 已经断了,把它 release 掉再重建——而不是继续把一个死对象发给 UI。
     */
    @Test fun `没收到回调时 ensureConnected 也能发现连接已死并重建`() {
        val h = Harness()
        val dead = FakeController("c1")
        h.holder.ensureConnected()
        h.completeLastConnect(dead)

        dead.connected = false                               // 会话被释放了,但回调没来
        h.holder.ensureConnected()

        assertEquals(2, h.connectCalls.size)
        assertEquals(1, dead.releaseCount)
        assertNull(h.holder.controller)
        assertEquals(listOf(dead, null), h.published)
    }

    /** 连接失败(Service 起不来 / 后台绑定被拒)必须静默:不崩、不发布死对象,并且之后还能重试。 */
    @Test fun `连接失败时静默处理且之后仍可重试`() {
        val h = Harness()

        h.holder.ensureConnected()
        h.completeLastConnect(null)

        assertEquals(emptyList<FakeController?>(), h.published)
        assertNull(h.holder.controller)

        h.holder.ensureConnected()
        val c = FakeController("c2")
        h.completeLastConnect(c)

        assertEquals(2, h.connectCalls.size)
        assertSame(c, h.holder.controller)
    }

    /** future 完成时 controller 已经断开(会话在建连过程中就被释放):不能发布,必须 release 掉。 */
    @Test fun `建连完成时已断开的 controller 不会被发布`() {
        val h = Harness()
        val bornDead = FakeController("c1", connected = false)

        h.holder.ensureConnected()
        h.completeLastConnect(bornDead)

        assertEquals(emptyList<FakeController?>(), h.published)
        assertEquals(1, bornDead.releaseCount)
        assertNull(h.holder.controller)
        assertTrue(h.holder.controller == null)
    }
}
