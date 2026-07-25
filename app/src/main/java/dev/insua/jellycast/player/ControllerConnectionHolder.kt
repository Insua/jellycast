package dev.insua.jellycast.player

/**
 * 「当前有没有一个活着的会话 controller」这件事的状态机。闭合复审 Finding 1:
 * [MediaControllerPlayerConnection] 以前在 `@Singleton` 的 `init` 里建一次
 * [androidx.media3.session.MediaController] 就再也不管了,既没有处理
 * `MediaController.Listener.onDisconnected`,也没有在使用前检查过 `isConnected`。
 *
 * 于是这条很常见的路径会让 App 进入"半死不活"状态:暂停 → 从最近任务里划掉 App →
 * `MediaSessionService.onTaskRemoved` 在没在播放时 `stopSelf` → `PlaybackService.onDestroy` 里
 * `mediaSession.release()` → 那个 controller **永久断开** → 重新打开 App → 点一集播放。
 * 音频是响的(引擎直接操作 `@Singleton` 的 ExoPlayer,绕开了 controller),但所有经
 * `PlayerConnection.player` 下发的传输命令(迷你条播放/暂停、进度条 seek、点歌词行跳转、
 * 上一集/下一集)全部变成**静默 no-op**,`player.isPlaying` 还恒为 false —— UI 显示"已暂停",
 * 耳机里却在响。
 *
 * ## 为什么单独抽成一个泛型类
 *
 * `MediaController` 是 media3 的 final 类,建它要真实 `Looper` 和 `Service` 绑定,:app 的 JVM 单测
 * 里造不出来。把"断了要重建、重建不能重复、被换掉的实例必须 release"这三条规则做成不认识
 * `MediaController` 的纯逻辑,就能离线单测(项目铁律 6);[MediaControllerPlayerConnection] 只负责
 * 把 media3 的三个回调(建连完成 / 建连失败 / 断开)接到这里。
 *
 * ## 线程契约
 *
 * **所有方法只能在同一个线程上调用。** 生产环境是主线程,也就是 controller 的 application looper:
 * `MediaController.Listener` 的回调投递到那里,`buildAsync()` 的 future 也用主线程 executor 通知。
 * 因此内部不需要任何锁,而 [connecting] 这个单飞标志就足以保证"任何时刻最多只有一个在建 / 一个
 * 已建成的 controller",不会出现两个 controller 各持一份 binder 的情况。
 */
internal class ControllerConnectionHolder<C : Any>(
    /** 这个 controller 现在还连着吗(生产实现:`MediaController.isConnected`)。 */
    private val isConnected: (C) -> Boolean,
    /** 释放一个不再使用的 controller(生产实现:`MediaController.release()`)。 */
    private val release: (C) -> Unit,
    /** 把"现在可用的 controller"(或 null)发布给 UI(生产实现:写 `_player`)。 */
    private val publish: (C?) -> Unit,
    /**
     * 发起一次建连;完成后**必须**在同一个线程上回调 `onResult`,失败传 null。
     * 每次建连都要把 [onDisconnected] 接到该 controller 的断开回调上。
     */
    private val connect: (onResult: (C?) -> Unit) -> Unit,
) {
    private var current: C? = null
    private var connecting = false

    /** 当前可用的 controller;null = 还没连上 / 已断开。 */
    val controller: C? get() = current

    /**
     * 保证"有一个连着的 controller":已经连上就什么都不做;正在建连就什么都不做(单飞);
     * 手里那个已经断了(可能压根没收到断开回调)就先 release 掉再重建。
     *
     * 生产调用点:单例初始化、Activity 的 `onStart`(App 回到前台)、以及"开始播放新条目"时——
     * 见 [MediaControllerPlayerConnection]。
     */
    fun ensureConnected() {
        if (connecting) return
        current?.let { existing ->
            if (isConnected(existing)) return
            // 兜底路径:断开回调没送到,但这个 controller 已经是死对象了。
            discard(existing)
        }
        connecting = true
        connect(::onConnectResult)
    }

    /**
     * 会话被释放导致 controller 断开(`MediaController.Listener.onDisconnected`)。
     *
     * 这里**只**把它丢掉、告诉 UI"暂时没有可控制的播放器",**不**立刻重连:重连意味着
     * `bindService` 会把刚刚 `stopSelf` 掉的 [PlaybackService] 又拉起来——用户刚把 App 从最近任务
     * 里划掉,那不是他要的。重连交给 [ensureConnected] 的三个生产触发点,它们都发生在"App 在前台"
     * 或"确实要播了"的时刻,绑定必定合法。
     *
     * 迟到的 / 重复的回调(参数不是当前实例)直接忽略:那个实例早就 release 过了,再 release 一次
     * 是错的,更不能把现在这个好好的 controller 连带清掉。
     */
    fun onDisconnected(controller: C) {
        if (controller !== current) return
        discard(controller)
    }

    private fun onConnectResult(controller: C?) {
        connecting = false
        // 建连失败(Service 起不来 / 绑定被拒):静默,等下一次 ensureConnected 重试。
        if (controller == null) return
        // 建连过程中会话就被释放了:不能把死对象发给 UI,也不能让它泄漏。
        if (!isConnected(controller)) {
            release(controller)
            return
        }
        current = controller
        publish(controller)
    }

    private fun discard(controller: C) {
        current = null
        publish(null)
        release(controller)
    }
}
