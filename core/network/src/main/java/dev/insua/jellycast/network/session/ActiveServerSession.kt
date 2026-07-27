package dev.insua.jellycast.network.session

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [JellyfinSession] 的生产实现。
 *
 * ## 运行时可变 baseUrl 的落地方案
 *
 * 普通 DI 教程里"启动时构造一次 Retrofit,注入到处用"的模式在本项目不成立——同一台
 * [Server] 有多个 [Endpoint](局域网/Tailscale/公网),哪一个胜出是**运行时并发探测**的结果
 * (见 [EndpointSelector]),而且赢家可能在会话中途变化(用户带着手机出门,局域网地址失效)。
 *
 * 这里的做法:不在 DI 图里固定一个 Retrofit/[JellyfinApi] 实例,而是把"解析出一个当前可用的
 * [JellyfinApi]"这件事本身做成一个**带缓存的挂起操作**([resolve]):
 * 1. 先看当前激活的 [Server] 是哪一台([activeServer] 这个 Flow,由调用方在应用启动时用
 *    `ServerStore.servers` + `ServerStore.activeServerId` 组合出来)。
 * 2. 如果缓存里已经有这台服务器解析好的结果(同一个 `server.id`),直接复用——不是每次调用都
 *    重新跑一次 [EndpointSelector.select] 的并发探测,那样每次请求都要付出探测延迟。
 * 3. 缓存未命中(第一次用,或者服务器切换了,或者被 [invalidate] 过)时,才真正跑一次
 *    [EndpointSelector.select],拿到这一刻胜出的 endpoint,用 [apiFactory] 现场组装一个绑定了该
 *    endpoint baseUrl + 该用户 token 的 [JellyfinApi]。
 *
 * ## 会话中途切换 endpoint 怎么发生
 *
 * 不被动监听连通性变化(那需要 Android `ConnectivityManager` 回调,超出这一层的职责),而是
 * "失败驱动重新选路":[SessionJellyfinApi] 把每一次业务请求包一层——请求失败就调 [invalidate]。
 * 于是下一次任意请求会重新触发第 3 步的并发选路,自然地在"当前对哪个 endpoint 可达"发生变化后
 * (不管是网络切换还是服务端重启)收敛到新的胜出者,而不需要任何显式的"网络变化"事件源。
 *
 * 用 [Mutex] 序列化 [resolve]:两个并发请求同时触发首次选路时,只应该真的探测一次,后到的那个
 * 应该等第一个探测完、直接复用其结果,而不是各自发起一轮探测。
 */
class ActiveServerSession(
    private val activeServer: Flow<Server?>,
    private val endpointSelector: EndpointSelector,
    private val apiFactory: SessionApiFactory,
) : JellyfinSession {

    private data class Cached(
        val serverId: String,
        val endpoint: Endpoint,
        val token: String,
        val userId: String,
        val api: JellyfinApi,
    )

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    override fun invalidate() {
        cached = null
    }

    override fun cachedBaseUrlOrNull(): String? = cached?.endpoint?.url

    override fun cachedTokenOrNull(): String? = cached?.token

    override suspend fun api(): JellyfinApi = resolve().api

    override suspend fun userId(): String = resolve().userId

    /**
     * 🔴 **刻意不走 [resolve]。**
     *
     * "当前激活的是哪台服务器"是本地 DataStore 里的事实,和"这一刻哪个接入地址连得上"是两件事。
     * 走 [resolve] 的话这个问题会被一次并发选路探测挡住,而离线缓存
     * (`CachingMediaRepository`,缓存按 serverId 分区)**每次读缓存前都要先问它一次** ——
     * 于是断网冷启动时读缓存必然失败,有缓存也被当成没缓存,用户看到的还是白屏。
     * 整套离线缓存恰好在最需要它的那一刻失效,而且不报任何错。
     *
     * 仍然保留"没有已激活服务器就失败"这一条:那种情况下缓存该往哪个分区读写是没有答案的,
     * 静默返回一个假 id 只会把不同服务器的缓存串在一起。
     *
     * 也刻意**不**读 [cached]:那份是"上次解析成功的那台",用户切换激活服务器之后它会短暂落后,
     * 拿它当分区键会把新服务器的数据写进旧服务器的缓存里。直接问 [activeServer] 永远是对的。
     */
    override suspend fun serverId(): String =
        (activeServer.first() ?: error("没有已激活的服务器")).id

    override suspend fun baseUrl(): String = resolve().endpoint.url

    override suspend fun token(): String = resolve().token

    private suspend fun resolve(): Cached = mutex.withLock {
        val server = activeServer.first() ?: error("没有已激活的服务器")

        cached?.let { existing -> if (existing.serverId == server.id) return existing }

        val token = server.accessToken ?: error("服务器 ${server.name} 尚未登录")
        val userId = server.userId ?: error("服务器 ${server.name} 尚未登录")
        val winner = endpointSelector.select(server.endpoints)
            ?: error("服务器 ${server.name} 没有可达的接入地址")

        val api = apiFactory.create(winner.endpoint) { token }
        Cached(server.id, winner.endpoint, token, userId, api).also { cached = it }
    }
}
