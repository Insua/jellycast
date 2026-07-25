package dev.insua.jellycast.feature.server

import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.dto.AuthResultDto
import dev.insua.jellycast.network.dto.UserDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {

    private val serverStore = mockk<ServerStore>(relaxed = true)
    private val endpointSelector = mockk<EndpointSelector>()
    private val jellyfinApiFactory = mockk<JellyfinApiFactory>()

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

    private fun viewModel() = ServerViewModel(serverStore, endpointSelector, jellyfinApiFactory)

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
}
