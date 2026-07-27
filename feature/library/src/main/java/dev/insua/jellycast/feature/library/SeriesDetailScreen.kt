package dev.insua.jellycast.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
            )
        }
    }
}
