package dev.insua.jellycast.player.di

import dev.insua.jellycast.network.di.TrustAwareHttpClient
import dev.insua.jellycast.player.HttpStreamProbe
import dev.insua.jellycast.player.STREAM_READ_TIMEOUT_MS
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding 2(闭合):L1 探测用的 [HttpStreamProbe] 之前接的是 [PlayerModule] 自己 `provideStreamProbeOkHttpClient()`
 * 现造的裸 `OkHttpClient()`——系统信任链之外什么自签证书都不认,真实服务器上探测必定
 * `SSLHandshakeException`,而 [dev.insua.jellycast.player.PlaybackSourceResolver] 把这类异常静默吞掉、
 * 直接退化到 L3(全量视频流,~42 倍带宽),完全不提示用户。
 *
 * 这里不连真实 TLS 握手(离线单测拿不到自签证书服务器),只验证"接线"本身:
 * 1. [PlayerModule.provideStreamProbe] 的唯一参数必须标注 [TrustAwareHttpClient],而不是接受一个
 *    不带 qualifier、来源不明的 `OkHttpClient`。
 * 2. 传入 provider 的 client 的信任配置(连接池 + TLS 信任链)被原样带进了 [HttpStreamProbe] 内部
 *    ——证明没有在中途另起一个不认自签证书的裸 client、或者把参数吞掉不用。
 *
 *    2026-08-15 起,[HttpStreamProbe] 为了给 L1 探测单独配一条长读超时(见其 KDoc/
 *    `StreamTimeouts.kt`),会用 `client.newBuilder()...build()` 派生出一个新的 `OkHttpClient`
 *    实例——所以这里不能再用 `assertSame(trustAwareClient, ...)` 判断"同一个实例",
 *    newBuilder() 产出的必然是不同对象。但它必须**复制**而不是**丢弃**连接池与证书信任配置,
 *    于是改为分别断言这几个字段与原始 client 是同一个引用。
 */
class PlayerModuleTest {

    @Test fun `provideStreamProbe 的 client 参数必须标注 TrustAwareHttpClient`() {
        val method = PlayerModule::class.java.getDeclaredMethod("provideStreamProbe", OkHttpClient::class.java)
        val clientParamAnnotations = method.parameterAnnotations[0].map { it.annotationClass }

        assertTrue(
            clientParamAnnotations.contains(TrustAwareHttpClient::class),
            "provideStreamProbe 的 OkHttpClient 参数缺少 @TrustAwareHttpClient——" +
                "会退回接一个不认自签证书的裸 client,L1 探测在自签证书服务器上会静默降级到 L3",
        )
    }

    @Test fun `provideStreamProbe 把传入的 trust-aware client 的信任配置原样带进 HttpStreamProbe,不是另起一个裸 client`() {
        val trustAwareClient = OkHttpClient()

        val probe = PlayerModule.provideStreamProbe(trustAwareClient) as HttpStreamProbe

        val clientField = HttpStreamProbe::class.java.getDeclaredField("client").apply { isAccessible = true }
        val derivedClient = clientField.get(probe) as OkHttpClient

        assertSame(
            trustAwareClient.connectionPool,
            derivedClient.connectionPool,
            "派生出的 client 连接池不是原始 trust-aware client 的——像是另起了一个新 client",
        )
        assertSame(
            trustAwareClient.sslSocketFactory,
            derivedClient.sslSocketFactory,
            "派生出的 client sslSocketFactory 不是原始 trust-aware client 的——自签证书信任丢了",
        )
        assertSame(
            trustAwareClient.hostnameVerifier,
            derivedClient.hostnameVerifier,
            "派生出的 client hostnameVerifier 不是原始 trust-aware client 的——自签证书信任丢了",
        )
    }

    /**
     * Finding 2(已闭合):[PlayerModule.provideAudioCacheStore] 把共享的 `@TrustAwareHttpClient`
     * 原样传给 [AudioCacheDownloader],那份共享 client 保留 okhttp 默认的 10 秒读超时——
     * 4K HEVC `.ts` 这类源服务端要 6–46 秒才吐响应头,后台预缓存对这类源会稳定命中超时、删掉
     * `.part` 文件、悄悄失败,`CachePrefetchController` 永远预缓存不成功。
     *
     * 派生逻辑抽成了 [PlayerModule.cacheDownloadHttpClient](private),这里反射调用它本身——不
     * 经过需要 `Context`/Room 的完整 `provideAudioCacheStore`,直接验证"派生出的 client 读超时
     * 确实是 [STREAM_READ_TIMEOUT_MS]"。
     *
     * 变异验证:把 `cacheDownloadHttpClient` 的实现改回 `return client`(即撤销这次修复,让
     * `AudioCacheDownloader` 继续接未修改的共享 client)——这条用例断言的
     * `derived.readTimeoutMillis == STREAM_READ_TIMEOUT_MS` 会失败(读到的是 okhttp 默认的
     * 10_000,不是 60_000),证明它确实在守卫这次修复,不是摆设。
     */
    @Test fun `cacheDownloadHttpClient 派生出的client读超时是STREAM_READ_TIMEOUT_MS而不是共享client的默认10秒`() {
        val sharedClient = OkHttpClient()
        check(sharedClient.readTimeoutMillis != STREAM_READ_TIMEOUT_MS) {
            "前置条件失败:共享 client 的默认读超时本来就等于 STREAM_READ_TIMEOUT_MS,这条用例测不出区别"
        }

        val method = PlayerModule::class.java
            .getDeclaredMethod("cacheDownloadHttpClient", OkHttpClient::class.java)
            .apply { isAccessible = true }
        val derived = method.invoke(PlayerModule, sharedClient) as OkHttpClient

        assertEquals(
            STREAM_READ_TIMEOUT_MS,
            derived.readTimeoutMillis,
            "AudioCacheDownloader 派生出的 client 读超时不是 STREAM_READ_TIMEOUT_MS——" +
                "高码率 MPEG-TS 源的后台预缓存会在服务端吐响应头之前就被 okhttp 默认的 10 秒读超时打断",
        )
        assertSame(
            sharedClient.connectionPool,
            derived.connectionPool,
            "派生出的 client 连接池不是原始共享 client 的——像是另起了一个新 client",
        )
        assertSame(
            sharedClient.sslSocketFactory,
            derived.sslSocketFactory,
            "派生出的 client sslSocketFactory 不是原始共享 client 的——自签证书信任丢了",
        )
    }
}
