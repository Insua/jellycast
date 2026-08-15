package dev.insua.jellycast.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `tabNavAction` / `restorableTabOwner` 是纯函数,离线可单测(项目铁律 6)——这是 Fix round 2
 * 对复审 Important 5("变异证据只证明了镜像,没证明生产函数")的直接回应:这里测的、下面
 * mutation 验证要打的,都是 `TabNavAction.kt` 里的**生产代码本身**,不是 Compose 测试里搭的镜像。
 *
 * 表驱动覆盖三个 tab 根(`home`/`library`/`settings`)、`library` 的三种子路由模式
 * (`series`/`collection`/`view`)、非 CHROME_ROUTES 的 `servers`,以及 `null`(导航还没就绪时
 * `currentRoute` 的取值)。
 */
class TabNavActionTest {

    @Test fun `已经站在tab根上什么都不做`() {
        assertEquals(TabNavAction.None, tabNavAction(currentRoute = Routes.HOME, tabRoute = Routes.HOME))
        assertEquals(TabNavAction.None, tabNavAction(currentRoute = Routes.LIBRARY, tabRoute = Routes.LIBRARY))
        assertEquals(TabNavAction.None, tabNavAction(currentRoute = Routes.SETTINGS, tabRoute = Routes.SETTINGS))
    }

    @Test fun `子页面点回自己所在tab退回根`() {
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY),
            tabNavAction(currentRoute = Routes.SERIES_DETAIL_PATTERN, tabRoute = Routes.LIBRARY),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY),
            tabNavAction(currentRoute = Routes.COLLECTION_DETAIL_PATTERN, tabRoute = Routes.LIBRARY),
        )
        assertEquals(
            TabNavAction.PopToTabRoot(Routes.LIBRARY),
            tabNavAction(currentRoute = Routes.LIBRARY_VIEW_PATTERN, tabRoute = Routes.LIBRARY),
        )
    }

    /**
     * **Critical 1 的回归用例。** `HOME` 永远在栈底,子页面点「在听」必须走跨 tab 切换
     * (`SwitchTab`,带 `saveState=true`),不能被误判成"属于 home 的子页面"从而退回根——
     * `HOME` 根本没有任何子路由,`popBackStack("home", ...)` 对它之上的一切都会"成功"但那不代表
     * 归属关系。
     */
    @Test fun `在库详情页点在听是跨tab切换不是退回home根`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.HOME),
            tabNavAction(currentRoute = Routes.SERIES_DETAIL_PATTERN, tabRoute = Routes.HOME),
        )
        assertEquals(
            TabNavAction.SwitchTab(Routes.HOME),
            tabNavAction(currentRoute = Routes.LIBRARY_VIEW_PATTERN, tabRoute = Routes.HOME),
        )
    }

    @Test fun `library的子页面点设置或在听都是跨tab切换`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.SETTINGS),
            tabNavAction(currentRoute = Routes.SERIES_DETAIL_PATTERN, tabRoute = Routes.SETTINGS),
        )
    }

    /** `servers` 不挂在 `"settings/"` 前缀下(它同时也是登录前的顶层路由),不算「设置」的子页面。 */
    @Test fun `servers不算settings的子页面`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.SETTINGS),
            tabNavAction(currentRoute = Routes.SERVERS, tabRoute = Routes.SETTINGS),
        )
        assertEquals(
            TabNavAction.SwitchTab(Routes.SETTINGS),
            tabNavAction(currentRoute = Routes.SERVERS_ADD, tabRoute = Routes.SETTINGS),
        )
    }

    /** 前缀匹配必须带上分隔符——不能因为字符串"长得像"就误判归属(比如假想的 `libraryx` 路由)。 */
    @Test fun `前缀匹配不能没有分隔符就命中`() {
        assertEquals(
            TabNavAction.SwitchTab(Routes.LIBRARY),
            tabNavAction(currentRoute = "libraryx", tabRoute = Routes.LIBRARY),
        )
    }

    /** 导航还没就绪时 `currentBackStackEntryAsState()` 可能给出 `null`——不能崩,按"跨 tab 切换"处理。 */
    @Test fun `currentRoute为null时按跨tab切换处理`() {
        assertEquals(TabNavAction.SwitchTab(Routes.HOME), tabNavAction(currentRoute = null, tabRoute = Routes.HOME))
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
