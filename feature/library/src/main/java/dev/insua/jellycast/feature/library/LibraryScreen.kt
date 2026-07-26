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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.insua.jellycast.designsystem.PosterCard
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.mapper.posterUrl

/** 滚动到距列表底部还剩这么多项时就预取下一页(设计文档 §3.1)。 */
private const val PREFETCH_THRESHOLD = 10

/** [LibraryScreenTest] 用来定位节点的测试标签,不依赖文案(文案会改)。 */
object LibraryScreenTestTags {
    const val SEARCH_FIELD = "library_search_field"
    const val SEARCH_CLEAR_BUTTON = "library_search_clear_button"
    const val TAB_ROW = "library_tab_row"
    const val LOADING_INITIAL = "library_loading_initial"
    const val LOADING_MORE = "library_loading_more"
    const val ERROR_ROW = "library_error_row"
    const val EMPTY_STATE = "library_empty_state"

    fun item(id: String) = "library_item_$id"
}

/**
 * 媒体库:剧集 / 电影两个 Tab,支持分页加载与搜索。剧集条目点进去是
 * [SeriesDetailScreen](经 [onSeriesClick]);电影条目本身就是完整可播放单元,
 * 直接触发 [onPlay]。全程不渲染视频画面,封面走 [PosterCard]。
 *
 * 真正的界面在 [LibraryScreenContent](纯函数、无 ViewModel 依赖)——这里只负责
 * 从 [LibraryViewModel] 取 `uiState` 并把回调转接过去,方便 Compose UI 测试直接
 * 喂一个手工构造的 [LibraryUiState] 而不必拉起真的 ViewModel/Hilt/网络。
 */
@Composable
fun LibraryScreen(
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
    baseUrl: String = "",
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LibraryScreenContent(
        uiState = uiState,
        baseUrl = baseUrl,
        onQueryChange = viewModel::onQueryChange,
        onSelectTab = viewModel::selectTab,
        onLoadNextPage = viewModel::loadNextPage,
        onRetry = viewModel::retry,
        onSeriesClick = onSeriesClick,
        onPlay = onPlay,
    )
}

/**
 * 分页由 [uiState] 的 `visible: PageState<MediaItem>` 驱动:列表滚动到接近底部时调用
 * [onLoadNextPage],失败时展示重试行(调用 [onRetry])——
 * [dev.insua.jellycast.model.PageState.onError] 已保证失败不清空已加载内容,这里只负责
 * 把状态渲染出来,渲染优先级严格对应设计文档 §3.4 的状态表:
 *
 * 1. `isLoading` 为 true:底部/首屏转圈(转圈与错误互斥,`PageState` 保证不会同时为 true)
 * 2. `error != null`:整行可点的重试行,**已加载的 items 照常渲染在它上面,绝不清空**
 * 3. `loadedCount == 0`:空态文案,按 `isSearching` 区分「没有匹配」/「库还没有内容」
 * 4. 都不是:只有 items,没有底部状态行
 */
@Composable
fun LibraryScreenContent(
    uiState: LibraryUiState,
    baseUrl: String = "",
    onQueryChange: (String) -> Unit,
    onSelectTab: (LibraryTab) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val visible = uiState.visible

    Column {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            label = { Text("搜索剧集或电影") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag(LibraryScreenTestTags.SEARCH_CLEAR_BUTTON),
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "清除搜索词")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(LibraryScreenTestTags.SEARCH_FIELD),
        )

        if (!uiState.isSearching) {
            TabRow(
                selectedTabIndex = uiState.tab.ordinal,
                modifier = Modifier.testTag(LibraryScreenTestTags.TAB_ROW),
            ) {
                Tab(
                    selected = uiState.tab == LibraryTab.SERIES,
                    onClick = { onSelectTab(LibraryTab.SERIES) },
                    text = { Text("剧集") },
                )
                Tab(
                    selected = uiState.tab == LibraryTab.MOVIES,
                    onClick = { onSelectTab(LibraryTab.MOVIES) },
                    text = { Text("电影") },
                )
            }
        }

        val gridState = rememberLazyGridState()

        // 预取协程只启动一次(key 是稳定的 gridState),所以**绝不能**直接闭包捕获 visible /
        // onLoadNextPage:那样捕获到的永远是首次组合时的那份值——首次组合时第一页还没到货,
        // items.size 恒为 0,`total > 0` 永远不成立,无限滚动直接死掉。
        // rememberUpdatedState 每次重组都把最新值写进同一个 State,而 snapshotFlow 会订阅
        // 块内读到的 State,于是"已加载条目数变化"本身也能重新驱动一次判定。
        val currentVisible by rememberUpdatedState(visible)
        val currentOnLoadNextPage by rememberUpdatedState(onLoadNextPage)

        // 滚动到接近列表底部时自动加载下一页;loadNextPage 内部已挡住重复触发。
        LaunchedEffect(gridState) {
            snapshotFlow {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to currentVisible.items.size
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
            modifier = Modifier,
        ) {
            items(visible.items, key = { it.id }) { mediaItem ->
                PosterCard(
                    title = mediaItem.name,
                    subtitle = if (uiState.isSearching) mediaItem.kind.searchSubtitle() else null,
                    imageUrl = if (baseUrl.isBlank()) null else mediaItem.posterUrl(baseUrl),
                    onClick = {
                        if (mediaItem.kind == MediaKind.SERIES) onSeriesClick(mediaItem.id) else onPlay(mediaItem)
                    },
                    modifier = Modifier.testTag(LibraryScreenTestTags.item(mediaItem.id)),
                )
            }

            when {
                visible.isLoading -> {
                    val tag = if (visible.loadedCount > 0) {
                        LibraryScreenTestTags.LOADING_MORE
                    } else {
                        LibraryScreenTestTags.LOADING_INITIAL
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

                visible.error != null -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onRetry)
                                .padding(16.dp)
                                .testTag(LibraryScreenTestTags.ERROR_ROW),
                        ) {
                            Text("加载失败,点击重试", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                visible.loadedCount == 0 -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (uiState.isSearching) "没有匹配的剧集或电影" else "这个库还没有内容",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(LibraryScreenTestTags.EMPTY_STATE),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 搜索结果混排时用来在副标题区分「剧集」/「电影」——浏览态(单一 Tab)下不需要。 */
private fun MediaKind.searchSubtitle(): String? = when (this) {
    MediaKind.SERIES -> "剧集"
    MediaKind.MOVIE -> "电影"
    else -> null
}
