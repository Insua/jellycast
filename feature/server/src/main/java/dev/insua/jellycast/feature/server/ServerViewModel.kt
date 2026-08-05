package dev.insua.jellycast.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.insua.jellycast.cache.AudioCacheStore
import dev.insua.jellycast.database.CachedItemDao
import dev.insua.jellycast.datastore.LastPlayedStore
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
import retrofit2.HttpException
import java.io.IOException
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
    /** 待确认删除的服务器 id——非 null 时列表页要弹出二次确认对话框。删除会丢登录态,
     *  误删代价高,所以不允许点一下就直接删。 */
    val deleteConfirmation: String? = null,
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
    private val cachedItemDao: CachedItemDao,
    private val audioCacheStore: AudioCacheStore,
    private val lastPlayedStore: LastPlayedStore,
    private val certificateFetcher: PeerCertificateFetcher = DefaultPeerCertificateFetcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    /** 最近一次观察到的活跃服务器 id,删除时用来判断要不要清活跃标记 + 缓存(见 [confirmDeleteServer])。 */
    private var activeServerId: String? = null

    init {
        viewModelScope.launch {
            serverStore.servers.collect { servers -> onServersChanged(servers) }
        }
        viewModelScope.launch {
            serverStore.activeServerId.collect { activeServerId = it }
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
                    it.copy(isSubmitting = false, error = buildLoginErrorMessage(e))
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

    // ---- 删除服务器 ----

    /** 点了某一行的删除按钮:只弹二次确认,不动 [ServerStore]——误删会丢登录态,必须先确认。 */
    fun requestDeleteServer(id: String) {
        _uiState.update { it.copy(deleteConfirmation = id, error = null) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(deleteConfirmation = null) }
    }

    /**
     * 用户在确认弹窗里点了「删除」。真正调 [ServerStore.delete] 的唯一入口。
     *
     * 被删的如果正好是当前活跃服务器,还要清掉活跃标记([ServerStore.clearActive])、清空
     * 它的库浏览缓存([CachedItemDao.clearServer])**和音频缓存**([AudioCacheStore.clearServer],
     * 复审 I2),并清掉「上次播放」记录([LastPlayedStore.clear],复审 Task 5 Important 1)——
     * 否则激活标记会指向一台不存在的服务器,两张缓存表里都会留下无主的行,「上次播放」记录也会
     * 继续指着一台已经被删掉的服务器,下次冷启动迷你条恢复出来的条目连服务器都不存在了。
     *
     * ⚠️ 复审 I2(Important):[AudioCacheStore.clearServer] 在这次修复之前**没有任何生产调用方
     * ——[CachedAudioDao.clearServer] 写好了、也单测过,却从没被这里调用**,音频缓存的索引行
     * 和 `audio-cache/<serverId>/` 目录整个被漏掉,删服务器不会清掉它可能高达设置上限(默认
     * 1 GB,最高可选 10 GB/不限制)的本地文件,永久占用磁盘直到用户手动清 App 数据。
     *
     * 五步全部包在同一个 try 里:任何一步失败都算这次删除失败,必须给用户
     * 看得到的提示(铁律:删除失败不得静默),不能让用户以为删成功了。
     *
     * 这一步只清磁盘上的记录。若用户删完立刻连一台新服务器,导航层随后会调
     * [AppSessionViewModel.onServerConnected][dev.insua.jellycast.navigation.AppSessionViewModel]
     * 把进程内已经装填好的迷你条/播放队列一并清掉——那是另一半状态,这里管不到也不该管
     * (`:feature:server` 不认识 `AppSessionViewModel`)。
     */
    fun confirmDeleteServer() {
        val id = _uiState.value.deleteConfirmation ?: return
        viewModelScope.launch {
            try {
                serverStore.delete(id)
                if (activeServerId == id) {
                    serverStore.clearActive()
                    cachedItemDao.clearServer(id)
                    audioCacheStore.clearServer(id)
                    lastPlayedStore.clear()
                }
                _uiState.update { it.copy(deleteConfirmation = null, error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(deleteConfirmation = null, error = "删除服务器失败:${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
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

/**
 * 登录失败(`/Users/AuthenticateByName`)的人话错误文案。
 *
 * 对这个接口而言 401 只有一种含义——凭据被拒绝,不是"未知错误"——所以单独给出针对性提示,
 * 覆盖案发时验证过的两个常见根因:大小写(密码大小写敏感)和软键盘自动纠错/自动大写插入的
 * 首尾空格(见 AddServerScreen.kt 的键盘修复)。其它 HTTP 状态码与网络层异常(IOException,
 * 如断线/DNS 失败)分别给出可区分的文案,不折叠成同一句话,方便用户和开发自己判断故障类型。
 * 绝不能把密码或异常堆栈拼进文案。
 */
internal fun buildLoginErrorMessage(e: Exception): String = when {
    e is HttpException && e.code() == 401 ->
        "登录失败:用户名或密码不正确。请检查密码大小写是否正确,以及首尾是否有多余的空格。"
    e is HttpException ->
        "登录失败:服务器返回错误(HTTP ${e.code()})。"
    e is IOException ->
        "登录失败:无法连接到服务器,请检查网络连接。"
    else ->
        "登录失败:${e.javaClass.simpleName}"
}
