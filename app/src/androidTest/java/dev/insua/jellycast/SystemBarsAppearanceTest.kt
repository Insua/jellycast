package dev.insua.jellycast

import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设计文档 §1:这个应用只有浅色主题,状态栏图标必须是**深色**的。
 *
 * 缺陷现场:`Theme.JellyCast` 继承 `android:Theme.Material.NoActionBar`(深色主题),
 * 系统据此判定"应用背景是深的"而画**浅色**图标 —— 于是白色的电池/信号/时间落在浅色界面上,
 * 几乎看不清。而代码里从来没有任何地方声明过状态栏外观。
 *
 * ⚠️ 这是**平台行为,JVM 单测结构上测不出**(v4 立下的铁律)。必须在真实 Activity 上取
 * `WindowInsetsController` 的实际状态。
 *
 * `@HiltAndroidTest` + `HiltAndroidRule` 是必需的,不是抄样板:`MainActivity` 是
 * `@AndroidEntryPoint`,instrumentation 进程跑的是 `CustomTestRunner` 换上的
 * `HiltTestApplication`(见该文件注释)。没有这条 rule,`ActivityScenario.launch` 会在
 * `onCreate` 里因为 Hilt 测试组件从未被创建而直接崩(`IllegalStateException: The
 * component was not created. Check that you have added the HiltAndroidRule.`)——
 * 这在本仓库其它 androidTest(`PlaybackE2eTest`/`OfflineE2eTest`)里是既定模式。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SystemBarsAppearanceTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Test
    fun 状态栏图标是深色的() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertTrue(
                    "状态栏图标必须是深色(isAppearanceLightStatusBars=true) —— " +
                        "这个应用只有浅色主题,浅色图标会和界面撞色看不清。",
                    controller.isAppearanceLightStatusBars,
                )
            }
        }
    }

    @Test
    fun 导航栏图标是深色的() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertTrue(
                    "导航栏图标同理必须是深色。",
                    controller.isAppearanceLightNavigationBars,
                )
            }
        }
    }
}
