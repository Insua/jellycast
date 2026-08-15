package dev.insua.jellycast.navigation

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TabNavAction.kt` 顶部那条铁律的守卫测试:**凡是会被当成带 `saveState = true` 的 pop 锚点的
 * 目的地([Routes.HOME]),都不许成为 `navigate(…){ restoreState = true }` 的目标。**
 *
 * ## 为什么必须是设备测试,JVM 单测守不住
 *
 * `backStackMap`(存/取那张表)**不是** [tabNavAction] / [restorableTabOwner] 这两个纯函数的输入
 * ——它活在 `NavController` 内部,由 `NavOptions` 上的 `saveState`/`restoreState` 隐式读写。所以
 * 无论 `TabNavActionTest`(JVM)写得多细,都**看不见**"A 调用点存进去、B 调用点过一会儿取出来"
 * 这类跨调用点、跨时间的碰撞;硬要在 JVM 上写一条"看起来像在守它"的用例,只会给出虚假的安全感。
 * 唯一能真的守住的办法,是在真实 `NavController` 上把**可达的动作序列穷举一遍**,直接观察落地在
 * 哪个目的地——这个文件干的就是这件事。
 *
 * ## 被守卫的三条不变式(每一步都查)
 *
 * 1. **播放序列结束回首页,必须真的落在 [Routes.HOME]。** 这是 Fix round 5 那个 Critical 的
 *    直接形式:点『在听』把当时那段存进了 `HOME` 的键,`returnToHome` 的
 *    `navigate(HOME){…restoreState}` 又把它取出来,人被送回 `library/view/{libraryId}`。
 * 2. **点一个不是自己所在 tab 的 tab,返回栈必须真的变化**(不是"点了没反应"——round 1/3/4
 *    各栽过一次的那族症状)**,而且必须落在属于该 tab 的目的地上**(不能被"传送"到别的 tab 的
 *    陈旧页面上)。
 * 3. **站在某个 tab 的根上再点它自己,返回栈必须逐字节不变**([TabNavAction.None])。
 *
 * ## 路由表与"可达"的定义
 *
 * 目的地表、tab 表、以及每个页面上"能往下钻到哪"都复刻 `JellyCastNavHost.kt`:首页「我的媒体」
 * 卡片 → `library/view/{libraryId}`;媒体库 → 剧集详情/合集详情;按库浏览、合集详情 → 剧集详情;
 * 设置 → 管理服务器。**tab 点击只在 [Routes.CHROME_ROUTES] 上枚举**——生产里底部 tab 栏正是按这
 * 个集合显示/隐藏的,`servers` 上根本没有 tab 栏可点。
 *
 * 首页顶栏的搜索入口(`onSearchClick`)没有单独列成一种动作:它调用的就是
 * [executeTabNavAction]`(`[TabNavAction.SwitchTab]`(LIBRARY))`,和"站在首页点『媒体库』tab"
 * 是同一次调用、同一份 `NavOptions`——枚举里的 `home → tab:library` 那一步已经把它覆盖了。
 *
 * > **已知的潜在项(故意不修,也故意不特判)**:如果哪天 `servers` 被加进
 * > [Routes.CHROME_ROUTES],"站在 `servers` 上点『设置』"会成为可达序列,而那一步今天会自我抵消
 * > 成一次死点击(机制见 [TabNavAction.SwitchTab] 的 KDoc)。到那天这个测试会自动把它测出来——
 * > 枚举是按 [Routes.CHROME_ROUTES] 现算的,不需要有人记得回来补用例。
 *
 * 驱动方式是直接调用生产执行体([executeTabNavAction] / [executeReturnToHome]),不经过 UI:
 * 这里要验的是 `NavOptions` 与 `backStackMap` 的相互作用,和像素无关,不需要真实屏幕内容——
 * 屏幕内容那一侧(滚动位置真的保住了)由 `ListScrollRestoreTest` 负责。
 */
@RunWith(AndroidJUnit4::class)
class TabNavInvariantSequenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val generation = mutableStateOf(0)
    private var navController: NavHostController? = null

    // ---------------------------------------------------------------- 具名回归用例

    /**
     * **Fix round 5 的 Critical,最小复现。**
     *
     * `首页 → 我的媒体卡片(library/view/lib1) → 点『在听』 → 播放序列结束`。
     *
     * 修好之前:点『在听』走 [TabNavAction.PopToTabRoot]`(HOME, saveState = true)`,把
     * `library/view/lib1` 这一段**同时**存进"最深被弹出的目的地"和"pop 锚点 `HOME`"两个键;
     * 随后 `returnToHome` 的 `navigate(HOME){popUpTo(HOME){saveState=true};restoreState=true}`
     * 正是 `HOME` 这个键的取款人,把它原样恢复——用户落在 `library/view/{libraryId}`,不是首页。
     *
     * 修好之后:`returnToHome` 改用 `popBackStack(HOME, inclusive = false, saveState = true)`,
     * 只存不取,`HOME` 这个键再也没有取款人。
     */
    @Test
    fun 库卡片进按库浏览页点在听之后播放序列结束必须落在首页() {
        startHarness()

        push(Routes.libraryView("lib1"))
        assertCurrentRoute(Routes.LIBRARY_VIEW_PATTERN, "前置条件:应当站在按库浏览页")

        tapTab(Routes.HOME)
        assertCurrentRoute(Routes.HOME, "点『在听』应当落在首页")

        returnToHome()
        assertCurrentRoute(
            Routes.HOME,
            "播放序列结束应当落在首页,不能把点『在听』时存进 HOME 键的那一段恢复出来",
        )
    }

    /** 同一个 Critical 再钻深一层(`library/view/{id} → library/series/{id}`),结论必须不变。 */
    @Test
    fun 库卡片再打开一集后点在听之后播放序列结束必须落在首页() {
        startHarness()

        push(Routes.libraryView("lib1"))
        push(Routes.seriesDetail("s1"))
        assertCurrentRoute(Routes.SERIES_DETAIL_PATTERN, "前置条件:应当站在剧集详情页")

        tapTab(Routes.HOME)
        assertCurrentRoute(Routes.HOME, "点『在听』应当落在首页")

        returnToHome()
        assertCurrentRoute(Routes.HOME, "播放序列结束应当落在首页")
    }

    /**
     * 修复不能以牺牲验收语义为代价:播放序列结束之后再点『媒体库』,恢复出来的必须是**同一个**
     * `library` 条目(entry id 不变)——`ViewModelStore`/`SaveableStateHolder` 都还在,滚动位置
     * 才谈得上保住。(滚动位置本身由 `ListScrollRestoreTest` 在真实列表上验;这里验的是它赖以
     * 成立的那个前提:条目存活。)
     */
    @Test
    fun 播放序列结束回首页后再点媒体库恢复的是同一个library条目() {
        startHarness()

        tapTab(Routes.LIBRARY)
        val libraryIdBefore = requireNotNull(entryIdOf(Routes.LIBRARY)) { "前置条件:library 应当在栈上" }
        push(Routes.seriesDetail("s1"))

        returnToHome()
        assertCurrentRoute(Routes.HOME, "播放序列结束应当落在首页")

        tapTab(Routes.LIBRARY)
        assertCurrentRoute(Routes.LIBRARY, "再次点『媒体库』应当落在列表本身(详情页已在上一步被丢弃)")
        assertEquals(
            "恢复出来的应当是同一个 library 条目(entry id 不变)—— id 变了说明是重新 push 的新条目,滚动位置必然丢",
            libraryIdBefore,
            entryIdOf(Routes.LIBRARY),
        )
    }

    // ---------------------------------------------------------------- 穷举

    /**
     * 把深度 [DEPTH] 以内的**全部可达动作序列**跑一遍,每一步都查上面那三条不变式。违规不立即
     * 抛出,而是全部收集起来一次性报出来——一轮就能看到"到底有几条、分别是哪几条",不是修一条
     * 再发现下一条(这个任务前四轮正是这样反复的)。
     */
    @Test
    fun 深度3以内的全部可达导航序列都不违反不变式() {
        startHarness()

        val violations = mutableListOf<String>()
        var sequenceCount = 0
        var stepCount = 0

        fun walk(prefix: List<Step>) {
            val route = replay(prefix, violations) { stepCount++ }
            sequenceCount++
            if (prefix.size == DEPTH) return
            availableSteps(route).forEach { walk(prefix + it) }
        }
        walk(emptyList())

        Log.i(
            SEQ_TAG,
            "枚举完成:$sequenceCount 条可达序列(深度 <= $DEPTH,含前缀),共执行 $stepCount 步," +
                "违规 ${violations.size} 条",
        )
        violations.forEach { Log.e(SEQ_TAG, it) }

        assertTrue(
            "在 $sequenceCount 条可达序列($stepCount 步)里发现 ${violations.size} 条违反导航不变式:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- 脚手架

    private fun startHarness() {
        composeTestRule.setContent {
            key(generation.value) {
                val nav = rememberNavController()
                SideEffect { navController = nav }
                RouteTableHarness(nav)
            }
        }
        composeTestRule.waitForIdle()
    }

    /** 换一个全新的 `NavController`(连同它内部全新的 `backStackMap`),让每条序列互不污染。 */
    private fun resetHarness() {
        composeTestRule.runOnUiThread { generation.value += 1 }
        composeTestRule.waitForIdle()
    }

    private fun snapshot(): List<BackStackEntrySnapshot> = composeTestRule.runOnIdle {
        requireNotNull(navController).currentBackStack.value.map {
            BackStackEntrySnapshot(it.destination.route, it.id)
        }
    }

    private fun currentRoute(): String? = snapshot().lastOrNull()?.route

    private fun entryIdOf(route: String): String? = snapshot().lastOrNull { it.route == route }?.id

    private fun assertCurrentRoute(expected: String, message: String) {
        assertEquals(message, expected, currentRoute())
    }

    private fun push(route: String) = perform(Step.Push(route))

    private fun tapTab(tabRoute: String) = perform(Step.Tab(tabRoute))

    private fun returnToHome() = perform(Step.ReturnToHome)

    /** 执行一步,返回 (执行前快照, 执行后快照)。 */
    private fun perform(step: Step): Pair<List<BackStackEntrySnapshot>, List<BackStackEntrySnapshot>> {
        val before = snapshot()
        val beforeRoute = before.lastOrNull()?.route
        val nav = requireNotNull(navController)
        composeTestRule.runOnUiThread {
            when (step) {
                // 生产 BottomNavBar.onClick 的两行:决策 + 执行,一个字都没改。
                is Step.Tab -> nav.executeTabNavAction(
                    tabNavAction(beforeRoute, step.tabRoute, before.map { it.route }),
                )
                // 生产 onLibraryClick / onSeriesClick / onCollectionClick / onManageServers:
                // 都是不带任何 NavOptions 的 navigate(route)。
                is Step.Push -> nav.navigate(step.route)
                // 生产 LaunchedEffect(returnToHome) 调用的执行体。
                Step.ReturnToHome -> nav.executeReturnToHome(TAB_ROUTES)
            }
        }
        composeTestRule.waitForIdle()
        return before to snapshot()
    }

    /**
     * 用一个全新的 `NavController` 重放 [prefix],逐步查不变式,返回重放结束时的当前路由。
     * 每条序列都从零重放(而不是接着上一条往下走)——`backStackMap` 里的残留会让"这一步的行为"
     * 依赖于此前跑过什么,那样测出来的东西不可复现。
     */
    private fun replay(prefix: List<Step>, violations: MutableList<String>, onStep: () -> Unit): String? {
        resetHarness()
        prefix.forEachIndexed { index, step ->
            onStep()
            val (before, after) = perform(step)
            val trace = prefix.take(index + 1).joinToString(" → ") { it.label }
            verifyStep(step, before, after, trace, violations)
        }
        return currentRoute()
    }

    private fun verifyStep(
        step: Step,
        before: List<BackStackEntrySnapshot>,
        after: List<BackStackEntrySnapshot>,
        trace: String,
        violations: MutableList<String>,
    ) {
        val beforeRoute = before.lastOrNull()?.route
        val afterRoute = after.lastOrNull()?.route
        fun violation(why: String) {
            violations += "[$trace] $why(执行前=$beforeRoute 执行后=$afterRoute)\n    before=$before\n    after=$after"
        }
        when (step) {
            Step.ReturnToHome ->
                if (afterRoute != Routes.HOME) {
                    violation("播放序列结束必须落在首页,实际落在 $afterRoute")
                }
            is Step.Tab -> when {
                beforeRoute == step.tabRoute ->
                    if (after != before) violation("站在 tab 根上再点它自己,返回栈不该有任何变化")
                after == before ->
                    violation("点『${step.label}』没有产生任何效果(返回栈逐字节相同)——死点击")
                !belongsToTab(afterRoute, step.tabRoute) ->
                    violation("点『${step.label}』后落在 $afterRoute,不属于 ${step.tabRoute} 这个 tab")
                else -> Unit
            }
            is Step.Push ->
                if (after.size != before.size + 1) violation("普通 push 应当让返回栈长度 +1")
        }
    }

    private fun availableSteps(route: String?): List<Step> = buildList {
        // 底部 tab 栏只在 CHROME_ROUTES 上显示(生产 JellyCastNavHost 的 showChrome),
        // 其它地方压根点不到 —— 枚举必须遵守同一条件,否则测的是不可达的场景。
        if (route in Routes.CHROME_ROUTES) TAB_ROUTES.forEach { add(Step.Tab(it)) }
        when (route) {
            Routes.HOME -> add(Step.Push(Routes.libraryView("lib1")))
            Routes.LIBRARY -> {
                add(Step.Push(Routes.seriesDetail("s1")))
                add(Step.Push(Routes.collectionDetail("c1")))
            }
            Routes.LIBRARY_VIEW_PATTERN -> add(Step.Push(Routes.seriesDetail("s1")))
            Routes.COLLECTION_DETAIL_PATTERN -> add(Step.Push(Routes.seriesDetail("s1")))
            Routes.SETTINGS -> add(Step.Push(Routes.SERVERS))
        }
        // 播放序列随时可能结束,和当前站在哪无关(生产里它是顶层 LaunchedEffect),
        // 包括没有 tab 栏的 servers 页。
        add(Step.ReturnToHome)
    }

    private companion object {
        const val DEPTH = 3
    }
}

private const val SEQ_TAG = "TabNavInvariantSeq"

/** 一步可执行的导航动作。[label] 只用于违规报告里的序列轨迹。 */
private sealed interface Step {
    val label: String

    data class Tab(val tabRoute: String) : Step {
        override val label: String get() = "tab:$tabRoute"
    }

    data class Push(val route: String) : Step {
        override val label: String get() = "push:$route"
    }

    data object ReturnToHome : Step {
        override val label: String get() = "播放序列结束回首页"
    }
}

/** "落地的目的地属不属于这个 tab":等于 tab 根,或挂在 `"$tabRoute/"` 前缀下。 */
private fun belongsToTab(route: String?, tabRoute: String): Boolean =
    route == tabRoute || (route != null && route.startsWith("$tabRoute/"))

/**
 * 只有路由表、没有真实页面的最小 `NavHost`——目的地内容和这个测试要验的东西无关(验的是
 * `NavOptions` 与 `backStackMap` 的相互作用),放个 [Text] 占位就够,换来的是每条序列都能在几十
 * 毫秒里重放完,穷举才跑得起来。路由字符串全部来自生产 [Routes]。
 */
@Composable
private fun RouteTableHarness(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { Text("home") }
        composable(Routes.LIBRARY) { Text("library") }
        composable(Routes.SERIES_DETAIL_PATTERN) { Text("series") }
        composable(Routes.COLLECTION_DETAIL_PATTERN) { Text("collection") }
        composable(Routes.LIBRARY_VIEW_PATTERN) { Text("libraryView") }
        composable(Routes.SETTINGS) { Text("settings") }
        composable(Routes.SERVERS) { Text("servers") }
    }
}
