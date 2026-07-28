package dev.insua.jellycast.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.ActionMessageHost
import dev.insua.jellycast.designsystem.OfflineBanner
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.model.PageState
import dev.insua.jellycast.network.mapper.posterUrl

/** 滚动到距列表底部还剩这么多项时就预取下一页——与 [LibraryScreenContent] 同一个阈值。 */
private const val PREFETCH_THRESHOLD = 10

/** [LibraryContentsScreenTest] 用来定位节点的测试标签,不依赖文案(文案会改)。 */
object LibraryContentsScreenTestTags {
    const val LOADING_INITIAL = "library_view_loading_initial"
    const val LOADING_MORE = "library_view_loading_more"
    const val ERROR_ROW = "library_view_error_row"
    const val EMPTY_STATE = "library_view_empty_state"
    const val OFFLINE_BANNER = "library_view_offline_banner"

    fun item(id: String) = "library_view_item_$id"
}

/**
 * 首页「我的媒体」库卡片点进来的单个库浏览页(修正 §3.1:此前 [dev.insua.jellycast.feature.home.HomeScreen]
 * 没有接上 `onLibraryClick`,也没有对应的路由,点了没反应)。
 *
 * 剧集条目点进去是 [SeriesDetailScreen](经 [onSeriesClick]);电影条目本身就是完整可播放单元,
 * 直接触发 [onPlay]——与 [LibraryScreen]/[CollectionDetailScreen] 同样的分流规则。分页/离线
 * 行为与 [LibraryScreenContent] 一致(见 [LibraryViewModel.openLibrary] 的 KDoc)。
 */
@Composable
fun LibraryContentsScreen(
    libraryId: String,
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(libraryId) { viewModel.openLibrary(libraryId) }

    // 还没轮到这个 libraryId(比如刚从另一个库切换过来,旧状态还没被上面的 LaunchedEffect 替换):
    // 什么都不画,而不是闪一下别的库的内容。
    val libraryView = uiState.libraryView?.takeIf { it.libraryId == libraryId } ?: return

    LibraryContentsScreenContent(
        page = libraryView.page,
        isOffline = libraryView.isOffline,
        baseUrl = baseUrl,
        onLoadNextPage = viewModel::loadLibraryViewNextPage,
        onRetry = viewModel::retryLibraryView,
        onSeriesClick = onSeriesClick,
        onPlay = onPlay,
        onToggleFavorite = viewModel::toggleFavorite,
        onTogglePlayed = viewModel::togglePlayed,
        actionError = uiState.actionError,
        onActionErrorShown = viewModel::consumeActionError,
    )
}

/**
 * 真正的界面(纯函数、无 ViewModel 依赖,方便 Compose UI 测试直接喂 [PageState])——与
 * [LibraryScreenContent] 同样的拆分理由。
 */
@Composable
fun LibraryContentsScreenContent(
    page: PageState<MediaItem>,
    isOffline: Boolean,
    baseUrl: String = "",
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit = {},
    onTogglePlayed: (MediaItem) -> Unit = {},
    actionError: String? = null,
    onActionErrorShown: () -> Unit = {},
) {
    var offlineNoticeDismissed by rememberSaveable { mutableStateOf(false) }

    Box {
    Column {
        if (isOffline && page.loadedCount > 0 && !offlineNoticeDismissed) {
            OfflineBanner(
                onDismiss = { offlineNoticeDismissed = true },
                modifier = Modifier.testTag(LibraryContentsScreenTestTags.OFFLINE_BANNER),
            )
        }

        val gridState = rememberLazyGridState()

        // 见 [LibraryScreenContent]:预取协程只启动一次,必须用 rememberUpdatedState 读最新值,
        // 否则闭包捕获的是首次组合时的空列表,无限滚动直接死掉。
        val currentPage by rememberUpdatedState(page)
        val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)

        LaunchedEffect(gridState) {
            snapshotFlow {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentPage.items.size
            }.collect { (lastVisibleIndex, total) ->
                if (lastVisibleIndex != null && total > 0 && lastVisibleIndex >= total - PREFETCH_THRESHOLD) {
                    currentOnLoadNextPage()
                }
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(page.items, key = { it.id }) { mediaItem ->
                PosterCard(
                    title = mediaItem.name,
                    subtitle = null,
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = {
                        if (mediaItem.kind == MediaKind.SERIES) onSeriesClick(mediaItem.id) else onPlay(mediaItem)
                    },
                    modifier = Modifier.testTag(LibraryContentsScreenTestTags.item(mediaItem.id)),
                    isFavorite = mediaItem.isFavorite,
                    onToggleFavorite = { onToggleFavorite(mediaItem) },
                    isPlayed = mediaItem.isPlayed,
                    onTogglePlayed = { onTogglePlayed(mediaItem) },
                )
            }

            // 状态优先级与 [LibraryScreenContent] 一致:转圈 > 错误行(已加载条目照常渲染在它上面,
            // 绝不清空)> 空态文案。
            when {
                page.isLoading -> {
                    val tag = if (page.loadedCount > 0) {
                        LibraryContentsScreenTestTags.LOADING_MORE
                    } else {
                        LibraryContentsScreenTestTags.LOADING_INITIAL
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(tag),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                }

                page.error != null -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onRetry)
                                .padding(16.dp)
                                .testTag(LibraryContentsScreenTestTags.ERROR_ROW),
                        ) {
                            Text("加载失败,点击重试", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                page.loadedCount == 0 -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "这个库还没有内容",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(LibraryContentsScreenTestTags.EMPTY_STATE),
                            )
                        }
                    }
                }
            }
        }
    }

    ActionMessageHost(message = actionError, onMessageShown = onActionErrorShown)
    }
}
