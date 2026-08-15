package dev.insua.jellycast.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `tabNavAction` / `restorableTabOwner` 是纯函数,离线可单测(项目铁律 6)——这里测的、下面
 * mutation 验证要打的,都是 `TabNavAction.kt` 里的**生产代码本身**,不是 Compose 测试里搭的镜像。
 *
 * 表驱动覆盖三个 tab 根(`home`/`library`/`settings`)、`library` 的三种子路由模式
 * (`series`/`collection`/`view`)、非 CHROME_ROUTES 的 `servers`、`null`(导航还没就绪时
 * `currentRoute` 的取值)、"子页面所属的 tab 根不在返回栈上"(首页库卡片直连场景),以及
 * "点『在听』时 `HOME` 作为 `popUpTo` 锚点自身"这个 fix round 4 才发现的自我循环场景。
 */
class TabNavActionTest {

    @Test fun `已经站在tab根上什么都不做`() {
        assertEquals(
            TabNavAction.None,
            tabNavAction(currentRoute = Routes.HOME, tabRoute = Routes.HOME, stackRoutes = listOf(Routes.HOME)),
        )
        assertEquals(
            TabNavAction.None,
            tabNavAction(
                currentRoute = Routes.LIBRARY,
                tabRoute = Routes.LIBRARY,
                stackRoutes = listOf(Routes.HOME, Routes.LIBRARY),
            ),
        )
        assertEquals(
            TabNavAction.None,
            tabNavAction(
                currentRoute = Routes.SETTINGS,
                tabRoute = Routes.SETTINGS,
                stackRoutes = listOf(Routes.HOME, Routes.SETTINGS),
            ),
        )
    }

