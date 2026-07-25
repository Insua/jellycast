package dev.insua.jellycast.feature.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 规整化用户在「添加服务器」表单里输入的接入地址:去首尾空白、去掉路径产生的尾部斜杠,
 * 并校验 scheme 是否为 http/https。
 *
 * 用 OkHttp [okhttp3.HttpUrl] 解析——这与 :core:network 的
 * [dev.insua.jellycast.network.buildHealthCheckUrl] 用的是同一套解析/渲染机制,IPv6 host 在
 * [okhttp3.HttpUrl.toString] 渲染时总是带方括号,所以 `https://[240e::1]:8920` 这类地址不会被
 * 手写字符串拼接/裁剪破坏方括号形式。
 *
 * 返回 null 表示这不是一个合法的 http(s) 服务器地址,调用方应在表单上提示错误、不得提交。
 */
fun normalizeEndpointUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val httpUrl = trimmed.toHttpUrlOrNull() ?: return null
    if (httpUrl.scheme != "http" && httpUrl.scheme != "https") return null
    // 这个字段是服务器根地址(如 "http://host:port"),不是资源 URL——HttpUrl 对空路径会渲染出
    // 一个多余的尾部斜杠("http://host:8096/"),这里统一去掉,使存储形式与设计文档里的示例一致。
    return httpUrl.toString().removeSuffix("/")
}
