package dev.insua.jellycast.network

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 证书的 SHA-256 指纹,大写十六进制、冒号分隔(如浏览器"查看证书"里展示的格式),
 * 供用户在确认自签证书时核对。对整张 DER 编码证书取哈希,不是只对公钥(SPKI)取哈希——
 * 这一点决定了这个指纹不能直接喂给 OkHttp 的 [okhttp3.CertificatePinner](它要的是 SPKI 哈希)。
 */
fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString(":") { "%02X".format(it) }

private fun String.normalizeHost(): String = trim('[', ']').lowercase()

/**
 * 在系统信任链之外,额外信任用户显式确认过的自签证书,并按 host 隔离信任范围。
 *
 * [sha256ByHost] 以 host(不带端口;IPv6 地址不带方括号)为 key,value 是
 * [sha256Fingerprint] 格式的指纹——即 `Endpoint.trustedCertSha256` 按各自 host 聚合成的映射。
 *
 * ## 铁律:绝不全局关闭 TLS 校验
 *
 * - 白名单为空时原样返回 [OkHttpClient.Builder],不做任何改动。
 * - 未在白名单中的证书,或指纹匹配但挂在别的 host 名下的证书,一律仍走系统信任链校验——
 *   系统校验失败就抛异常,没有任何"放行"分支。
 * - 只有当系统校验失败、且该证书指纹**恰好等于握手对端 host 在白名单中登记的指纹**时,
 *   才视为用户已确认、放行这一次握手。
 *
 * ## 为什么能做到按 host 隔离,而不是"任一白名单指纹都能通过任一 host"
 *
 * [javax.net.ssl.X509TrustManager.checkServerTrusted] 的经典两参数重载拿不到 host 信息——
 * 如果只实现这个重载,就只能拿指纹去比对整个白名单的 value 集合,不管 key,这样 A 站点确认过的
 * 自签证书会被 B 站点冒用(这是原始方案的缺陷)。
 *
 * [X509ExtendedTrustManager](JDK 标准公开 API,自 Java 7 起就是为这个场景设计的)额外提供了带
 * [Socket] / [SSLEngine] 参数的重载。当交给 SSLContext 的信任管理器运行时类型是
 * [X509ExtendedTrustManager] 时,JSSE 的握手实现会自动改调这些带连接上下文的重载,而不是老的
 * 两参数版本——这是 JDK 自身的行为,不依赖 OkHttp 内部类。[Socket.getHandshakeSession] /
 * [SSLEngine.getPeerHost] 返回的是**我们自己发起连接时要求连接的 host**(来自
 * `SSLSocketFactory.createSocket(host, port)` 的参数,即 OkHttp 从请求 URL 里取出的 host),
 * 不是服务端证书自称的 CN/SAN,也不受服务端响应内容影响,所以拿它做 host 匹配是可信的。
 *
 * 退化路径:如果某次握手真的只触发了老的两参数重载(理论上在标准 JVM 上不会,这里仅为防御性
 * 兜底),实现选择直接委托系统校验、不做任何指纹放行——宁可"该信任的连接失败",也不可
 * "不该信任的连接被放行"。
 */
fun OkHttpClient.Builder.trustPinnedSelfSigned(
    sha256ByHost: Map<String, String>,
): OkHttpClient.Builder {
    if (sha256ByHost.isEmpty()) return this

    val fingerprintByHost = sha256ByHost.entries.associate { (host, fp) -> host.normalizeHost() to fp }

    val systemTrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

    fun checkWithPinFallback(chain: Array<X509Certificate>, authType: String, peerHost: String?) {
        try {
            systemTrustManager.checkServerTrusted(chain, authType)
        } catch (systemValidationFailure: Exception) {
            val expectedFingerprint = peerHost?.normalizeHost()?.let { fingerprintByHost[it] }
                ?: throw systemValidationFailure
            val actualFingerprint = chain.first().sha256Fingerprint()
            if (!expectedFingerprint.equals(actualFingerprint, ignoreCase = true)) {
                throw systemValidationFailure
            }
            // 指纹与该 host 登记的白名单条目完全一致 —— 视为用户已确认,放行这一次握手。
        }
    }

    val trustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrustManager.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            systemTrustManager.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            systemTrustManager.checkClientTrusted(chain, authType)

        // 没有 host 信息的经典重载:绝不据此放行任何自签证书,只委托系统校验。
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrustManager.checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
            val peerHost = (socket as? SSLSocket)?.handshakeSession?.peerHost
            checkWithPinFallback(chain, authType, peerHost)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
            checkWithPinFallback(chain, authType, engine.peerHost)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return sslSocketFactory(sslContext.socketFactory, trustManager)
}
