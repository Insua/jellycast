package dev.insua.jellycast.e2e

import dev.insua.jellycast.BuildConfig
import org.junit.Assume.assumeTrue

/**
 * 端到端测试的凭据来源:项目根的 `testing.properties` → debug 变体的 BuildConfig(见
 * `app/build.gradle.kts`)。
 *
 * 🔴 **这三个值绝不允许出现在任何输出里** —— 不许 log,不许 println,不许拼进断言消息,
 * 不许拼进异常消息,不许出现在任务报告或 commit message 里。需要在失败信息里指代服务器时,
 * 用 [redact] 把 URL/token 抹掉。
 *
 * 缺失时用 JUnit `Assume` **跳过**而不是失败:CI 上没有真实服务器,不该因此变红。
 */
object TestCredentials {
    val baseUrl: String get() = BuildConfig.E2E_BASE_URL
    val username: String get() = BuildConfig.E2E_USERNAME
    val password: String get() = BuildConfig.E2E_PASSWORD

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** 放在每个端到端测试的开头。 */
    fun assumeConfigured() {
        assumeTrue(
            "根目录缺少 testing.properties(或其中某项为空),跳过端到端测试。" +
                "参考 testing.properties.example 配置后再跑。",
            isConfigured,
        )
    }

    /**
     * 把任意文本里的服务器地址、用户名、access token 换成占位符,再交给断言消息 / 日志。
     *
     * 为什么需要它:失败信息里最有价值的是「HTTP 401」「Content-Type 不是 audio 开头」这类事实,
     * 而这些事实往往裹在一条带 `api_key=` 的完整流 URL 里。抹掉之后信息量不减,凭据不泄漏。
     */
    fun redact(text: String?): String {
        if (text == null) return "<null>"
        var out = text
        if (baseUrl.isNotBlank()) out = out.replace(baseUrl, "<server>")
        if (username.isNotBlank()) out = out.replace(username, "<user>")
        if (password.isNotBlank()) out = out.replace(password, "<password>")
        // token 不是编译期常量,只能按 URL 参数形状抹。
        out = API_KEY_PATTERN.replace(out, "api_key=<token>")
        return out
    }

    private val API_KEY_PATTERN = Regex("api_key=[^&\\s\"]*")
}
