package dev.insua.jellycast.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 一次性动作反馈(收藏/已看乐观更新失败回滚时的提示)的统一落地方式:一条 Snackbar,
 * 显示一次就消费掉。**不是** [androidx.compose.material3.Scaffold] 的 `snackbarHost`——
 * 这几个屏幕([dev.insua.jellycast.feature.library.LibraryScreen] 等)已经被
 * `JellyCastNavHost` 的 Scaffold 托管,再嵌一层 Scaffold 会引入不必要的 padding 计算,
 * 所以就是一个可以摆在任意 [Box] 里的独立 host。
 *
 * [message] 非 null 时显示一次,显示完(或被顶掉)调用 [onMessageShown] 清空——调用方据此把
 * 状态里的消息字段置 null,不然屏幕旋转/重组会重复弹出同一条。
 */
@Composable
fun ActionMessageHost(message: String?, onMessageShown: () -> Unit, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        hostState.showSnackbar(text)
        onMessageShown()
    }

    Box(
        modifier = modifier.fillMaxSize().padding(bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        SnackbarHost(hostState)
    }
}
