package dev.insua.jellycast.model

/**
 * 分页列表的不可变状态机。**不做任何 IO** —— 调用方负责取数据,把结果喂进来。
 *
 * 之所以不用 Paging 3:项目铁律要求核心逻辑可离线单测,而 `PagingData` 是不透明流,
 * 难以断言"第二页追加在第一页之后"这类行为。详见
 * `docs/superpowers/specs/2026-07-26-library-paging-and-search-design.md` §3.1。
 */
data class PageState<T>(
    val items: List<T> = emptyList(),
    /** 服务端 TotalRecordCount;null 表示尚未发起过成功请求 */
    val totalCount: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val loadedCount: Int get() = items.size

    /** 只有确知总数且已加载够了才算到底。totalCount 未知时必须返回 false,否则会漏加载。 */
    val endReached: Boolean get() = totalCount != null && items.size >= totalCount

    fun startLoading(): PageState<T> = copy(isLoading = true, error = null)

    /**
     * 接收一页数据。
     *
     * [startIndex] 用于幂等保护:只有恰好接在当前已加载条目之后的响应才被采纳。
     * 重复请求(startIndex 偏小)会导致内容重复,乱序/跳跃响应(startIndex 偏大)
     * 会在列表中留下空洞 —— 两者都直接丢弃。
     */
    fun onPageLoaded(newItems: List<T>, startIndex: Int, total: Int): PageState<T> {
        if (startIndex != items.size) return copy(isLoading = false, error = null)
        return copy(
            items = items + newItems,
            totalCount = total,
            isLoading = false,
            error = null,
        )
    }

    /** 失败时**保留**已加载内容 —— 绝不清空用户已经看到的东西。 */
    fun onError(message: String): PageState<T> = copy(isLoading = false, error = message)

    fun reset(): PageState<T> = PageState()
}
