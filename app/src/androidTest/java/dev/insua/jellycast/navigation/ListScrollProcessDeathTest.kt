package dev.insua.jellycast.navigation

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复审 Fix round 1 —— 候选 (i):进程回收 / Activity 重建。
 *
 * 复审指出这是简报最初怀疑的那个机制("恢复出的索引 + 第一次测量为空")真正能被用户触达的路径:
 * `NavController` 的返回栈(entry id、当前路由)是 `Parcelable`,随 Activity 的
 * `onSaveInstanceState`/`onCreate(Bundle)` 存取,跨"Activity 被系统回收再重建"存活;Compose 的
 * `rememberSaveable`(`LazyGridState` 就是这么保存的)同理。但 `ViewModelStore` **不是**
 * `Parcelable`——它只活在内存里,"配置变更"式的重建(如 [android.app.Activity.recreate])会通过
 * `onRetainNonConfigurationInstance` 把它保下来,但"被系统回收后重新创建"这种真正意义上的
 * "重建"不会。也就是说:返回栈的位置信息活下来了,承载数据的 ViewModel 没活下来——这正是
 * "恢复出保存的索引,却因为第一次测量时列表是空的而被夹到 0"这个机制的必要条件。
 *
 * **不复用 [ListScrollRestoreTest] 里 `createComposeRule()` 隐式创建的空白 Activity**——那个
 * Activity 拿不到外部句柄,没法触发它的重建。这里改用 [ActivityScenario] 显式管理一个真实
 * [ComponentActivity],配合"不保留活动"开发者选项(`always_finish_activities`)——这是
 * Android 官方文档承认的、在测试里确定性复现"系统在后台回收了 Activity"这类场景的标准手段。
 *
 * 复用 [ListScrollRestoreTest.kt] 里的 [TestNavRoot] / [FakeLibraryViewModel] 等(`internal`
 * 可见度,同包):这里要验证的是 Navigation-Compose + Compose 本身的通用行为(返回栈/
 * `rememberSaveable`/`ViewModelStore` 三者在"Activity 被回收重建"下各自的存活情况),
 * 与是否接真实 Jellyfin 服务器无关,复用同一份可观测的假 ViewModel 能省掉真机登录的开销和
 * 不确定性,同时保留 `instanceId` 日志这个"验证机制而非断言机制"的手段。
 */
@RunWith(AndroidJUnit4::class)
class ListScrollProcessDeathTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device by lazy { UiDevice.getInstance(instrumentation) }

    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before
    fun setUp() {
        // 确保从一个干净状态开始——`ActivityScenario.launch` 在这个开发者选项开着的时候会直接
        // 拒绝启动(实测:`IllegalStateException: "Don't keep activities" developer options
        // must be disabled for ActivityScenario`),所以必须先关、等 Activity 启动好了之后,
        // 测试方法内部再手动打开(见下面的用例)。
        runShell("settings put global always_finish_activities 0")
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        // 🔴 无条件恢复——不管上面跑没跑成功,都不能把模拟器留在这个状态里影响别的测试/手工调试。
        runShell("settings put global always_finish_activities 0")
    }

    /**
     * 断言:滚动到 index 40 → 按 Home 键把 App 送到后台(配合上面的开发者选项,系统立刻销毁
     * Activity)→ 从最近任务/桌面图标把它带回前台(触发 `onCreate(savedInstanceState)`,和
     * 用户"划走再点图标重新打开"是同一条真实路径)→ 断言滚动位置。
     *
     * 如果这条路径真的复现了机制 (a):日志会显示 `FakeLibraryViewModel` 的 `instanceId` 变了
     * (数据被重新加载),但 `firstVisibleItemIndex` 一度是"从 40 恢复、又被夹到 0"而不是
     * "本来就是全新的、从未滚动过的 0"——两者从用户可见的最终状态上其实一样,能区分开的证据
     * 只有日志里那条 `LibraryDestination composed: vm=#N items=0` 是否发生在一次 backstack
     * *没有变化*(还是同一个 "library" 路由/条目)的场景下。
     */
    @Test
    fun 进程回收后重新打开App滚动位置丢失() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { s ->
            s.onActivity { it.setContent { TestNavRoot() } }
        }

        compose.onNodeWithTag(BOTTOM_NAV_TAG).performClick()
        compose.waitForIdle()
        compose.waitForLibraryContentSettled()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLL_TARGET_INDEX)
        compose.waitForIdle()

        val before = compose.firstVisibleItemIndex()
        Log.i(TAG, "[进程回收] 滚动到 $SCROLL_TARGET_INDEX 后 firstVisibleItemIndex=$before")
        check(before >= MIN_ACCEPTABLE_INDEX) {
            "前置条件失败:滚动到 index=$SCROLL_TARGET_INDEX 后 firstVisibleItemIndex=$before," +
                "期望 >= $MIN_ACCEPTABLE_INDEX"
        }

        // Activity 已经启动好了,现在才打开"不保留活动"——`ActivityScenario.launch` 之前不能
        // 开着这个选项(见 @Before 的说明)。
        runShell("settings put global always_finish_activities 1")

        Log.i(TAG, "[进程回收] pressHome 前 scenario.state=${scenario?.state}")

        // ── 送到后台,给系统一点时间真的把它销毁 ──
        device.pressHome()
        Thread.sleep(3_000)
        Log.i(TAG, "[进程回收] pressHome 后 scenario.state=${scenario?.state}")

        // ── 带回前台:显式指向同一个 ComponentActivity 类,而不是
        //    `packageManager.getLaunchIntentForPackage`——那个拿到的是清单里真正的启动器
        //    Activity([dev.insua.jellycast.MainActivity]),这里测的是一个手搭的裸壳
        //    ComponentActivity,两者不是同一个,实测直接指向 MainActivity 会因为 Hilt 测试组件
        //    没有走 HiltAndroidRule 初始化而崩溃。 ──
        val context = instrumentation.targetContext
        val launchIntent = Intent(context, ComponentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(launchIntent)

        instrumentation.waitForIdleSync()
        Thread.sleep(2_000)
        compose.waitForIdle()
        Log.i(TAG, "[进程回收] 带回前台后 scenario.state=${scenario?.state}")

        compose.waitForLibraryContentSettled()

        val after = compose.firstVisibleItemIndex()
        Log.i(TAG, "[进程回收] 带回前台后 firstVisibleItemIndex=$after")
        assert(after >= MIN_ACCEPTABLE_INDEX) {
            "进程被回收(不保留活动)后重新打开 App,滚动位置应保持(firstVisibleItemIndex >= " +
                "$MIN_ACCEPTABLE_INDEX),实际 firstVisibleItemIndex=$after"
        }
    }

    /** 把 shell 命令的输出流耗尽——`executeShellCommand` 需要有人读完这个
     *  `ParcelFileDescriptor`,不然命令可能不会真正执行完。 */
    private fun runShell(command: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
