package dev.insua.jellycast.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 离线提示条的文案。测试与各页面共用同一份,避免"改了文案测试还在断旧字符串"。 */
const val OFFLINE_BANNER_MESSAGE = "离线,显示的是上次内容"

/** [OfflineBannerTest] 用来定位节点的测试标签,不依赖文案。 */
object OfflineBannerTestTags {
    const val ROOT = "offline_banner"
    const val DISMISS_BUTTON = "offline_banner_dismiss"
}

/**
 * 顶部一条可关闭的离线提示(设计文档 §3.2 第二行)。
 *
 * ## 为什么是横幅,不是弹窗
 *
 * 离线缓存的全部意义就是"没网也能接着浏览"。用 `AlertDialog` 通报这件事,等于先把用户拦下来
 * 让他确认"你没网",再放他去看那些本来就已经显示好的内容 —— 把刚刚争取到的可用性又还回去了。
 * 所以它是**同一棵布局树里的一行**:底下的列表照常可见、可滚动、可点击。
 *
 * ## 为什么可以关掉
 *
 * 用户知道自己没开 VPN。提示的价值在"解释这些内容为什么可能是旧的",解释过一次就够了;
 * 一条赶不走的横幅只会一直占着首屏。关闭状态由调用方持有([onDismiss]),因为"这一次浏览
 * 会话里已经看过了"是页面级的状态 —— 下次重新加载(或重新进入)时该重新出现。
 *
 * 配色用 `secondaryContainer` 而不是 `error`:离线不是错误,内容是好的,只是旧的。
 * 用红色会让用户以为出了故障。**保持浅色主题,不改动任何全局配色。**
 */
@Composable
fun OfflineBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp)
            .testTag(OfflineBannerTestTags.ROOT),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = OFFLINE_BANNER_MESSAGE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(OfflineBannerTestTags.DISMISS_BUTTON),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭离线提示",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
