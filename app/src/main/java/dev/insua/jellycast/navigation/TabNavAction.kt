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
     * 当前路由属于这个 tab(是它的子页面)、**且这个 tab 的根确实在返回栈上**:
     * `popBackStack(route, inclusive = false)` 退回根——保住的是同一个 `NavBackStackEntry`,
     * ViewModelStore/SaveableStateHolder 都还在。
     *
     * 后一个条件不是多余的:子页面可以不经过 tab 根就被直接推入栈(比如首页「我的媒体」库卡片
     * 直连 `library/view/{libraryId}`,从未 push 过 `"library"` 本身)。这种情况下
     * `popBackStack(route, inclusive = false)` 找不到目标,返回 `false`、不改动任何东西——点了
     * 没反应,和"点自己所在 tab 是 no-op"是同一种症状,只是换了条路径触发。所以这个动作只在
     * "根真的在栈里"时才会被返回,不能只按路由字符串前缀判断归属。
     */
    data class PopToTabRoot(val route: String) : TabNavAction

    /** 跨 tab 切换,或者子页面所属的 tab 根不在栈上:
     *  `navigate(route){popUpTo(HOME){saveState=true};restoreState=true}`,保留/恢复目标 tab
     *  自己的浏览状态(根不在栈上时这是一次全新 push,不是"恢复")。 */
    data class SwitchTab(val route: String) : TabNavAction
}

/**
 * [tabRoute] 的根是否出现在 [stackRoutes] 里,决定子页面点击能不能走 [TabNavAction.PopToTabRoot]。
 * `HOME` 是起始目的地,永远在栈底——`popBackStack("home", inclusive = false)` 的返回值本身
 * 因此对任何路由都是"成功"的,不能拿它当"当前路由是否属于 HOME"的判断依据(那是自我循环:
 * 拿要验证的东西的执行结果去验证它)。改用路由字符串前缀("属于"关系:等于 tab 根,或以
 * `"$tabRoute/"` 为前缀)决定"应不应该退回根",用 [stackRoutes] 决定"根实际在不在、能不能退",
 * 两者都满足才返回 [TabNavAction.PopToTabRoot]。
 */
fun tabNavAction(currentRoute: String?, tabRoute: String, stackRoutes: List<String?>): TabNavAction = when {
    currentRoute == tabRoute -> TabNavAction.None
    currentRoute != null && currentRoute.startsWith("$tabRoute/") && tabRoute in stackRoutes ->
        TabNavAction.PopToTabRoot(tabRoute)
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
