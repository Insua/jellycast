package dev.insua.jellycast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.insua.jellycast.designsystem.JellyCastTheme
import dev.insua.jellycast.navigation.JellyCastNavHost
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 复审 Finding 1:`MediaController` 会随会话释放而永久断开(暂停 → 从最近任务划掉 App →
     * `MediaSessionService.onTaskRemoved` 在没在播放时 `stopSelf` → `onDestroy` 里会话被 release)。
     * `onStart` 是 Media3 推荐的 controller 生命周期时机,也是"划掉 App 再重开"这条路径上最早能把
     * 传输控制恢复过来的点(用户还没点任何按钮)。
     *
     * 注入具体实现而不是 `PlayerConnection` 接口:"确保连着"是这个实现的内部职责,不该污染
     * `:feature:player` 的接口——那一层只该看到一个 `Player?`。
     */
    @Inject
    lateinit var playerConnection: MediaControllerPlayerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JellyCastTheme {
                JellyCastNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playerConnection.ensureConnected()
    }
}
