package dev.insua.jellycast.player.di

import dev.insua.jellycast.network.di.TrustAwareHttpClient
import dev.insua.jellycast.player.HttpStreamProbe
import okhttp3.OkHttpClient
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
}
