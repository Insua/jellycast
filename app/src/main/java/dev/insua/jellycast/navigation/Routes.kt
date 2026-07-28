package dev.insua.jellycast.navigation

/** 修正 §4 的路由表。`player` 不是这里的一个 NavHost 目的地——见 [JellyCastNavHost] 的类注释。 */
object Routes {
    const val SERVERS = "servers"
    const val SERVERS_ADD = "servers/add"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val SERIES_DETAIL_PATTERN = "library/series/{seriesId}"
    const val COLLECTION_DETAIL_PATTERN = "library/collection/{collectionId}"

    fun seriesDetail(seriesId: String) = "library/series/$seriesId"
    fun collectionDetail(collectionId: String) = "library/collection/$collectionId"

    /** 底部导航栏 + 常驻迷你播放条只在这些目的地上显示——服务器列表/添加表单是登录前流程,没有 tab。 */
    val CHROME_ROUTES = setOf(HOME, LIBRARY, SETTINGS, SERIES_DETAIL_PATTERN, COLLECTION_DETAIL_PATTERN)
}
