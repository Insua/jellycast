package dev.insua.jellycast.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.network.trustPinnedSelfSigned
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.URI
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * "信任 endpoint 自签证书"的 OkHttpClient(修正 §1c)。用于 Coil 封面图 / 字幕 HTTP 拉取,以及
 * L1 音频流探测([dev.insua.jellycast.player.HttpStreamProbe])——任何直连当前 endpoint、需要
 * 认得用户已确认过的自签证书指纹的场景。
 *
 * ⚠️ Finding 2(闭合):这个 qualifier + provider 原来重复长在 `:app` 的 `NetworkModule` 里,
 * `:core:player` 的 `PlayerModule` 拿不到它(`:core:player` 不能反向依赖 `:app`,那样会形成循环
 * 依赖),所以 Task 21/22 只能给 `HttpStreamProbe` 塞一个裸 `OkHttpClient()`——系统信任链之外
 * 什么都不认,自签证书服务器上探测必定 `SSLHandshakeException`,`PlaybackSourceResolver`
 * 把这类异常静默吞掉、直接退化到 L3(见 `PlaybackSourceResolver` 的 `runCatching`),
 * 完全不提示用户,这正是本产品要避免的"最坏的静默降级"。
 *
 * 修正:`:app` 和 `:core:player` 都已经对 `:core:network` 有 `implementation` 依赖,所以把这个
 * qualifier + provider 挪到这里——两边都能看见同一个 `@Singleton` 绑定,不用各自重复造一个
 * client(重复的话就是两个不同的 OkHttpClient 实例,连接池/证书信任状态不共享,也失去了"只有
 * 一份信任判断逻辑"这件事本身的意义)。
 *
 * `Retention` 用 RUNTIME 而不是常见的 BINARY:Dagger/Hilt 编译期就能用 BINARY 完成绑定,但这里
 * 特意留到运行时可见,好让 `PlayerModuleTest` 能用反射直接断言 `provideStreamProbe` 的参数确实
 * 标了这个 qualifier——回归到裸 `OkHttpClient()` 这种"编译能过、运行时才在自签证书服务器上
 * 静默降级到 L3"的错误,能在离线单测里就抓到,不用等到真机连自签证书服务器才发现。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class TrustAwareHttpClient

@Module
@InstallIn(SingletonComponent::class)
object TrustAwareHttpClientModule {

    /**
     * 铁律复查:证书信任只走 [trustPinnedSelfSigned](本模块内、按 host 隔离、Task 5 审查过),
     * 绝不新增全局 TrustManager/HostnameVerifier。
     *
     * 已知取舍:白名单是应用启动时对 [ServerStore] 的一次快照(`runBlocking { .first() }`)——
     * 进程存活期间新增的自签证书信任(用户在添加服务器表单里刚确认的指纹)要等下次冷启动才会
     * 影响封面图/字幕/L1 探测请求。API 请求本身不受影响,因为它们经过 `JellyfinApi` 这条完全
     * 独立的路径(每次选路都重新读最新的 `Endpoint.trustedCertSha256`)。
     */
    @Provides
    @Singleton
    @TrustAwareHttpClient
    fun provideTrustAwareOkHttpClient(serverStore: ServerStore): OkHttpClient {
        val trustedByHost = runBlocking {
            serverStore.servers.first().flatMap { server ->
                server.endpoints.mapNotNull { endpoint ->
                    val fingerprint = endpoint.trustedCertSha256 ?: return@mapNotNull null
                    val host = runCatching { URI(endpoint.url).host }.getOrNull() ?: return@mapNotNull null
                    host to fingerprint
                }
            }.toMap()
        }
        return OkHttpClient.Builder().trustPinnedSelfSigned(trustedByHost).build()
    }
}
