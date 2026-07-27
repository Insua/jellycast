package dev.insua.jellycast.model

data class Server(
    val id: String,
    val name: String,
    val endpoints: List<Endpoint>,
    val userId: String? = null,
    val accessToken: String? = null,
)

data class Endpoint(
    val url: String,          // 形如 http://192.168.1.10:8096 或 https://[240e::1]:8920
    val label: String,        // "局域网" / "Tailscale" / "公网"
    val priority: Int,        // 数字越小越优先
    val trustedCertSha256: String? = null,  // 自签证书指纹白名单
)

data class EndpointHealth(
    val endpoint: Endpoint,
    val reachable: Boolean,
    val latencyMs: Long?,
    val failureReason: String? = null,
)
