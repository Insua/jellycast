package dev.insua.jellycast.feature.server

import dev.insua.jellycast.network.sha256Fingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 仅供「自签证书确认」弹窗使用:系统证书校验失败后,单独发起一次 TLS 握手,把对端证书链
 * 捞出来算指纹给用户看。
 *
 * 铁律仍然成立——这次握手本身对证书来者不拒(否则握手会在系统校验失败时直接中止,拿不到
 * 证书内容可看),但它**不承载任何业务请求、结果不缓存、也不参与实际连接的信任决策**。
 * 真正放行业务流量的只有 [dev.insua.jellycast.network.trustPinnedSelfSigned] 按 host 隔离的
 * 白名单校验;用户必须先看到这里返回的指纹并显式确认,ViewModel 才会把它写进
 * `Endpoint.trustedCertSha256`,写入之后也只对这一个 endpoint 生效。
 */
fun interface PeerCertificateFetcher {
    /**
     * `suspend`,不是普通阻塞函数:这里做的是一次真实 TLS 握手。调用方是 ViewModel 的
     * `viewModelScope.launch { }`,跑在 `Dispatchers.Main` 上——和 `HttpEndpointProbe.probe()`
     * 那个 `NetworkOnMainThreadException` 是同一类缺陷(见该函数 KDoc)。切 IO 调度器的责任放在
     * 实现里,而不是让每个调用方各自记得包一层。
     */
    suspend fun fetch(endpointUrl: String): X509Certificate?
}

object DefaultPeerCertificateFetcher : PeerCertificateFetcher {
    override suspend fun fetch(endpointUrl: String): X509Certificate? = withContext(Dispatchers.IO) {
        val httpUrl = endpointUrl.toHttpUrlOrNull() ?: return@withContext null
        var captured: Array<X509Certificate>? = null

        val inspectionOnlyTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                captured = chain
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        try {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(inspectionOnlyTrustManager), null)
            }
            (sslContext.socketFactory.createSocket(httpUrl.host, httpUrl.port) as SSLSocket).use { socket ->
                socket.startHandshake()
            }
            captured?.firstOrNull()
        } catch (e: CancellationException) {
            // 与 SubtitleRepository / HttpStreamProbe 保持一致:取消不是"取证书失败",必须正常传播。
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** [DefaultPeerCertificateFetcher] 拿到的证书算出的指纹,格式与 [sha256Fingerprint] 一致。 */
suspend fun PeerCertificateFetcher.fetchFingerprint(endpointUrl: String): String? =
    fetch(endpointUrl)?.sha256Fingerprint()
