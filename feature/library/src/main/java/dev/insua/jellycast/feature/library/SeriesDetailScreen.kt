package dev.insua.jellycast.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.Box
import dev.insua.jellycast.designsystem.ActionMessageHost
import dev.insua.jellycast.designsystem.OfflineBanner
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.mapper.posterUrl

/**
 * 剧集详情:季选择 + 当前季的完整集列表。
 *
 * 关键交互(自动连播依赖):点某一集播放时,必须把[整季完整集列表][LibraryViewModel.queueFor]
 * 作为播放队列传出去(`onPlay(item, allEpisodesInSeason)`),而不是单独这一集——否则自动连播
 * 静默失效(播完这一集后队列里没有下一集可放)。
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onPlay: (MediaItem, List<MediaItem>) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(seriesId) { viewModel.openSeries(seriesId) }

    val detail = uiState.detail ?: return

    var offlineNoticeDismissed by rememberSaveable(seriesId) { mutableStateOf(false) }

    Box {
    Column {
        // 没缓存又连不上:可点重试,而不是一片空白(设计文档 §3.2 第三行)。
        detail.error?.let { message ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        offlineNoticeDismissed = false
                        viewModel.openSeries(seriesId)
                    }
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text("点击重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        // 显示的是上次的季/集列表:提示一句,但不打断浏览。
        // 和 [dev.insua.jellycast.feature.home.HomeScreen] 同样的理由:**必须在 LazyColumn 外面**,
        // 否则这条后到的提示会被懒列表的锚定行为顶到视口上方,组合出来了却看不见。
        if (detail.isOffline && detail.episodes.isNotEmpty() && !offlineNoticeDismissed) {
            OfflineBanner(onDismiss = { offlineNoticeDismissed = true })
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(detail.seasons, key = { it.id }) { season ->
                        val selected = season.id == detail.selectedSeasonId
                        Text(
                            text = season.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(8.dp)
                                .clickable(enabled = !selected) { viewModel.selectSeason(seriesId, season.id) },
                        )
                    }
                }
            }
            items(detail.episodes, key = { it.id }) { episode ->
                PosterCard(
                    title = episode.name,
                    subtitle = null,
                    imageUrl = if (baseUrl.isBlank()) null else episode.posterUrl(baseUrl),
                    onClick = {
                        // 铁律:整季作为队列传出,自动连播才能工作——不能只传这一集。
                        onPlay(episode, viewModel.queueFor(episode))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    isFavorite = episode.isFavorite,
                    onToggleFavorite = { viewModel.toggleFavorite(episode) },
                    isPlayed = episode.isPlayed,
                    onTogglePlayed = { viewModel.togglePlayed(episode) },
                )
            }
        }
    }

    ActionMessageHost(message = uiState.actionError, onMessageShown = viewModel::consumeActionError)
    }
}
