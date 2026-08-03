package dev.insua.jellycast.feature.server

import dev.insua.jellycast.database.CachedItemDao
import dev.insua.jellycast.datastore.LastPlayedStore
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import dev.insua.jellycast.model.Server
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.dto.AuthResultDto
import dev.insua.jellycast.network.dto.UserDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.security.cert.X509Certificate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {

    private val serverStore = mockk<ServerStore>(relaxed = true)
    private val endpointSelector = mockk<EndpointSelector>()
    private val jellyfinApiFactory = mockk<JellyfinApiFactory>()
    private val cachedItemDao = mockk<CachedItemDao>(relaxed = true)
    private val lastPlayedStore = mockk<LastPlayedStore>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { serverStore.servers } returns flowOf(emptyList())
        every { serverStore.activeServerId } returns flowOf(null)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(certificateFetcher: PeerCertificateFetcher = mockk(relaxed = true)) =
        ServerViewModel(
            serverStore, endpointSelector, jellyfinApiFactory, cachedItemDao, lastPlayedStore, certificateFetcher,
        )

    private fun server(id: String, name: String = "server-$id") =
        Server(id = id, name = name, endpoints = listOf(lan()))

    /** 构造一张可被 [sha256Fingerprint] 计算出确定性指纹的假证书,内容本身无所谓。 */
    private fun fakeCertificate(seed: Byte): X509Certificate {
        val cert = mockk<X509Certificate>()
        every { cert.encoded } returns byteArrayOf(seed, 1, 2, 3)
        return cert
    }

    private fun lan(url: String = "http://192.168.1.10:8096") = Endpoint(url, "局域网", 0)
    private fun tailscale(url: String = "http://100.126.20.77:8096") = Endpoint(url, "Tailscale", 1)
    private fun public(url: String = "https://xxx.ddns.net:8920") = Endpoint(url, "公网", 2)

    private fun ServerViewModel.fillEndpoints(vararg endpoints: Endpoint) {
        endpoints.forEachIndexed { index, endpoint ->
            if (index > 0) addEndpointField()
            onEndpointUrlChange(index, endpoint.url)
            onEndpointLabelChange(index, endpoint.label)
        }
    }

    // ---- 修正 §1 场景 1:全部地址不可达 → uiState.error 包含每个地址各自的失败原因 ----

    @Test
    fun `全部地址不可达时给出可诊断的错误`() = runTest {
        val e1 = lan()
        val e2 = tailscale()
        val e3 = public()
        coEvery { endpointSelector.probeAll(listOf(e1, e2, e3)) } returns listOf(
            EndpointHealth(e1, false, null, "ConnectException: Connection refused"),
            EndpointHealth(e2, false, null, "SocketTimeoutException: timeout"),
            EndpointHealth(e3, false, null, "UnknownHostException: xxx.ddns.net"),
        )

        val vm = viewModel()
        vm.fillEndpoints(e1, e2, e3)
        vm.testConnection()

        val error = vm.uiState.value.error
        assertNotNull(error)
        // 每个地址各自的失败原因都要出现在错误文案里,而不是一句笼统的"连接失败"。
        assertTrue(error!!.contains("ConnectException: Connection refused"), error)
        assertTrue(error.contains("SocketTimeoutException: timeout"), error)
        assertTrue(error.contains("UnknownHostException: xxx.ddns.net"), error)
        assertEquals(3, vm.uiState.value.diagnostics.size)
        assertTrue(vm.uiState.value.diagnostics.none { it.reachable })
    }

    // ---- 修正 §1 场景 2:登录成功 → ServerStore.upsert 被调用且 accessToken 非空 ----

    @Test
    fun `登录成功后保存 token 与 userId`() = runTest {
        val endpoint = lan()
        val winner = EndpointHealth(endpoint, true, 12L)
        coEvery { endpointSelector.select(listOf(endpoint)) } returns winner

        val fakeApi = mockk<JellyfinApi>()
        coEvery { fakeApi.authenticate(AuthRequestDto(username = "bob", pw = "secret")) } returns
            AuthResultDto(accessToken = "tok-123", user = UserDto(id = "user-1", name = "bob"))
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.fillEndpoints(endpoint)
        vm.onUsernameChange("bob")
        vm.onPasswordChange("secret")

        vm.submit()

        coVerify(exactly = 1) {
            serverStore.upsert(match { server ->
                server.name == "我的 NAS" &&
                    server.accessToken == "tok-123" &&
                    server.userId == "user-1"
            })
        }
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.connectedServerId)
    }

    // ---- 修正 §1 场景 3:Tailscale 超时且无其他可用地址 → 错误文案包含 "Tailscale" ----

    @Test
    fun `Tailscale 地址超时且无其他可用地址时提示检查 Tailscale`() = runTest {
        val e1 = lan()
        val e2 = tailscale()
        coEvery { endpointSelector.probeAll(listOf(e1, e2)) } returns listOf(
            EndpointHealth(e1, false, null, "ConnectException: Connection refused"),
            EndpointHealth(e2, false, null, "SocketTimeoutException: timeout"),
        )

        val vm = viewModel()
        vm.fillEndpoints(e1, e2)
        vm.testConnection()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Tailscale"), error)
        assertTrue(error.contains("检查 Tailscale 是否已连接"), error)
    }

    // ---- 反例:Tailscale 超时,但另有可用地址 → 不应出现"检查 Tailscale"提示 ----

    @Test
    fun `Tailscale 超时但有其它可用地址时不提示检查 Tailscale`() = runTest {
        val e1 = lan()
        val e2 = tailscale()
        coEvery { endpointSelector.probeAll(listOf(e1, e2)) } returns listOf(
            EndpointHealth(e1, true, 30L),
            EndpointHealth(e2, false, null, "SocketTimeoutException: timeout"),
        )

        val vm = viewModel()
        vm.fillEndpoints(e1, e2)
        vm.testConnection()

        assertNull(vm.uiState.value.error)
    }

    // ---- URL 规整化在提交前生效:表单里的地址会先规整化再送去探测 ----

    @Test
    fun `地址末尾多余斜杠会被规整化后再探测`() = runTest {
        val endpoint = lan()
        coEvery { endpointSelector.probeAll(listOf(endpoint)) } returns listOf(EndpointHealth(endpoint, true, 5L))

        val vm = viewModel()
        vm.onEndpointUrlChange(0, "${endpoint.url}/")
        vm.onEndpointLabelChange(0, endpoint.label)
        vm.testConnection()

        coVerify(exactly = 1) { endpointSelector.probeAll(listOf(endpoint)) }
    }

    // ---- 证书确认指纹必须落在被展示证书的那个 endpoint 上,而不是表单里靠位置数出来的行 ----
    // 这条用例复现审查发现的 bug:diagnostics 是 validEndpoints() 压缩过的列表(空行被
    // mapIndexedNotNull 丢弃),confirmCertificate() 原来却拿 diagnostics 的下标去索引未压缩的
    // form.endpoints —— 只要目标地址前面有一行空白,两个下标就会错位。

    @Test
    fun `确认证书指纹应写入目标地址而不是前面的空行`() = runTest {
        val certificateFetcher = mockk<PeerCertificateFetcher>()
        coEvery { certificateFetcher.fetch(any()) } returns fakeCertificate(1)

        val targetUrl = "http://192.168.1.10:8096"
        // 用 any() + captured 参数回填结果,而不是手写期望的 Endpoint(priority 取决于表单里的
        // 原始下标,不是压缩后的下标,写死会让这条用例因为 mock 参数不匹配而误报,而不是真正
        // 验证证书指纹落位是否正确)。
        val probedEndpoints = slot<List<Endpoint>>()
        coEvery { endpointSelector.probeAll(capture(probedEndpoints)) } answers {
            probedEndpoints.captured.map { EndpointHealth(it, false, null, "SSLHandshakeException: self-signed certificate") }
        }

        val vm = viewModel(certificateFetcher)
        // 表单默认就有一行空白(index 0);目标地址填在它后面的第二行(index 1)。
        vm.addEndpointField()
        vm.onEndpointUrlChange(1, targetUrl)
        vm.onEndpointLabelChange(1, "局域网")
        vm.testConnection()

        // 压缩后 diagnostics 只有一条,对应表单里的第二行(空白行被过滤掉了)。
        assertEquals(1, vm.uiState.value.diagnostics.size)

        vm.onInspectCertificate(0)
        val confirmation = vm.uiState.value.certConfirmation
        assertNotNull(confirmation, "应该弹出证书确认对话框")

        vm.confirmCertificate()

        val endpoints = vm.uiState.value.form.endpoints
        assertEquals(2, endpoints.size)
        assertNull(endpoints[0].trustedCertSha256, "空白行不应该被写入指纹")
        assertEquals(
            confirmation!!.fingerprint,
            endpoints[1].trustedCertSha256,
            "指纹应该写入用户实际看到证书的那个地址行",
        )
    }

    @Test
    fun `确认证书指纹不影响其它地址行`() = runTest {
        val certificateFetcher = mockk<PeerCertificateFetcher>()
        coEvery { certificateFetcher.fetch(any()) } returns fakeCertificate(2)

        val e1 = lan()
        val e2 = tailscale()
        val e3 = public()
        coEvery { endpointSelector.probeAll(listOf(e1, e2, e3)) } returns listOf(
            EndpointHealth(e1, true, 10L),
            EndpointHealth(e2, false, null, "SSLHandshakeException: self-signed certificate"),
            EndpointHealth(e3, false, null, "UnknownHostException: xxx.ddns.net"),
        )

        val vm = viewModel(certificateFetcher)
        vm.fillEndpoints(e1, e2, e3)
        vm.testConnection()

        vm.onInspectCertificate(1)
        assertNotNull(vm.uiState.value.certConfirmation)
        vm.confirmCertificate()

        val endpoints = vm.uiState.value.form.endpoints
        assertNull(endpoints[0].trustedCertSha256)
        assertNotNull(endpoints[1].trustedCertSha256)
        assertNull(endpoints[2].trustedCertSha256)
    }

    @Test
    fun `取消证书确认不写入任何指纹`() = runTest {
        val certificateFetcher = mockk<PeerCertificateFetcher>()
        coEvery { certificateFetcher.fetch(any()) } returns fakeCertificate(3)

        val target = lan()
        coEvery { endpointSelector.probeAll(listOf(target)) } returns listOf(
            EndpointHealth(target, false, null, "SSLHandshakeException: self-signed certificate"),
        )

        val vm = viewModel(certificateFetcher)
        vm.fillEndpoints(target)
        vm.testConnection()

        vm.onInspectCertificate(0)
        assertNotNull(vm.uiState.value.certConfirmation)

        vm.dismissCertificateConfirmation()

        assertNull(vm.uiState.value.certConfirmation)
        assertTrue(vm.uiState.value.form.endpoints.all { it.trustedCertSha256 == null })
    }

    // ---- 输入清洗:用户名与地址前后空白必须在到达 API 前被清掉,密码原样保留 ----
    // 根因(见任务描述):软键盘对用户名/密码字段做自动首字母大写/自动纠错,导致密码被静默
    // 篡改;键盘修复在 AddServerScreen.kt(Compose UI,不在本文件覆盖范围)。这里只覆盖
    // ServerViewModel 这一层能单测的部分:提交前的字符串清洗策略。

    @Test
    fun `提交时用户名前后空白被裁剪但密码原样保留`() = runTest {
        val endpoint = lan()
        val winner = EndpointHealth(endpoint, true, 12L)
        coEvery { endpointSelector.select(listOf(endpoint)) } returns winner

        val fakeApi = mockk<JellyfinApi>()
        val authRequest = slot<AuthRequestDto>()
        coEvery { fakeApi.authenticate(capture(authRequest)) } returns
            AuthResultDto(accessToken = "tok-123", user = UserDto(id = "user-1", name = "bob"))
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.fillEndpoints(endpoint)
        vm.onUsernameChange("  bob  ")
        vm.onPasswordChange("  secret  ")

        vm.submit()

        assertEquals("bob", authRequest.captured.username, "用户名前后空白应被裁剪")
        assertEquals("  secret  ", authRequest.captured.pw, "密码不允许被裁剪——首尾空格可能是密码的一部分")
    }

    @Test
    fun `提交时地址前后空白被裁剪`() = runTest {
        val endpoint = lan()
        val winner = EndpointHealth(endpoint, true, 12L)
        coEvery { endpointSelector.select(listOf(endpoint)) } returns winner

        val fakeApi = mockk<JellyfinApi>(relaxed = true)
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.onEndpointUrlChange(0, "  ${endpoint.url}  ")
        vm.onEndpointLabelChange(0, endpoint.label)
        vm.onUsernameChange("bob")
        vm.onPasswordChange("secret")

        vm.submit()

        // select() 只会被裁剪后的 endpoint 列表调用——如果地址带着空白被传下去,
        // 这个 mock 匹配不上,select() 返回 null,登录会走"无法连接"分支而不是成功分支。
        coVerify(exactly = 1) { endpointSelector.select(listOf(endpoint)) }
        assertNotNull(vm.uiState.value.connectedServerId)
    }

    // ---- 401 → 人话错误文案,且与其它失败原因(500 / IO 异常)可区分 ----

    private fun httpException(code: Int, message: String = "Error"): HttpException {
        val body = "{}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `登录返回 401 时提示用户名或密码不正确`() = runTest {
        val endpoint = lan()
        coEvery { endpointSelector.select(listOf(endpoint)) } returns EndpointHealth(endpoint, true, 12L)
        val fakeApi = mockk<JellyfinApi>()
        coEvery { fakeApi.authenticate(any()) } throws httpException(401, "Unauthorized")
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.fillEndpoints(endpoint)
        vm.onUsernameChange("bob")
        vm.onPasswordChange("wrong")

        vm.submit()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("用户名或密码不正确"), error)
        // 提示要覆盖案发根因:大小写与首尾空格,但绝不能把密码本身带出来。
        assertTrue(error.contains("大小写") || error.contains("空格"), error)
        assertFalse(error.contains("wrong"), "错误文案不得包含密码内容")
        assertNull(vm.uiState.value.connectedServerId)
    }

    @Test
    fun `登录返回 500 时提示与 401 不同`() = runTest {
        val endpoint = lan()
        coEvery { endpointSelector.select(listOf(endpoint)) } returns EndpointHealth(endpoint, true, 12L)
        val fakeApi = mockk<JellyfinApi>()
        coEvery { fakeApi.authenticate(any()) } throws httpException(500, "Internal Server Error")
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.fillEndpoints(endpoint)
        vm.onUsernameChange("bob")
        vm.onPasswordChange("secret")

        vm.submit()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertFalse(error!!.contains("用户名或密码不正确"), error)
        assertTrue(error.contains("500"), error)
    }

    @Test
    fun `登录时网络异常提示与 401_500 都不同`() = runTest {
        val endpoint = lan()
        coEvery { endpointSelector.select(listOf(endpoint)) } returns EndpointHealth(endpoint, true, 12L)
        val fakeApi = mockk<JellyfinApi>()
        coEvery { fakeApi.authenticate(any()) } throws IOException("Connection reset")
        every { jellyfinApiFactory.create(endpoint) } returns fakeApi

        val vm = viewModel()
        vm.onNameChange("我的 NAS")
        vm.fillEndpoints(endpoint)
        vm.onUsernameChange("bob")
        vm.onPasswordChange("secret")

        vm.submit()

        val error = vm.uiState.value.error
        assertNotNull(error)
        assertFalse(error!!.contains("用户名或密码不正确"), error)
        assertFalse(error.contains("500"), error)
    }

    @Test
    fun `buildLoginErrorMessage 对 401_其它状态码_IO异常给出三种不同文案`() {
        val unauthorized = buildLoginErrorMessage(httpException(401))
        val serverError = buildLoginErrorMessage(httpException(503, "Service Unavailable"))
        val ioError = buildLoginErrorMessage(IOException("timeout"))

        assertNotEquals(unauthorized, serverError)
        assertNotEquals(unauthorized, ioError)
        assertNotEquals(serverError, ioError)
        assertTrue(unauthorized.contains("用户名或密码不正确"), unauthorized)
        assertTrue(serverError.contains("503"), serverError)
    }

    // ---- Task 4:删除服务器——误删会丢登录态,必须先二次确认,确认前绝不能调用 delete ----

    @Test
    fun `点删除仅弹出确认_未确认不调用delete`() = runTest {
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")

        assertEquals("srv-1", vm.uiState.value.deleteConfirmation)
        coVerify(exactly = 0) { serverStore.delete(any()) }
    }

    @Test
    fun `取消删除确认时不删除且清空确认弹窗`() = runTest {
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.dismissDeleteConfirmation()

        assertNull(vm.uiState.value.deleteConfirmation)
        coVerify(exactly = 0) { serverStore.delete(any()) }
    }

    @Test
    fun `确认删除后调用ServerStore_delete`() = runTest {
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()

        coVerify(exactly = 1) { serverStore.delete("srv-1") }
        assertNull(vm.uiState.value.deleteConfirmation)
    }

    @Test
    fun `删除当前活跃服务器时清掉活跃标记并清除该服务器缓存`() = runTest {
        every { serverStore.activeServerId } returns MutableStateFlow("srv-1")
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()

        coVerify(exactly = 1) { serverStore.delete("srv-1") }
        coVerify(exactly = 1) { serverStore.clearActive() }
        coVerify(exactly = 1) { cachedItemDao.clearServer("srv-1") }
    }

    // ---- 复审 Task 5 Important 1:删除活跃服务器必须清掉「上次播放」记录 ----
    // 之前只有 AppSessionViewModel.onServerConnected()(连接新服务器成功之后)才会清这份记录,
    // 删除活跃服务器这一步本身完全不碰 LastPlayedStore——如果用户删完不再连任何服务器,
    // 这条属于已删服务器的记录会一直留在磁盘上,下次冷启动迷你条恢复出来的条目连服务器都不存在了。

    @Test
    fun `删除当前活跃服务器时清掉上次播放记录`() = runTest {
        every { serverStore.activeServerId } returns MutableStateFlow("srv-1")
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()

        coVerify(exactly = 1) { lastPlayedStore.clear() }
    }

    @Test
    fun `删除非活跃服务器不清活跃标记_不清缓存_也不清上次播放记录`() = runTest {
        every { serverStore.activeServerId } returns MutableStateFlow("srv-other")
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()

        coVerify(exactly = 1) { serverStore.delete("srv-1") }
        coVerify(exactly = 0) { serverStore.clearActive() }
        coVerify(exactly = 0) { cachedItemDao.clearServer(any()) }
        coVerify(exactly = 0) { lastPlayedStore.clear() }
    }

    @Test
    fun `删到列表为空时uiState servers跟随store回到空列表`() = runTest {
        val serversFlow = MutableStateFlow(listOf(server("srv-1")))
        every { serverStore.servers } returns serversFlow
        coEvery { endpointSelector.select(any()) } returns null
        val vm = viewModel()

        assertEquals(1, vm.uiState.value.servers.size)

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()
        // 模拟真实 ServerStore.delete 落盘后 Flow 重新发射的效果——这里的 mock 不会
        // 自己触发这一步,手动推一次来验证 ViewModel 对 servers Flow 的订阅确实驱动了空状态。
        serversFlow.value = emptyList()

        assertTrue(vm.uiState.value.servers.isEmpty())
    }

    @Test
    fun `删除失败时给出提示_不静默_也不清活跃标记或缓存或上次播放记录`() = runTest {
        coEvery { serverStore.delete("srv-1") } throws IOException("disk full")
        val vm = viewModel()

        vm.requestDeleteServer("srv-1")
        vm.confirmDeleteServer()

        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.deleteConfirmation)
        coVerify(exactly = 0) { serverStore.clearActive() }
        coVerify(exactly = 0) { cachedItemDao.clearServer(any()) }
        coVerify(exactly = 0) { lastPlayedStore.clear() }
    }
}
