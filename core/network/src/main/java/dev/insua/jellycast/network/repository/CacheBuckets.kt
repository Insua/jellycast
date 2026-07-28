package dev.insua.jellycast.network.repository

/**
 * 缓存分区键的唯一定义处。之所以集中在这里而不是让每个 ViewModel 就地拼字符串:bucket 是
 * 缓存表的主键组成部分,写的时候和读的时候拼得不一样,表现就是"缓存永远不命中",而且不会
 * 报任何错 —— 这种缺陷只能靠"只有一处拼法"来杜绝。
 *
 * 命名与 `CachedItemEntity` 的 KDoc 保持一致。
 */
object CacheBuckets {
    const val HOME_RESUME = "home.resume"
    const val HOME_NEXT_UP = "home.nextup"

    /** 「我的媒体」库入口列表(GET /UserViews)。 */
    const val HOME_LIBRARIES = "home.libraries"

    const val LIBRARY_SERIES = "library.series"
    const val LIBRARY_MOVIES = "library.movies"

    /** 某个库的"最近添加"分组——按库分区,而不是首页一条拉全部库混排的扁平列表。 */
    fun recentlyAddedOf(libraryId: String): String = "home.recent.$libraryId"

    /** 某部剧的季列表。 */
    fun seasonsOf(seriesId: String): String = "series.$seriesId.seasons"

    /** 某一季的完整集列表 —— 也就是自动连播的播放队列。 */
    fun episodesOf(seasonId: String): String = "season.$seasonId.episodes"
}
