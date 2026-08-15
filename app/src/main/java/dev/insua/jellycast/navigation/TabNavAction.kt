package dev.insua.jellycast.navigation

import androidx.navigation.NavController

/**
 * # 铁律:`HOME` 不许出现在 `navigate(…){ restoreState = true }` 的目标位置
 *
 * **凡是会被当成 `popUpTo`/`popBackStack` 锚点、且带 `saveState = true` 的目的地(本项目里只有
 * [Routes.HOME]),都不许成为 `navigate(…){ restoreState = true }` 的目标。** 想回到 `HOME` 一律
 * 走 `popBackStack(Routes.HOME, inclusive = false, …)`(见 [NavController.executeReturnToHome] 与
 * [TabNavAction.PopToTabRoot])——`HOME` 是起始目的地、永远在栈上,压根没有什么需要"恢复"。
 *
 * ## 为什么(反编译得到的机制,不是文档推断)
 *
 * 反编译 `androidx.navigation.internal.NavControllerImpl.executePopOperations`(2.9.8):
 * `saveState = true` 且 `inclusive = false` 时,被弹出的那一段会被写进 `backStackMap` **两次**——
 * 一次以"被弹出的最深的那个目的地"为键,一次以 `foundDestination`(**pop 锚点自己**,这里就是
 * `HOME`)为键,后者仅在该键已存在时才跳过。所以
 * `popBackStack(HOME, inclusive = false, saveState = true)` 会把这一段**存到 `HOME` 的键底下**,
 * 而 `navigate(HOME){ popUpTo(HOME){ saveState = true }; restoreState = true }` 正是这个键的
 * **取款人**:它会把别的调用点存进去的那一段原样恢复出来,把用户送回一个早就离开的页面。
 *
 * ## 存款和取款可以跨调用点、跨时间发生
 *
 * 这是本任务前四轮反复漏掉同一族缺陷的原因:审查时只在**单次调用内部**查"目标 `X` 和锚点 `Y`
 * 相不相等"。真正的碰撞是 **A 调用点存、B 调用点过一会儿取**——用户点『在听』
 * ([TabNavAction.PopToTabRoot]`(HOME, saveState = true)`)把 `library/view/{libraryId}` 存进
 * `HOME` 的键,过一会儿播放序列结束,`returnToHome` 的 `navigate(HOME){…restoreState = true}`
 * 把它取出来——人被"送回"那个库浏览页,而不是首页。审查新写的导航调用要问的是
 * **"这个键会不会有别的调用点来取"**,不是"这一次调用里 X 是否等于 Y"。
 *
 * ## 当前四个导航调用点按这条铁律的归类(`JellyCastNavHost.kt`;底部栏按两条分支拆开写)
 *
 * | 调用点 | 存(`saveState`)| 取(`restoreState`)| 结论 |
 * |---|---|---|---|
 * | 底部栏 [TabNavAction.PopToTabRoot] | `HOME` 键 + 最深被弹目的地键(仅 `saveState=true` 时)| 无 | 合规:只存不取 |
 * | 底部栏 [TabNavAction.SwitchTab] | 同上 | 目标 tab 根的键 | 合规:目标恒不为 `HOME`(见 [tabNavAction]) |
 * | `returnToHome` → [NavController.executeReturnToHome] | 同上 | 无 | 合规:已改成纯 `popBackStack`,不再取 `HOME` 的键 |
 * | 首页 `onSearchClick` | 同上 | `LIBRARY` 键 | 合规:它就是 [TabNavAction.SwitchTab]`(LIBRARY)`,目标是 `LIBRARY`,不是锚点 `HOME` |
 * | 首页 `onLibraryClick` | 无 | 无 | 合规:一次普通 `navigate`,不碰 `backStackMap` |
 *
 * 底部导航栏点击某个 tab 时应该采取的动作。[tabNavAction] / [restorableTabOwner] 只看字符串/列表
 * (当前路由、被点的 tab 根路由、返回栈里现有的路由),不碰 `NavController` 本身——可以在 JVM 上
 * 直接单测,不需要设备(项目铁律 6)。**注意:`backStackMap` 不是这两个纯函数的输入**,所以上面
 * 那条铁律**无法**用 JVM 单测守卫,只能靠设备测试穷举可达序列(`TabNavInvariantSequenceTest`)。
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
     *
     * **已知的潜在缺陷,现在够不着,故意不特判**:站在 `servers` 上点「设置」会落到这个分支,
     * `popUpTo(HOME){saveState=true}` 把 `[settings, servers]` 整段按"最深被弹出的目的地"
     * (`settings`)存起来,紧接着同一次调用的 `restoreState`(目标正是 `settings`)又把它整段
     * 恢复回来——点了没反应。这是本文件顶部那条铁律的另一种形态(取款键这次是"最深被弹出的目的地"
     * 而不是锚点)。今天不可达:`servers ∉ Routes.CHROME_ROUTES`,那个页面上根本不显示底部 tab 栏。
     * **一旦 `servers` 被加进 `CHROME_ROUTES`,这条就会立刻变成真缺陷**,届时要连同这个分支一起
     * 重新设计(多半是改成"每个 tab 一个嵌套 `NavGraph`"),而不是再加一个特判。
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
 * false)`(不 `saveState`),把这段子页面干净销毁,而不是留一段大概率没人来取的状态。
 *
 * **这是一个取舍,不是"存了必然没用"的定理。** 之前这里写的是「确实没有任何路径会把
 * `library/view/{libraryId}` 从 `backStackMap` 里取出来」,并且只拿"`onLibraryClick` 没写
 * `restoreState`"当依据——**那句话是错的**,漏掉了另一把钥匙:带 `saveState` 的 pop 会把同一段
 * **同时**存到"最深被弹出的目的地"和"pop 锚点(`HOME`)"两个键底下(机制见本文件顶部的铁律),
 * 而 `HOME` 这个键在修好之前是有取款人的——`returnToHome` 自己的
 * `navigate(HOME){…restoreState = true}` 会把它取出来,把用户送回那个库浏览页。所以要判断
 * "存下去有没有出口",必须**两个键各查一遍有没有取款人**:
 *
 * - `library/view/{libraryId}` 这个键:取款人只能是 `navigate(libraryView(...)){restoreState}`,
 *   `onLibraryClick` 没写 `restoreState`,今天没有取款人。
 * - `HOME` 这个键:取款人只能是 `navigate(HOME){restoreState}`——按顶部那条铁律,这种写法在本
 *   代码库里已经不存在了,今天也没有取款人。
 *
 * 两个前提**任意一个**被打破(有人给 `onLibraryClick` 加上 `restoreState = true`,或者有人重新
 * 引入 `navigate(HOME){restoreState = true}`),这里的销毁决策就要重新评估。
 */
