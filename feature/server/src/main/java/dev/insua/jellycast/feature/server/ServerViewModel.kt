package dev.insua.jellycast.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.dto.AuthRequestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 添加服务器表单里的一个接入地址输入行。`trustedCertSha256` 只在用户确认过自签证书指纹后写入。 */
data class EndpointInput(
    val url: String = "",
    val label: String = "",
    val trustedCertSha256: String? = null,
)

data class AddServerForm(
    val name: String = "",
    val endpoints: List<EndpointInput> = listOf(EndpointInput()),
    val username: String = "",
    val password: String = "",
)

/** 服务器列表一行:名称 + 当前选中的 endpoint 与延迟(如 "Tailscale · 42ms"),选路结果未知前为 null。 */
data class ServerListItem(
    val id: String,
    val name: String,
    val selectedEndpointLabel: String? = null,
    val latencyMs: Long? = null,
)

/**
 * 探测到自签证书、等待用户在弹窗里确认指纹后才写入 `Endpoint.trustedCertSha256`。
 *
 * 用 [endpointUrl](已规整化的地址字符串)而不是下标来标识目标 endpoint:`diagnostics` 是
 * [ServerViewModel.validEndpoints] 压缩过的列表(`mapIndexedNotNull` 会丢掉空白/不合法的行),
 * 而用户要写回的目标是 `form.endpoints`——原始、未压缩的表单行列表。这两个列表的下标不对齐
 * (目标地址前面只要有一行空白或非法输入,下标就会错位),所以下标不是稳定的身份标识;
 * 规整化后的 URL 在同一次表单里唯一标识"用户刚看过证书的那一行",落到 [confirmCertificate]
 * 时按它匹配,而不是按位置去数第几行。
 */
data class CertConfirmation(
    val endpointUrl: String,
    val fingerprint: String,
)

data class ServerUiState(
    val servers: List<ServerListItem> = emptyList(),
    val form: AddServerForm = AddServerForm(),
    val diagnostics: List<EndpointHealth> = emptyList(),
    val isProbing: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val certConfirmation: CertConfirmation? = null,
    val connectedServerId: String? = null,
)

/**
 * 服务器管理与登录。铁律:密码只用于登录这一次请求,过程中和结束后都不落盘——
 * [ServerStore] 只持久化 [Server.accessToken] / [Server.userId](见 修正 §7)。这意味着 token
 * 过期(401)时没有保存的密码可用于"静默重新认证",只能引导用户回到登录页重新输入密码;
 * 这个取舍在 Task 17 报告里有记录。
 */
