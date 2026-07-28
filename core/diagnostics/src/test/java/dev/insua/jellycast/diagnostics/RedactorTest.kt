package dev.insua.jellycast.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 铁律 5(CLAUDE.md)+ design doc §5:诊断日志绝不能包含 token / password / 服务器地址完整形式。
 * 这里的断言只关心"敏感值绝对不出现在输出里"——这是最容易静默回归、后果最重的一类要求。
 */
class RedactorTest {

    private val token = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
    private val password = "Sup3rSecretPassw0rd!"
    private val fullUrl = "https://home.example-jellycast-lab.net:8096/Users/AuthenticateByName"

    @Test fun `access token 不会出现在脱敏结果里`() {
        val out = Redactor.redact("请求失败,token=$token 已过期")
        assertFalse(out.contains(token))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun `password 不会出现在脱敏结果里`() {
        val out = Redactor.redact("登录失败:username=alice password=$password")
        assertFalse(out.contains(password))
        assertTrue(out.contains("<redacted>"))
    }

    @Test fun `完整服务器地址不会出现在脱敏结果里,只保留 scheme 和主机名首段`() {
        val out = Redactor.redact("连接失败:$fullUrl")
        assertFalse(out.contains(fullUrl))
        assertFalse(out.contains("example-jellycast-lab.net"))
        assertFalse(out.contains("/Users/AuthenticateByName"))
        assertTrue(out.contains("https://home.<redacted>"))
    }

    @Test fun `Authorization Bearer 头不会出现在脱敏结果里`() {
        val out = Redactor.redact("HTTP 请求头 Authorization: Bearer $token 被拒绝")
        assertFalse(out.contains(token))
    }

    @Test fun `api_key 查询参数不会出现在脱敏结果里`() {
        val out = Redactor.redact("流地址探测失败:https://nas.mylab.dev/Audio/x/universal?api_key=$token")
        assertFalse(out.contains(token))
        assertFalse(out.contains("nas.mylab.dev/Audio"))
    }

    @Test fun `一条消息里同时混有多个凭据,全部不出现`() {
        val out = Redactor.redact(
            "login for user=bob password=$password against $fullUrl token=$token",
        )
        assertFalse(out.contains(password))
        assertFalse(out.contains(token))
        assertFalse(out.contains(fullUrl))
    }

    @Test fun `不含凭据的普通诊断信息原样保留,不被过度脱敏`() {
        val out = Redactor.redact("播放引擎进入 Error 状态:itemId=ep123,errorCode=BEHIND_LIVE_WINDOW")
        assertTrue(out.contains("itemId=ep123"))
        assertTrue(out.contains("errorCode=BEHIND_LIVE_WINDOW"))
    }

    @Test fun `null 和空字符串安全处理`() {
        assertTrue(Redactor.redact(null).isEmpty())
        assertTrue(Redactor.redact("").isEmpty())
    }
}
