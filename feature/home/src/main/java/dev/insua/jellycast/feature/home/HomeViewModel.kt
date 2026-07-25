package dev.insua.jellycast.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.mapper.toMediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomeSectionKind { RESUME, NEXT_UP, RECENTLY_ADDED }

data class HomeSection(
    val kind: HomeSectionKind,
    val title: String,
    val items: List<MediaItem>,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
)

/**
 * "在听"首页:继续收听 / 下一集 / 最近添加,自上而下。设计文档的成功标准之一是
 * "打开 App 到开始播放'下一集'不超过 3 次点击"——下一集分区是追剧主入口,必须显眼、可直接点播
 * (由 [HomeScreen] 负责布局上的强调,这里只负责把数据摆出来)。
 *
 * 三个分区各自独立请求、各自独立失败:一个接口的 500 绝不能把整页拖空白,所以每个分区的
 * 加载单独 try/catch,互不影响;并且并发发起(而不是顺序 await),不让一个慢接口拖慢其余分区。
 * 空分区不出现在 [HomeUiState.sections] 里,标题也就不会显示——由调用方(这里)决定,而不是
 * 交给 Compose 层做"list.isEmpty() 就不画标题"这种容易漏的判断。
 *
 * [api] / [userId] 目前直接构造注入,尚未接入"当前激活服务器"的会话解析(那是 Task 22 导航装配
 * 的职责);:feature:home 现在还没有被 :app 引用,不影响 Hilt 全图校验。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: JellyfinApi,
    private val userId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 三个 async 同时发起,再逐个 await——并发而非串行。
            val resumeDeferred = async {
                runCatching { api.resume(userId).items.mapNotNull { it.toMediaItem() } }
            }
            val nextUpDeferred = async {
                runCatching { api.nextUp(userId).items.mapNotNull { it.toMediaItem() } }
            }
            val recentDeferred = async {
                runCatching {
                    api.items(userId, types = "Episode,Movie", sortBy = "DateCreated")
                        .items.mapNotNull { it.toMediaItem() }
                }
            }

            val resume = resumeDeferred.await().getOrDefault(emptyList())
            val nextUp = nextUpDeferred.await().getOrDefault(emptyList())
            val recent = recentDeferred.await().getOrDefault(emptyList())

            val sections = buildList {
                if (resume.isNotEmpty()) add(HomeSection(HomeSectionKind.RESUME, "继续收听", resume))
                if (nextUp.isNotEmpty()) add(HomeSection(HomeSectionKind.NEXT_UP, "下一集", nextUp))
                if (recent.isNotEmpty()) add(HomeSection(HomeSectionKind.RECENTLY_ADDED, "最近添加", recent))
            }

            _uiState.update { it.copy(isLoading = false, sections = sections) }
        }
    }
}