fun restorableTabOwner(stackRoutes: List<String?>, tabRoutes: List<String>): String? =
    stackRoutes.lastOrNull { it in tabRoutes }

/**
 * [tabNavAction] 决策的执行体。抽成函数(而不是内联在 `BottomNavBar` 的 `onClick` 里)是为了让
 * 设备测试驱动的**就是生产代码本身**——包括 `navigate` 的那几行 `NavOptions`。这一点很重要:
 * 本文件顶部那条铁律讲的是 `saveState`/`restoreState` 的配对,而配对关系恰恰写在 `NavOptions`
 * 里;如果测试里再手抄一份"长得像"的 `NavOptions`,变异验证打在生产代码上就不会变红。
 */
fun NavController.executeTabNavAction(action: TabNavAction) {
    when (action) {
        TabNavAction.None -> Unit
        is TabNavAction.PopToTabRoot ->
            popBackStack(action.route, inclusive = false, saveState = action.saveState)
        // 目标恒不为 Routes.HOME(tabNavAction 已经把 HOME 分流到 PopToTabRoot),
        // 因此不违反顶部那条铁律。
        is TabNavAction.SwitchTab -> navigate(action.route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * 播放序列结束回首页的执行体(`JellyCastNavHost.kt` 里 `LaunchedEffect(returnToHome)` 调用它)。
 * 两步:
 *
 * 1. 先退到 [restorableTabOwner] 选出的那个 tab 根(不 `saveState`),把"你刚看完的那一集/那个
 *    服务器列表"这类子页面干净弹掉——播完了就不该以后自动弹回详情页。
 * 2. 再 `popBackStack(HOME, inclusive = false, saveState = true)` 把留下的那个 tab 根存起来,
 *    让用户下次点该 tab 时能连同滚动位置一起恢复。
 *
 * **第 2 步绝不能写成 `navigate(HOME){ popUpTo(HOME){ saveState = true }; restoreState = true }`**
 * ——那正是本文件顶部那条铁律禁止的形状,实测症状:先点『在听』(它会把当时那段存到 `HOME` 的键
 * 底下),播放序列结束时这里的 `restoreState` 又把同一段取出来,用户落在
 * `library/view/{libraryId}` 而不是首页。`popBackStack` 只存不取,天然没有这个问题;而且 `HOME`
 * 是起始目的地、永远在栈上,`inclusive = false` 的 pop 一定能落在它上面,不需要"恢复"。
 */
fun NavController.executeReturnToHome(tabRoutes: List<String>) {
    val stackRoutes = currentBackStack.value.map { it.destination.route }
    val owner = restorableTabOwner(stackRoutes, tabRoutes)
    if (owner != null) popBackStack(owner, inclusive = false)
    popBackStack(Routes.HOME, inclusive = false, saveState = true)
}
