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
 * 2. 传入 provider 的 client 就是最终塞进 [HttpStreamProbe] 内部的那个实例(证明没有在中途另起
 *    一个新 client、或者把参数吞掉不用)。
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

    @Test fun `provideStreamProbe 把传入的 trust-aware client 原样交给 HttpStreamProbe,不是另起一个`() {
        val trustAwareClient = OkHttpClient()

        val probe = PlayerModule.provideStreamProbe(trustAwareClient) as HttpStreamProbe

        val clientField = HttpStreamProbe::class.java.getDeclaredField("client").apply { isAccessible = true }
        assertSame(trustAwareClient, clientField.get(probe))
    }
}
