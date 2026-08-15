package dev.insua.jellycast.navigation

/**
 * 底部导航栏点击某个 tab 时应该采取的动作。只看字符串(当前路由 + 被点的 tab 根路由),不碰
 * `NavController`/返回栈实际内容——可以在 JVM 上直接单测,不需要设备(项目铁律 6)。
 *
 * Fix round 1 的 Critical 1 就是不做这个区分的后果:那一轮直接用
 * `navController.popBackStack(tab.route, inclusive = false)` 的返回值当条件——`HOME` 是起始
 * 目的地,永远在栈底,`popBackStack("home", inclusive = false)` 对**任何**非 HOME 的当前路由都
 * 会"成功"(把它上面的所有东西弹光,不带 `saveState`),于是从「媒体库」子页面点「在听」也会走这
 * 条分支,把「媒体库」的状态弹没了——正是这个任务要修的那个症状,原样又发生了一次。
 *
 * 区分办法是"当前路由是否属于这个 tab":等于 tab 根,或者以 `"$tabRoute/"` 为前缀。
 * `HOME` 没有任何以 `"home/"` 为前缀的子路由,所以从子页面点「在听」永远落在 [SwitchTab] 分支
 * (`popUpTo(HOME){saveState=true}` 保留当前 tab 的状态),不会再误触发 [PopToTabRoot]。
 */
sealed interface TabNavAction {
    /** 已经站在这个 tab 的根上:什么都不做。 */
    data object None : TabNavAction

    /** 当前路由属于这个 tab(是它的子页面)但不是根本身:`popBackStack(route, inclusive = false)`
     *  退回根——保住的是同一个 `NavBackStackEntry`,ViewModelStore/SaveableStateHolder 都还在。 */
    data class PopToTabRoot(val route: String) : TabNavAction

    /** 真正的跨 tab 切换:`navigate(route){popUpTo(HOME){saveState=true};restoreState=true}`,
     *  保留/恢复目标 tab 自己的浏览状态。 */
    data class SwitchTab(val route: String) : TabNavAction
}

fun tabNavAction(currentRoute: String?, tabRoute: String): TabNavAction = when {
    currentRoute == tabRoute -> TabNavAction.None
    currentRoute != null && currentRoute.startsWith("$tabRoute/") -> TabNavAction.PopToTabRoot(tabRoute)
    else -> TabNavAction.SwitchTab(tabRoute)
}

/**
 * 播放序列结束回首页时,当前返回栈里"值得保留"的 tab 根——从 [stackRoutes] **栈顶往栈底**找,
 * 第一个出现在 [tabRoutes] 里的那个。`Routes.HOME` 是起始目的地,永远在栈底,所以只要
 * [tabRoutes] 里包含它,这个函数实际上总能找到东西(最差也是 `HOME` 自己);`null` 只在
 * [tabRoutes] 没包含 `HOME`、且栈里也没出现过别的候选根时才会发生——留着这个分支是为了让函数
 * 本身保持诚实(输入决定输出,不藏一个"HOME 总归兜底"的隐藏假设),不是当前唯一调用方
 * (`returnToHome`)会用到的路径。
 *
 * 为什么要"从栈顶往栈底找",不能只看"栈里有没有出现过"(Fix round 2 第一版实现踩过这个坑,单测
 * 已经把它打红过一次,细节见 `TabNavActionTest`):`HOME` 永远在栈里,如果只判断"tabRoutes 里
 * 谁先出现在 stackRoutes 里"而不管位置,`HOME` 会不分青红皂白地抢在 `LIBRARY`/`SETTINGS` 前面
 * 命中,导致明明栈里有 `library` 这个根,也会被判定成"没有可保留的根"。只有按"离当前位置多近"
 * (即栈顶方向)找,才能找到真正当前所在的那个 tab。
 *
 * 为什么需要这一步(而不是直接对当前路由做 `popUpTo(HOME){saveState=true}`,Fix round 1 的
 * Critical 2 就是这么写的):`saveState` 存下的整段东西,只有当"点某个 tab"最终 `navigate()` 到
 * 的目的地(`tabRoute` 本身)真的是这段被弹出范围里的一员时,才有对应的 `restoreState` 能找到它。
 * 「媒体库」tab 按钮永远 `navigate("library")`,但首页「我的媒体」库卡片是直接跳
 * `library/view/{libraryId}`(`JellyCastNavHost.kt` 的 `onLibraryClick`),中途从来没有把
 * `"library"` 本身压过栈——这时如果仍然对 `library/view/{libraryId}` 本身做 `saveState=true`,
 * 存下的东西被存进 `backStackMap`,以后却没有任何 `navigate()` 调用会拿它的 key 去
 * `restoreState`——它的 `ViewModelStore` 就永远留在那儿出不来了(Important 3)。这种情况下
 * `restorableTabOwner` 会往栈底找到 `HOME` 自己;调用方拿到 `HOME` 之后对它做
 * `popBackStack(HOME, inclusive = false)`(不 `saveState`,函数签名默认值就是 `false`),
 * `library/view/{libraryId}` 会被干净销毁,不会变成一段永远够不着的僵尸状态。
 *
 * 同理,"设置" tab 下的「管理服务器」(`servers`,不在 `CHROME_ROUTES` 里、也不是
 * `"settings/"` 前缀的子路由)找到的也不会是它——只要 `settings` 本身还在栈里(比栈底的
 * `home` 更靠近栈顶),会先命中 `settings`,`popBackStack(settings, inclusive = false)`
 * 会把 `servers` 弹掉(同样默认不 `saveState`),不会像 Fix round 1 那样让 `servers` 混进
 * 被保留的那一段里(Important 4)。
 */
fun restorableTabOwner(stackRoutes: List<String?>, tabRoutes: List<String>): String? =
    stackRoutes.lastOrNull { it in tabRoutes }
