package dev.insua.jellycast.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.OfflineBanner
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.mapper.posterUrl

/**
 * 合集(BoxSet)详情:一份条目列表,电影/剧集混排,没有"季"这一层结构(区别于
 * [SeriesDetailScreen])。电影条目直接触发 [onPlay];剧集条目导航去既有的
 * [SeriesDetailScreen](经 [onSeriesClick]),不在这里重复展开季/集。
 */
@Composable
fun CollectionDetailScreen(
    collectionId: String,
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(collectionId) { viewModel.openCollection(collectionId) }

    val detail = uiState.collectionDetail ?: return

    var offlineNoticeDismissed by rememberSaveable(collectionId) { mutableStateOf(false) }

    Column {
        detail.error?.let { message ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        offlineNoticeDismissed = false
                        viewModel.retryCollection()
                    }
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text("点击重试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (detail.isOffline && detail.items.isNotEmpty() && !offlineNoticeDismissed) {
            OfflineBanner(onDismiss = { offlineNoticeDismissed = true })
        }

        if (detail.isLoading && detail.items.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            ) {
                CircularProgressIndicator()
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(detail.items, key = { it.id }) { mediaItem ->
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
