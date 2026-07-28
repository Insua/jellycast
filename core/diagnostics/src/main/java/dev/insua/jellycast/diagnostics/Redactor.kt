package dev.insua.jellycast.diagnostics

/**
 * 诊断日志的脱敏规则(design doc §5 / CLAUDE.md 铁律 5:"不允许全局关闭 TLS 校验" 那条铁律旁边的
 * 精神一致——凭据绝不落盘)。
 *
 * 和 `app/src/androidTest/.../TestCredentials.redact` 同一个出发点,但那边知道真实凭据的
 * **精确值**(测试配置注入),可以做精确字符串替换;这里不行——真实崩溃发生的那一刻,我们不
 * 提前知道消息文本里会混进哪个具体的 token/密码。所以这里改用**结构定位**:按"看起来像什么"
 * 而不是"等于什么"来识别并抹掉:
 *
 * 1. URL 形状(`scheme://host[:port][/path...]`)—— 命中后只保留 scheme + 主机名的第一段,
 *    其余(完整域名/端口/路径/查询串,查询串里常常就藏着 `api_key=`)整体丢弃。
 * 2. `key=value` / `key: value` 形状,key 命中一组已知的凭据字段名(token、password、
 *    Authorization 等,大小写不敏感,兼容 `Authorization: Bearer <token>` 这种前缀)——命中后
 *    整个值替换成 `<redacted>`。
 *
 * 刻意不做的事:不去猜"看起来像 32 位十六进制的字符串就是 token"之类的宽泛启发式——Jellyfin 的
 * item id / session id 也是这个形状,过度脱敏会把诊断信息废掉一半,而且是在没有核对过
 * `docs/jellyfin-openapi.json` 的情况下凭记忆猜格式,违反 CLAUDE.md 铁律 2。
 */
internal object Redactor {

    private val CREDENTIAL_KV_PATTERN = Regex(
        pattern = """(?i)\b(api_key|access_token|token|password|pwd|pass|secret|authorization|""" +
            """x-emby-token|x-mediabrowser-token)\s*[:=]\s*(bearer\s+)?[^\s&"'<>]+""",
    )

    private val URL_PATTERN = Regex("""(https?)://([A-Za-z0-9._-]+)(:\d+)?(/[^\s"'<>]*)?""")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()

        // 先处理裸露的 key=value 凭据,再折叠 URL——顺序不敏感(URL 正则会把查询串整体吞掉),
        // 但先做 KV 能覆盖 URL 之外单独出现的 "token=..." / "password=..." 片段。
        var out = CREDENTIAL_KV_PATTERN.replace(text) { match -> "${match.groupValues[1]}=<redacted>" }
        out = URL_PATTERN.replace(out) { match ->
            val scheme = match.groupValues[1]
            val host = match.groupValues[2]
            val firstLabel = host.substringBefore('.')
            "$scheme://$firstLabel.<redacted>"
        }
        return out
    }
}