@HiltViewModel
class ServerViewModel @Inject constructor(
    private val serverStore: ServerStore,
    private val endpointSelector: EndpointSelector,
    private val jellyfinApiFactory: JellyfinApiFactory,
    private val certificateFetcher: PeerCertificateFetcher = DefaultPeerCertificateFetcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            serverStore.servers.collect { servers -> onServersChanged(servers) }
        }
    }

    private fun onServersChanged(servers: List<Server>) {
        _uiState.update { state ->
            state.copy(servers = servers.map { s ->
                state.servers.find { it.id == s.id }?.copy(name = s.name)
                    ?: ServerListItem(id = s.id, name = s.name)
            })
        }
        servers.forEach { refreshServerStatus(it) }
    }

    /** 后台为列表里每台服务器跑一次 [EndpointSelector.select](取最快的,不是逐条诊断)。 */
    private fun refreshServerStatus(server: Server) {
        viewModelScope.launch {
            val winner = endpointSelector.select(server.endpoints)
            _uiState.update { state ->
                state.copy(servers = state.servers.map { item ->
                    if (item.id == server.id) {
                        item.copy(selectedEndpointLabel = winner?.endpoint?.label, latencyMs = winner?.latencyMs)
                    } else item
                })
            }
        }
    }

    // ---- 添加服务器表单 ----

    fun onNameChange(name: String) = updateForm { it.copy(name = name) }
    fun onUsernameChange(username: String) = updateForm { it.copy(username = username) }
    fun onPasswordChange(password: String) = updateForm { it.copy(password = password) }

    fun onEndpointUrlChange(index: Int, url: String) = updateForm { form ->
        form.copy(endpoints = form.endpoints.mapIndexed { i, e -> if (i == index) e.copy(url = url) else e })
    }

    fun onEndpointLabelChange(index: Int, label: String) = updateForm { form ->
        form.copy(endpoints = form.endpoints.mapIndexed { i, e -> if (i == index) e.copy(label = label) else e })
    }

    fun addEndpointField() = updateForm { form -> form.copy(endpoints = form.endpoints + EndpointInput()) }

    fun removeEndpointField(index: Int) = updateForm { form ->
        form.copy(
            endpoints = form.endpoints.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(EndpointInput()) }
        )
    }

    private inline fun updateForm(transform: (AddServerForm) -> AddServerForm) {
        _uiState.update { it.copy(form = transform(it.form), error = null) }
    }

    /**
     * 「测试连接」按钮:必须用 [EndpointSelector.probeAll](等全部探测完成),不能用
     * [EndpointSelector.select](只返回最先成功的一个)——诊断要的是每个地址各自的结果,
     * 用 select() 只会看到一个结果,不是诊断。
     */
    fun testConnection() {
        if (_uiState.value.isProbing) return
        val endpoints = validEndpoints()
        if (endpoints == null) {
            _uiState.update { it.copy(error = "请至少填写一个合法的接入地址(http:// 或 https://)") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isProbing = true, error = null) }
            val results = endpointSelector.probeAll(endpoints)
            _uiState.update {
                it.copy(
                    isProbing = false,
                    diagnostics = results,
                    error = if (results.none(EndpointHealth::reachable)) buildUnreachableMessage(results) else null,
                )
            }
        }
    }

    /**
     * 提交表单:连接(用 [EndpointSelector.select] 取最快可用地址,不是诊断)、登录、
     * 保存服务器。全部地址都连不上时,退回去跑一次 [EndpointSelector.probeAll] 只是为了把
     * 每个地址的失败原因摆给用户看——这次额外探测只发生在失败路径上。
     */
    fun submit() {
        if (_uiState.value.isSubmitting) return
        val form = _uiState.value.form
        val name = form.name.trim()
        val username = form.username.trim()
        val password = form.password
        val endpoints = validEndpoints()

        val validationError = when {
            name.isEmpty() -> "请填写服务器名称"
            endpoints == null -> "请至少填写一个合法的接入地址(http:// 或 https://)"
            username.isEmpty() -> "请填写用户名"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }
        checkNotNull(endpoints)

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }

            val winner = endpointSelector.select(endpoints)
            if (winner == null) {
                val diagnostics = endpointSelector.probeAll(endpoints)
                _uiState.update {
                    it.copy(isSubmitting = false, diagnostics = diagnostics, error = buildUnreachableMessage(diagnostics))
                }
                return@launch
            }

            try {
                val api = jellyfinApiFactory.create(winner.endpoint)
                val auth = api.authenticate(AuthRequestDto(username = username, pw = password))
                val server = Server(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    endpoints = endpoints,
                    userId = auth.user.id,
                    accessToken = auth.accessToken,
                )
                serverStore.upsert(server)
                serverStore.setActive(server.id)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        form = AddServerForm(),
                        diagnostics = emptyList(),
                        connectedServerId = server.id,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = "登录失败:${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** 导航消费完 `connectedServerId` 后调用,避免重复触发 onServerReady。 */
    fun consumeConnectedServer() {
        _uiState.update { it.copy(connectedServerId = null) }
    }

    // ---- 自签证书确认 ----

    /** 用户对某一条失败诊断点了"查看证书"。只用于展示指纹,不代表信任——见 [PeerCertificateFetcher]。 */
    fun onInspectCertificate(diagnosticIndex: Int) {
        val diagnostic = _uiState.value.diagnostics.getOrNull(diagnosticIndex) ?: return
        viewModelScope.launch {
            val fingerprint = certificateFetcher.fetchFingerprint(diagnostic.endpoint.url)
            if (fingerprint == null) {
                _uiState.update { it.copy(error = "无法获取该地址的证书信息") }
                return@launch
            }
            _uiState.update {
                it.copy(certConfirmation = CertConfirmation(diagnostic.endpoint.url, fingerprint))
            }
        }
    }

    /**
     * 用户在弹窗里确认了指纹——写回表单里对应那一行的 `trustedCertSha256`,仅对该 endpoint 生效。
     * 按规整化后的 URL 匹配(见 [CertConfirmation] 的注释),而不是按下标——`form.endpoints`
     * 是未压缩的原始行列表,下标和 `diagnostics`/`certConfirmation` 不对齐。
     */
    fun confirmCertificate() {
        val confirmation = _uiState.value.certConfirmation ?: return
        _uiState.update { state ->
            val endpoints = state.form.endpoints.map { e ->
                if (normalizeEndpointUrl(e.url) == confirmation.endpointUrl) {
                    e.copy(trustedCertSha256 = confirmation.fingerprint)
                } else e
            }
            state.copy(form = state.form.copy(endpoints = endpoints), certConfirmation = null)
        }
    }

    fun dismissCertificateConfirmation() {
        _uiState.update { it.copy(certConfirmation = null) }
    }

    // ---- 内部辅助 ----

    /** 表单里的地址行 → 规整化过的 [Endpoint] 列表,过滤掉空/不合法的行;一个都不剩时返回 null。 */
    private fun validEndpoints(): List<Endpoint>? {
        val endpoints = _uiState.value.form.endpoints.mapIndexedNotNull { index, input ->
            val url = normalizeEndpointUrl(input.url) ?: return@mapIndexedNotNull null
            Endpoint(
                url = url,
                label = input.label.trim().ifEmpty { "地址${index + 1}" },
                priority = index,
                trustedCertSha256 = input.trustedCertSha256,
            )
        }
        return endpoints.ifEmpty { null }
    }
}

/**
 * 设计文档 §8:全部地址都探测失败时,提示要**逐条列出每个地址自己的失败原因**,不是一句笼统的
 * "连接失败"；如果 Tailscale 地址是超时、且没有其它地址可用,额外明确提示"检查 Tailscale
 * 是否已连接"。
 */
internal fun buildUnreachableMessage(diagnostics: List<EndpointHealth>): String {
    val reasons = diagnostics.joinToString("\n") { health ->
        "${health.endpoint.label}(${health.endpoint.url}):${health.failureReason ?: "未知错误"}"
    }
    val tailscaleTimedOutAlone = diagnostics.none(EndpointHealth::reachable) &&
        diagnostics.any { health ->
            health.endpoint.label.contains("tailscale", ignoreCase = true) &&
                health.failureReason?.contains("timeout", ignoreCase = true) == true
        }
    return buildString {
        append("无法连接到服务器:\n")
        append(reasons)
        if (tailscaleTimedOutAlone) {
            append("\n检查 Tailscale 是否已连接。")
        }
    }
}
