package dev.insua.jellycast.model

/**
 * 一次取数的结果:数据 + 它是不是旧的 + 本次刷新是否失败。
 *
 * 三个字段各自回答一个问题,不要合并:
 * - [data] —— 现在应该显示什么。**永远非空**,即使是空列表也是"确知服务端没有内容"的表态。
 * - [isStale] —— 这份数据来自本地缓存(可能已经过时),还是刚从服务端拿到的。UI 据此决定
 *   要不要继续显示"正在刷新"的指示。
 * - [refreshFailed] —— 本次后台刷新是否失败。这是"离线,显示的是上次内容"那条提示的唯一依据。
 *
 * 为什么 `refreshFailed` 不能省略、用「`isStale` 且流已结束」代替:那需要 UI 层知道 Flow 的
 * 生命周期,而"服务器确实还没返回"和"服务器返回失败了"在那种表示法下无法区分。
 */
data class Cached<T>(
    val data: T,
    val isStale: Boolean,
    val refreshFailed: Boolean = false,
)
