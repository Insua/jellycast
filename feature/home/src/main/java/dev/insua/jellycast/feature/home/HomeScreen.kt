package dev.insua.jellycast.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl

/**
 * "在听"首页。三个分区自上而下:继续收听 / 下一集 / 最近添加——下一集是追剧主入口,紧跟在
 * 继续收听之后,点开就能直接播放,呼应设计文档"3 次点击内开始播放下一集"的成功标准。
 * 全程不渲染视频画面,封面用 [PosterCard](海报卡,内部走 Coil AsyncImage)。
 *
 * [baseUrl] 是当前激活服务器的接入地址,用于拼封面 URL([dev.insua.jellycast.network.mapper.posterUrl]);
 * 默认空串——:feature:home 目前还没有接入"当前激活服务器"的会话解析(Task 22 导航装配的职责),
 * 空串时不拼 URL,PosterCard 走占位背景兜底,不会拼出一个必 404 的地址。
 */
@Composable
fun HomeScreen(
    onItemClick: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 96.dp), // 给底部常驻的 MiniPlayerBar 让位
    ) {
        for (section in uiState.sections) {
            item(key = section.kind) {
                HomeSectionRow(section = section, baseUrl = baseUrl, onItemClick = onItemClick)
            }
        }
    }
}

@Composable
private fun HomeSectionRow(section: HomeSection, baseUrl: String, onItemClick: (MediaItem) -> Unit) {
    // 下一集是追剧主入口(设计文档"3 次点击内开始播放下一集"的成功标准),标题用主题色 + 加粗
    // 突出显示,和另外两个分区(继续收听/最近添加)区分开——只是排版上的强调,不改变布局结构。
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
                // 只有继续收听分区传听过比例——下一集/最近添加不是"进行中"的语义,保持不传,
                // PosterCard 收到 null 就什么都不画。
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
