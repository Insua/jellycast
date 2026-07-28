package dev.insua.jellycast.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.Cached
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.mapper.toMediaItem
import dev.insua.jellycast.network.repository.CacheBuckets
import dev.insua.jellycast.network.repository.MediaRepository
import dev.insua.jellycast.network.session.JellyfinSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 无缓存又连不上服务器时给用户看的话。不带技术细节——用户能做的只有"重试"。 */
internal const val OFFLINE_MESSAGE = "无法连接服务器"

enum class HomeSectionKind { RESUME, NEXT_UP, RECENTLY_ADDED, LIBRARIES }

private val HomeSectionKind.bucket: String
    get() = when (this) {
        HomeSectionKind.RESUME -> CacheBuckets.HOME_RESUME
        HomeSectionKind.NEXT_UP -> CacheBuckets.HOME_NEXT_UP
        HomeSectionKind.RECENTLY_ADDED -> CacheBuckets.HOME_RECENTLY_ADDED
        HomeSectionKind.LIBRARIES -> CacheBuckets.HOME_LIBRARIES
    }

private val HomeSectionKind.title: String
    get() = when (this) {
        HomeSectionKind.RESUME -> "继续收听"
        HomeSectionKind.NEXT_UP -> "下一集"
        HomeSectionKind.RECENTLY_ADDED -> "最近添加"
        HomeSectionKind.LIBRARIES -> "我的媒体"
    }

data class HomeSection(
    val kind: HomeSectionKind,
    val title: String,
    val items: List<MediaItem>,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
    /** 至少有一个分区这次没刷新成功,屏幕上显示的是上次的内容。用于顶部那条"离线"提示。 */
    val isOffline: Boolean = false,
    /** 四个分区**一个都没拿到数据**(没缓存 + 全部请求失败)时的可重试错误态。 */
    val error: String? = null,
)

/**
 * "在听"首页:继续收听 / 下一集 / 最近添加 / 我的媒体,自上而下。设计文档的成功标准之一是
 * "打开 App 到开始播放'下一集'不超过 3 次点击"——下一集分区是追剧主入口,必须显眼、可直接点播
 * (由 [HomeScreen] 负责布局上的强调,这里只负责把数据摆出来)。「我的媒体」是新增的库入口分区
 * (设计文档 §3.6,GET /UserViews),点击某个库交给 [HomeScreen] 的 onLibraryClick 回调处理导航。
 *
 * 四个分区各自独立取数、各自独立失败:一个接口的 500 绝不能把整页拖空白,所以每个分区是一条
 * 独立的 [MediaRepository] 流、独立 launch,互不影响;并且并发发起(而不是顺序 await),
 * 不让一个慢接口拖慢其余分区。空分区不出现在 [HomeUiState.sections] 里,标题也就不会显示——
 * 由调用方(这里)决定,而不是交给 Compose 层做"list.isEmpty() 就不画标题"这种容易漏的判断。
 *
 * ## 缓存优先(本次改动)
 *
 * 取数不再是"调 API → 显示",而是走 [MediaRepository]:**先发缓存,再后台刷新**。
 * 用户没开 VPN 打开 App 时,看到的是上次的内容而不是白屏或崩溃(设计文档 §3.2)。
 * 每个分区自己经历"缓存 → 新数据"两次发射,[applySection] 每次都重新拼一遍 sections,
 * 所以四个分区可以各自以不同的节奏落地,谁先到谁先显示。
 *
 * [error] 只在**一个分区都没拿到数据**时置位(既没有缓存、网络也全挂了)。只要有任何一个分区
 * 有内容可显示,就不该拿一整页错误盖住它——那正是"一个 500 拖空整页"的老毛病换个形式复发。
 *
 * [api] 是 :app 提供的会话代理(见 `dev.insua.jellycast.network.session.SessionJellyfinApi`),
 * 内部按需解析"当前应该用哪个 endpoint";`userId` 同样是运行时可变的会话状态(修正 §8d)——
 * 不再作为裸 `String` 构造参数注入(Hilt 无法稳定注入一个会随会话切换而失效的裸值),改成注入
 * [JellyfinSession] 并在每次真正发请求前用 [JellyfinSession.userId] 取当前值。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val session: JellyfinSession,
    private val repository: MediaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * 各分区最近一次拿到的数据。**"键不存在"和"值是空列表"含义不同**:前者是"这个分区至今
     * 一个字节都没拿到"(没缓存且请求失败),后者是"服务端确实说没有"。[load] 收尾时正是靠
     * 这个区分来决定要不要进错误态——把两者混为一谈,一次服务端抖动就会显示成"连不上服务器"。
     */
    private val sectionItems = mutableMapOf<HomeSectionKind, List<MediaItem>>()

    private val sectionRefreshFailed = mutableMapOf<HomeSectionKind, Boolean>()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        sectionItems.clear()
        sectionRefreshFailed.clear()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isOffline = false, error = null) }

            // 四个分区同时发起,再一起 join——并发而非串行。
            HomeSectionKind.entries
                .map { kind ->
                    launch {
                        repository.bucket(kind.bucket) { fetchSection(kind) }
                            .collect { cached -> applySection(kind, cached) }
                    }
                }
                .joinAll()

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    // 一个分区都没拿到数据 = 没缓存 + 全部失败,这才是"连不上服务器"。
                    error = if (sectionItems.isEmpty()) OFFLINE_MESSAGE else null,
                )
            }
        }
    }

    /** 重试:与首次加载完全同路,失败后的重试成功同样会把结果写回缓存。 */
    fun retry() = load()

    private suspend fun fetchSection(kind: HomeSectionKind): List<MediaItem> {
        val userId = session.userId()
        val response = when (kind) {
            HomeSectionKind.RESUME -> api.resume(userId)
            HomeSectionKind.NEXT_UP -> api.nextUp(userId)
            // 库里有 8744 集,不带 limit 会一次性拉全量并渲染。
            HomeSectionKind.RECENTLY_ADDED ->
                api.items(userId, types = "Episode,Movie", sortBy = "DateCreated", limit = 20)
            HomeSectionKind.LIBRARIES -> api.userViews(userId)
        }
        return response.items.mapNotNull { it.toMediaItem() }
    }

    /**
     * 每次发射都整体重拼 sections。分区顺序取自 [HomeSectionKind] 的声明顺序,与哪个分区先
     * 返回无关——否则"谁先到谁在上面"会让首页每次打开的排版都不一样。
     */
    private fun applySection(kind: HomeSectionKind, cached: Cached<List<MediaItem>>) {
        sectionItems[kind] = cached.data
        sectionRefreshFailed[kind] = cached.refreshFailed
        _uiState.update { state ->
            state.copy(
                sections = HomeSectionKind.entries.mapNotNull { k ->
                    sectionItems[k]?.takeIf { it.isNotEmpty() }?.let { HomeSection(k, k.title, it) }
                },
                isOffline = sectionRefreshFailed.values.any { it },
                error = null,
            )
        }
    }
}
