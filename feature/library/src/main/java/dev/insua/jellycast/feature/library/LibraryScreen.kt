package dev.insua.jellycast.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.mapper.posterUrl

/** 滚动到距列表底部还剩这么多项时就预取下一页(设计文档 §3.1)。 */
private const val PREFETCH_THRESHOLD = 10

/**
 * 媒体库:剧集 / 电影两个 Tab,支持分页加载与搜索。剧集条目点进去是
 * [SeriesDetailScreen](经 [onSeriesClick]);电影条目本身就是完整可播放单元,
 * 直接触发 [onPlay]。全程不渲染视频画面,封面走 [PosterCard]。
 *
 * 分页由 [LibraryViewModel.uiState] 的 `visible: PageState<MediaItem>` 驱动:
 * 列表滚动到接近底部时调用 [LibraryViewModel.loadNextPage],失败时展示重试按钮
 * (调用 [LibraryViewModel.retry])——[dev.insua.jellycast.model.PageState.onError]
 * 已保证失败不清空已加载内容,这里只负责把 error 状态渲染出来。
 */
@Composable
fun LibraryScreen(
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val visible = uiState.visible

    Column {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("搜索剧集或电影") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (!uiState.isSearching) {
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
        }

        val gridState = rememberLazyGridState()

        // 滚动到接近列表底部时自动加载下一页;loadNextPage 内部已挡住重复触发。
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisibleIndex ->
                    val total = viewModel.uiState.value.visible.items.size
                    if (lastVisibleIndex != null && total > 0 && lastVisibleIndex >= total - PREFETCH_THRESHOLD) {
                        viewModel.loadNextPage()
                    }
                }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier,
        ) {
            items(visible.items, key = { it.id }) { mediaItem ->
                PosterCard(
                    title = mediaItem.name,
                    subtitle = null,
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = {
                        if (mediaItem.kind == MediaKind.SERIES) onSeriesClick(mediaItem.id) else onPlay(mediaItem)
                    },
                )
            }

            if (visible.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text("加载失败:${visible.error}", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text("重试") }
                    }
                }
            }
        }
    }
}
