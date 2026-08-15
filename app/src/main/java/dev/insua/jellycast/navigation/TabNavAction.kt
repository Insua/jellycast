package dev.insua.jellycast.navigation

/**
 * 底部导航栏点击某个 tab 时应该采取的动作。只看字符串/列表(当前路由、被点的 tab 根路由、
 * 返回栈里现有的路由),不碰 `NavController` 本身——可以在 JVM 上直接单测,不需要设备
 * (项目铁律 6)。
 */
sealed interface TabNavAction {
    /** 已经站在这个 tab 的根上:什么都不做。 */
    data object None : TabNavAction

    /**
     * 目标 tab 的根**确实在返回栈上**,执行 `popBackStack(route, inclusive = false, saveState =
     * [saveState])` 退回它——保住的是同一个 `NavBackStackEntry`,ViewModelStore/
     * SaveableStateHolder 都还在。这个动作只在"根真的在栈里"时才会被返回(见 [tabNavAction]
     * 的判断条件);根不在栈上时 `popBackStack` 会找不到目标、返回 `false`、不改动任何东西,
     * 点了没反应,和"点自己所在 tab 是 no-op"是同一种症状。
     *
     * [saveState] 为 `true` 时是`HOME`专属的一种情形:`HOME` 是起始目的地,永远在栈底,退回它
     * 的同时要把它之上的一整段(不管属于哪个 tab)存起来,好让离开的那个 tab 以后被重新点开时能
     * 恢复——这正是"跨 tab 切换"想要的效果,只是执行方式换成了 `popBackStack` 而不是
     * `navigate()`(原因见 [tabNavAction] 的 KDoc)。为 `false` 时是同一个 tab 内部"退到根,
     * 丢弃子页面"的情形,子页面不需要被存起来给谁恢复。
     */
    data class PopToTabRoot(val route: String, val saveState: Boolean) : TabNavAction

    /**
     * 跨 tab 切换,或者子页面所属的 tab 根不在栈上:
     * `navigate(route){popUpTo(HOME){saveState=true};restoreState=true}`。
     *
     * 根不在栈上时这是一次**全新 push**——但"全新"只对**这一次触发路径**成立,不是绝对保证:
     * `restoreState` 按目的地 id 匹配 `backStackMap`,如果 [route] 这个 tab 根在**更早的某次**
     * 正常访问里被存过(比如先经过 `library` 根逛过、再切走),这次即便当前分支没经过那个根,
     * `restoreState` 依然会命中那次更早的存档,恢复出**那次**离开时的子页面(可能是详情页,不是
     * 列表本身)——用户不会卡住(总能看到点内容),但落地页面取决于历史,不一定是列表。
     *
     * 这次弹出的旧分支会带着 `saveState=true` 被存进 `backStackMap`:如果它(比如
     * `library/view/{libraryId}`)本来就没有任何 tab 根挂在栈上,存下的这段自己也没有出口——
     * `ViewModelStore` 会一直占着,直到整个 `NavController` 连同它一起被销毁(Activity/进程结束)
     * 为止。范围有限(单个会话内最多囤积三个 tab 各自最近一次这样的分支),不是无界增长,
     * 但确实是"存了但可能没人来取"。
     */
    data class SwitchTab(val route: String) : TabNavAction
}

/**
 * `tabRoute == Routes.HOME` 时无条件走 [TabNavAction.PopToTabRoot]`(saveState = true)`——`HOME`
 * 是起始目的地,`inclusive = false` 的 pop 从不把它算在被弹的范围内,所以它永远在栈上,不需要
 * `navigate()`/`restoreState` 那一套。这不是随手加的特判:round 2/3 都用
 * `navigate(HOME){popUpTo(HOME){saveState=true};restoreState=true}` 处理"切到在听 tab",
 * 而**这次真的实测过**(不是从文档推断)——当被弹出的那段直接压在 `HOME` 上面(比如首页「我的
 * 媒体」库卡片直连的 `library/view/{libraryId}`,中途未经过 `"library"`)时,`restoreState` 对
 * `HOME` 自己的这次 `navigate()` 会命中刚存下的同一份东西、原样恢复回去——保存和恢复在同一次
 * 点击里互相抵消,和缺陷 A 是同一种"自我循环"机制,只是发生在 `HOME` 分支上。
 * `popBackStack(..., saveState = true)` 不经过 `navigate()`/`restoreState`,天然不会有这个问题
 * ——已经用 `TestNavRoot` 在真实场景上逐条测过(见 `ListScrollRestoreTest`)。
 *
 * 其余情形(目标不是 `HOME`)按路由字符串前缀判断"属于"关系(等于 tab 根,或以
 * `"$tabRoute/"` 为前缀),用 [stackRoutes] 判断"根实际在不在、能不能退"——两者都满足才走
 * [TabNavAction.PopToTabRoot]`(saveState = false)`(同一个 tab 内部退到根,不需要存)。
 */
fun tabNavAction(currentRoute: String?, tabRoute: String, stackRoutes: List<String?>): TabNavAction = when {
    currentRoute == tabRoute -> TabNavAction.None
    tabRoute == Routes.HOME && tabRoute in stackRoutes -> TabNavAction.PopToTabRoot(tabRoute, saveState = true)
    currentRoute != null && currentRoute.startsWith("$tabRoute/") && tabRoute in stackRoutes ->
        TabNavAction.PopToTabRoot(tabRoute, saveState = false)
    else -> TabNavAction.SwitchTab(tabRoute)
}

/**
 * 播放序列结束回首页时,当前返回栈里"值得保留"的 tab 根——从 [stackRoutes] **栈顶往栈底**找,
 * 第一个出现在 [tabRoutes] 里的那个。必须按位置找,不能只判断"栈里有没有出现过":`Routes.HOME`
 * 永远在栈底,如果只看"[tabRoutes] 里谁先出现在 [stackRoutes] 里"而不管位置,`HOME` 会不分
 * 青红皂白地抢在 `LIBRARY`/`SETTINGS` 前面命中,即便栈里明明有更具体的根。
 *
 * 找不到更具体的根(比如从首页「我的媒体」库卡片直连的 `library/view/{libraryId}`,从未 push
 * 过 `"library"` 本身)时会退到 `HOME` 自己——调用方据此对它做 `popBackStack(HOME, inclusive =
 * false)`(不 `saveState`),把这段子页面干净销毁,不去存一段以后没有任何 `navigate()` 会拿它的
 * 目的地 id 去 `restoreState`、因而永远出不来的僵尸状态。
 *
 * **这个"没有出口"的前提依赖当前的调用方式**:`restoreState` 是按**目的地 id**(不是参数)匹配
 * `backStackMap` 的键,而 `onLibraryClick`(`JellyCastNavHost.kt`)目前的 `navigate()` 调用没有
 * `restoreState = true`,所以确实没有任何路径会把 `library/view/{libraryId}` 从
 * `backStackMap` 里取出来。如果以后有人给 `onLibraryClick` 加上 `restoreState = true`,这个
 * "存了也没用"的前提就不成立了,这里的销毁决策需要跟着重新评估。
 */
fun restorableTabOwner(stackRoutes: List<String?>, tabRoutes: List<String>): String? =
    stackRoutes.lastOrNull { it in tabRoutes }
