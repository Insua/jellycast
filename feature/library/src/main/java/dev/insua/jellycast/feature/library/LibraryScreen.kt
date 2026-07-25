package dev.insua.jellycast.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.mapper.posterUrl

/**
 * 媒体库:剧集 / 电影两个 Tab。剧集条目点进去是 [SeriesDetailScreen](经 [onSeriesClick]);
 * 电影条目本身就是完整可播放单元,直接触发 [onPlay]。全程不渲染视频画面,封面走 [PosterCard]。
 */
@Composable
fun LibraryScreen(
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Column {
        TabRow(selectedTabIndex = uiState.tab.ordinal) {
            Tab(
                selected = uiState.tab == LibraryTab.SERIES,
                onClick = { viewModel.selectTab(LibraryTab.SERIES) },
                text = { Text("剧集") },
            )
            Tab(
                selected = uiState.tab == LibraryTab.MOVIES,
                onClick = { viewModel.selectTab(LibraryTab.MOVIES) },
                text = { Text("电影") },
            )
        }

        val items = when (uiState.tab) {
            LibraryTab.SERIES -> uiState.series
            LibraryTab.MOVIES -> uiState.movies
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier,
        ) {
            items(items, key = { it.id }) { mediaItem ->
                PosterCard(
                    title = mediaItem.name,
                    subtitle = null,
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = {
                        if (mediaItem.kind == MediaKind.SERIES) onSeriesClick(mediaItem.id) else onPlay(mediaItem)
                    },
                )
            }
        }
    }
}
