package dev.insua.jellycast.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.OfflineBanner
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl

/** 定位节点用的测试标签,不依赖文案(文案会改)。 */
object HomeScreenTestTags {
    const val ERROR_RETRY = "home_error_retry"
}

/**
 * "在听"首页。四个分区自上而下:继续收听 / 下一集 / 最近添加 / 我的媒体——下一集是追剧主入口,
 * 紧跟在继续收听之后,点开就能直接播放,呼应设计文档"3 次点击内开始播放下一集"的成功标准。
 * 全程不渲染视频画面,封面用 [PosterCard](海报卡,内部走 Coil AsyncImage)。
 *
 * [onLibraryClick] 点击「我的媒体」的库卡片时回调库 id,交给调用方(导航层)决定跳去哪个库——
 * 这里不认识具体的路由。
 *
 * [baseUrl] 是当前激活服务器的接入地址,用于拼封面 URL([dev.insua.jellycast.network.mapper.posterUrl]);
 * 默认空串——:feature:home 目前还没有接入"当前激活服务器"的会话解析(Task 22 导航装配的职责),
 * 空串时不拼 URL,PosterCard 走占位背景兜底,不会拼出一个必 404 的地址。
 */
@Composable
fun HomeScreen(
    onItemClick: (MediaItem) -> Unit,
    onLibraryClick: (String) -> Unit = {},
    baseUrl: String = "",
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // 「这一次浏览会话里已经看过离线提示了」是页面级状态,不进 ViewModel:重新进入首页
    // (或点重试重新加载)时提示该重新出现,而不是被永久关掉。
    var offlineNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        // 显示的是上次的内容:提示一句,但绝不挡住内容本身,也允许用户关掉。
        //
        // ⚠️ **必须放在 LazyColumn 外面。** 放进去(`item(key = "home_offline")`)会踩到懒列表的
        // 锚定行为:分区是先到的,提示条是后到的(网络失败晚于读缓存),于是这一项是被**插入到
        // 已有项之前**的。LazyListState 带 key 时会保持原来那一项的位置不动,新插入的项因此
        // 落在视口**上方** —— 它确实被组合出来了,用户却要往上滑才看得见。真机验证时正是这个
        // 现象:日志里 isOffline=true、屏幕上什么都没有。
        if (uiState.isOffline && uiState.sections.isNotEmpty() && !offlineNoticeDismissed) {
            OfflineBanner(onDismiss = { offlineNoticeDismissed = true })
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp), // 给底部常驻的 MiniPlayerBar 让位
        ) {
            // 没缓存又连不上服务器:给一个可点的重试行,而不是一片什么都没有的白屏。
            // 只要有任何一个分区有内容(哪怕是缓存),error 就是 null,这一行不会盖住它们。
            uiState.error?.let { message ->
                item(key = "home_error") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 重试是一次全新的加载,离线提示的"已看过"状态跟着复位。
                                offlineNoticeDismissed = false
                                viewModel.retry()
                            }
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                            .testTag(HomeScreenTestTags.ERROR_RETRY),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "点击重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            for (section in uiState.sections) {
                item(key = section.kind) {
                    HomeSectionRow(
                        section = section,
                        baseUrl = baseUrl,
                        // 「我的媒体」的卡片点的是库,不是可播放条目——回调换成 onLibraryClick,
                        // 其余分区(继续收听/下一集/最近添加)行为不变。
                        onItemClick = { mediaItem ->
                            if (section.kind == HomeSectionKind.LIBRARIES) {
                                onLibraryClick(mediaItem.id)
                            } else {
                                onItemClick(mediaItem)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRow(section: HomeSection, baseUrl: String, onItemClick: (MediaItem) -> Unit) {
    // 下一集是追剧主入口(设计文档"3 次点击内开始播放下一集"的成功标准),标题用主题色 + 加粗
    // 突出显示,和另外分区区分开——只是排版上的强调,不改变布局结构。
    val isPrimarySection = section.kind == HomeSectionKind.NEXT_UP
    Text(
        text = section.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (isPrimarySection) FontWeight.Bold else FontWeight.Normal,
        color = if (isPrimarySection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(section.items, key = { it.id }) { mediaItem ->
            PosterCard(
                title = mediaItem.name,
                subtitle = mediaItem.displaySubtitle.ifBlank { null },
                imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                onClick = { onItemClick(mediaItem) },
                // 只有继续收听分区传听过比例——下一集/最近添加/我的媒体不是"进行中"的语义,
                // 保持不传,PosterCard 收到 null 就什么都不画。
                progress = if (section.kind == HomeSectionKind.RESUME) mediaItem.resumeProgressFraction() else null,
            )
        }
    }
}

/**
 * "听过比例"的纯换算:输入输出全是毫秒/Float,不接触 ticks、不依赖 Android,可离线单测。
 * 三种退化情形都返回 null(= PosterCard 不画进度条),而不是画一条 0 宽或除零崩溃的条:
 * - 没有总时长(点播列表接口偶尔不带 RunTimeTicks)
 * - 总时长是 0
 * - 一秒都没听过(resumePositionMs == 0,不算"进行中")
 * 服务端上报的位置理论上不会超过总时长,但客户端仍要钳制到 1f,不能画出溢出的进度条。
 */
internal fun MediaItem.resumeProgressFraction(): Float? {
    val totalMs = runTimeMs
    if (totalMs == null || totalMs <= 0L || resumePositionMs <= 0L) return null
    return (resumePositionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}