    /** 正常路径:先进了「媒体库」tab 根,再点进子页面——根还在栈上,点回 tab 退回它。同一个 tab
     *  内部退到根,不需要把子页面存起来给谁恢复(`saveState = false`)。 */
    @Test fun `子页面点回自己所在tab时根在栈上就退回根`() {
        val stackViaLibraryRoot = listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES_DETAIL_PATTERN)
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY, saveState = false),
            tabNavAction(Routes.SERIES_DETAIL_PATTERN, Routes.LIBRARY, stackViaLibraryRoot),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY, saveState = false),
            tabNavAction(
                Routes.COLLECTION_DETAIL_PATTERN,
                Routes.LIBRARY,
                listOf(Routes.HOME, Routes.LIBRARY, Routes.COLLECTION_DETAIL_PATTERN),
            ),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY, saveState = false),
            tabNavAction(
                Routes.LIBRARY_VIEW_PATTERN,
                Routes.LIBRARY,
                listOf(Routes.HOME, Routes.LIBRARY, Routes.LIBRARY_VIEW_PATTERN),
            ),
        )
    }

    /**
     * **Fix round 3 的回归用例。** 首页「我的媒体」库卡片直连 `Routes.libraryView(id)`
     * (`JellyCastNavHost.kt` 的 `onLibraryClick`),从未 push 过 `"library"` 本身——这条路径上
     * `"library"` 根本不在栈里。点『媒体库』tab 必须走 [TabNavAction.SwitchTab](一次全新
     * push,能正常落地在列表上),不能返回 [TabNavAction.PopToTabRoot]——那样执行方
     * `popBackStack("library", inclusive = false)` 会找不到目标、返回 `false`、不改动任何东西,
     * 点了没反应,和"点自己所在 tab 是 no-op"是同一种症状。
     *
     * 同时覆盖从 `library/view/{id}` 再往下钻进 `library/series/{id}` 的情形——`"library"`
     * 依然不在栈里,结论不变。
     */
    @Test fun `子页面所属tab根不在栈上时跨tab切换而不是退回根`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.LIBRARY),
            tabNavAction(
                currentRoute = Routes.LIBRARY_VIEW_PATTERN,
                tabRoute = Routes.LIBRARY,
                stackRoutes = listOf(Routes.HOME, Routes.LIBRARY_VIEW_PATTERN),
            ),
        )
        assertEquals(
            TabNavAction.SwitchTab(Routes.LIBRARY),
            tabNavAction(
                currentRoute = Routes.SERIES_DETAIL_PATTERN,
                tabRoute = Routes.LIBRARY,
                stackRoutes = listOf(Routes.HOME, Routes.LIBRARY_VIEW_PATTERN, Routes.SERIES_DETAIL_PATTERN),
            ),
        )
    }

    /**
     * **Fix round 4 的回归用例。** `HOME` 永远在栈底(`inclusive = false` 的 pop 从不把它算在被
     * 弹的范围内),点「在听」必须无条件走 [TabNavAction.PopToTabRoot]`(saveState = true)`,
     * **不能**走 [TabNavAction.SwitchTab]——那条路径会调用
     * `navigate(HOME){popUpTo(HOME){saveState=true};restoreState=true}`,而
     * `navigate()`/`restoreState` 这一套在目标就是 `popUpTo` 锚点本身(`HOME`)时会自我循环:
     * 刚存下的那段被同一次调用的 `restoreState` 立刻恢复回去,点击变成 no-op——复审在真实路由表
     * 上实测过(`home → library/view/lib1` 点『在听』,返回栈点击前后逐字节不变,entry id 都
     * 没变)。改用 `popBackStack(HOME, inclusive=false, saveState=true)`,不经过
     * `navigate()`/`restoreState`,天然不会有这个问题(同样实测过,见
     * `ListScrollRestoreTest`)。
     *
     * 覆盖三种不同的"当前站在哪":经过 `library` 根的详情页、从首页库卡片直连的
     * `library/view/{id}`(中途未经过 `library`)、以及 `settings`。三种情形下点「在听」
     * 都应该得到同一个结论。
     */
    @Test fun `点在听时无条件退回home并保存离开的这一段`() {
        val viaLibraryRoot = listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES_DETAIL_PATTERN)
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.HOME, saveState = true),
            tabNavAction(Routes.SERIES_DETAIL_PATTERN, Routes.HOME, viaLibraryRoot),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.HOME, saveState = true),
            tabNavAction(
                currentRoute = Routes.LIBRARY_VIEW_PATTERN,
                tabRoute = Routes.HOME,
                stackRoutes = listOf(Routes.HOME, Routes.LIBRARY_VIEW_PATTERN),
            ),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.HOME, saveState = true),
            tabNavAction(
                currentRoute = Routes.SETTINGS,
                tabRoute = Routes.HOME,
                stackRoutes = listOf(Routes.HOME, Routes.SETTINGS),
            ),
        )
    }

    @Test fun `library的子页面点设置是跨tab切换`() {
        val stack = listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES_DETAIL_PATTERN)
        assertEquals(
            TabNavAction.SwitchTab(Routes.SETTINGS),
            tabNavAction(Routes.SERIES_DETAIL_PATTERN, Routes.SETTINGS, stack),
        )
    }

    /** `servers` 不挂在 `"settings/"` 前缀下(它同时也是登录前的顶层路由),不算「设置」的子页面。 */
    @Test fun `servers不算settings的子页面`() {
        val stack = listOf(Routes.HOME, Routes.SETTINGS, Routes.SERVERS)
        assertEquals(TabNavAction.SwitchTab(Routes.SETTINGS), tabNavAction(Routes.SERVERS, Routes.SETTINGS, stack))
        assertEquals(
            TabNavAction.SwitchTab(Routes.SETTINGS),
            tabNavAction(Routes.SERVERS_ADD, Routes.SETTINGS, stack + Routes.SERVERS_ADD),
        )
    }

    /** 前缀匹配必须带上分隔符——不能因为字符串"长得像"就误判归属(比如假想的 `libraryx` 路由),
     *  即便栈里真的有 `"library"`。 */
    @Test fun `前缀匹配不能没有分隔符就命中`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.LIBRARY),
            tabNavAction(
                currentRoute = "libraryx",
                tabRoute = Routes.LIBRARY,
                stackRoutes = listOf(Routes.HOME, Routes.LIBRARY, "libraryx"),
            ),
        )
    }

    /** 导航还没就绪时 `currentBackStackEntryAsState()` 可能给出 `null`——不能崩,按"跨 tab 切换"处理。 */
    @Test fun `currentRoute为null时按跨tab切换处理`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.HOME),
            tabNavAction(currentRoute = null, tabRoute = Routes.HOME, stackRoutes = emptyList()),
        )
    }

    // ---- restorableTabOwner ----

    @Test fun `栈里有library根就选library`() {
        val stack = listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES_DETAIL_PATTERN)
        assertEquals(Routes.LIBRARY, restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)))
    }

    @Test fun `栈里有settings根就选settings`() {
        val stack = listOf(Routes.HOME, Routes.SETTINGS, Routes.SERVERS)
        assertEquals(Routes.SETTINGS, restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)))
    }

    /**
     * **Critical 2 / Important 3 的回归用例。** 首页「我的媒体」库卡片直接跳
     * `library/view/{libraryId}`,中途从未压过 `"library"` 本身——栈里找不到 `library`/`settings`
     * 这两个更具体的根,只有栈底的 `home`。`restorableTabOwner` 老实返回 `home`(不是假装找到了
     * 一个 `library`),调用方对 `home` 做 `popBackStack(inclusive = false)`(默认不
     * `saveState`)会把 `library/view/{libraryId}` 干净弹掉、销毁——不会变成一段以后没有任何
     * `navigate()` 会去恢复、`ViewModelStore` 永远出不来的僵尸状态。
     */
    @Test fun `library的子页面直接从home进入时栈里没有library根只能退到home`() {
        val stack = listOf(Routes.HOME, Routes.LIBRARY_VIEW_PATTERN)
        assertEquals(Routes.HOME, restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)))
    }

    /** 只看 `library`/`settings` 这两个更具体的候选(不含 `HOME`)时,库详情的直连场景确实
     *  一个候选都不占,验证 `null` 分支本身没写错。 */
    @Test fun `候选列表不含home时找不到就是null`() {
        val stack = listOf(Routes.HOME, Routes.LIBRARY_VIEW_PATTERN)
        assertNull(restorableTabOwner(stack, listOf(Routes.LIBRARY, Routes.SETTINGS)))
    }

    /**
     * **Important 4 的回归用例。** `servers` 单独出现在栈里(`settings -> servers`)时,
     * `settings` 比栈底的 `home` 更靠近栈顶,先命中的应该是 `settings`,不是让 `servers` 那段被
     * 一起存下——调用方在 `popBackStack(owner, inclusive = false)` 时会把 `servers` 弹掉
     * (不保存)、只留 `settings`。
     */
    @Test fun `settings下的servers不会被当成可恢复的根`() {
        val stack = listOf(Routes.HOME, Routes.SETTINGS, Routes.SERVERS)
        val owner = restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS))
        assertEquals(Routes.SETTINGS, owner)
        assertEquals(false, Routes.SERVERS == owner)
    }

    @Test fun `已经站在home自己栈里只有home时选home`() {
        val stack = listOf(Routes.HOME)
        assertEquals(Routes.HOME, restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)))
    }

    /** 栈里 `library` 比 `home` 更靠近栈顶——即便候选列表里 `home` 排在前面,选中的也必须是
     *  离当前位置更近的 `library`,不是候选列表里"顺序更靠前"的那个。这是"从栈顶找"和"看
     *  候选列表谁先出现在栈里"这两种(错误)实现唯一会给出不同答案的地方。 */
    @Test fun `候选顺序不影响结果只看谁离栈顶更近`() {
        val stack = listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES_DETAIL_PATTERN)
        assertEquals(Routes.LIBRARY, restorableTabOwner(stack, listOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)))
        assertEquals(Routes.LIBRARY, restorableTabOwner(stack, listOf(Routes.LIBRARY, Routes.HOME, Routes.SETTINGS)))
    }
}
