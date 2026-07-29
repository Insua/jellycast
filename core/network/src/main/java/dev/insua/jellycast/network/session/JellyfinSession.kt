package dev.insua.jellycast.network.session

import dev.insua.jellycast.network.JellyfinApi

/**
 * "当前登录的服务器 + 当前选中的接入地址"这份运行时可变会话状态的唯一权威来源。
 *
 * 为什么需要这一层(修正 §8d):`userId` 和用于发请求的 [JellyfinApi] 都依赖同一份东西——
 * 当前激活的 [dev.insua.jellycast.model.Server] 加上它的多个 [dev.insua.jellycast.model.Endpoint]
 * 里选路胜出的那一个。这份状态在一次进程生命周期里可以变化(用户切换激活服务器;网络环境变化导致
 * 上次选中的 endpoint 不再可达,需要重新选路)。把它藏在各个 ViewModel/Resolver 自己的构造参数里
 * (裸 `JellyfinApi`/裸 `userId: String`)必然导致这些值在构造时被"冻结"、后续不会再更新。
 *
 * 生产实现见 [ActiveServerSession]。
 */
interface JellyfinSession {
    /** 面向当前选中 endpoint、带认证 token 的 [JellyfinApi]。可能触发一次选路(见实现类文档)。 */
    suspend fun api(): JellyfinApi

    /** 当前登录用户的 id。 */
    suspend fun userId(): String

    /**
     * 当前激活服务器的 id(不是接入地址)。离线缓存按它分区,这样同一台手机上连过的多台服务器
     * 各自的缓存不会串号 —— 一台服务器的 `home.resume` 绝不能出现在另一台的首页上。
     *
     * 注意它和 [baseUrl] 是两层:一台 Server 有多个 Endpoint(局域网 / Tailscale / 公网),
     * 用户在家和出门时 baseUrl 不同、serverId 却是同一个,缓存必须跟着 serverId 走。
     */
    suspend fun serverId(): String

    /** 当前选中 endpoint 的 baseUrl,不带结尾斜杠。 */
    suspend fun baseUrl(): String

    /** 当前登录用户的 access token。 */
    suspend fun token(): String

    /**
     * 同步读取上一次成功解析后缓存下来的 baseUrl,尚未解析过时返回 null。
     *
     * 存在的理由:[dev.insua.jellycast.player.PlaybackSourceResolver] 的 `baseUrlProvider` /
     * `tokenProvider` 构造参数是同步 lambda([suspend] 语义在那两个契约里不成立,它们是在
     * resolve() 内部同步调用的)。但只要 resolve() 内部总是先调一次会经过 [api] 的请求
     * ([dev.insua.jellycast.network.JellyfinApi.playbackInfo]),缓存就已经被那次调用暖好,
     * 这两个同步 getter 读到的就是本次 resolve 用的同一份新鲜数据,不会读到上一个服务器的脏值。
     */
    fun cachedBaseUrlOrNull(): String?

    /** 见 [cachedBaseUrlOrNull]。 */
    fun cachedTokenOrNull(): String?

    /**
     * 强制下一次 [api] / [userId] / [baseUrl] 重新选路,而不是复用缓存的 endpoint。
     *
     * 用于"当前 endpoint 疑似已经不可达"的场景(见 [SessionJellyfinApi] 对请求失败的处理)——
     * 这就是运行时切换 endpoint(比如从局域网切到公网)的落地机制:不是被动监听网络变化,
     * 而是"一旦某次请求失败,下一次请求前重新做一次并发选路"。
     */
    fun invalidate()
}
