package dev.insua.jellycast.subtitle

/**
 * SRT/VTT/ASS 共用的标签清洗逻辑。
 *
 * Jellyfin 把 ASS 字幕转成 SRT/VTT 时不会剥离 ASS 特效覆盖标签(`{\pos(...)}`、
 * `{\fn...}`、`{\r}` 等),这些标签会原样出现在实测数据里,必须与 HTML 样式标签
 * (`<i>`、`<b>` 等)一起剥离,否则会直接展示给用户。
 */
internal object SubtitleTags {
    // 正则编译故意放进 `by lazy` 而不是 object 属性初始化器:后者在 JVM 规范下属于类的
    // <clinit>(静态初始化),任何异常都会被包装成 ExceptionInInitializerError——那是
    // Error 而不是 Exception,`catch (e: Exception)` 接不住,会一路穿透到进程被杀
    // (真实发生过一次,见 docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2)。
    // `by lazy` 把编译挪到首次访问时的一次普通函数调用里,失败时抛出的是原始异常类型本身
    // (这里是 PatternSyntaxException,是 Exception),可以被正常 catch 兜住——即便未来
    // 这两个模式串又出现新的平台差异,也不会再以不可捕获的 Error 形式炸穿进程。
    //
    // 注意:`}` 必须转义成 `\}`。裸 `}` 在 OpenJDK 的 java.util.regex 上合法(只有 `{` 有特殊
    // 含义),但在 Android 14+ 换用的 ICU 正则引擎上两个花括号都是元字符,裸 `}` 是语法错误。
    private val HTML_TAG by lazy { Regex("""</?[a-zA-Z][^>]*>""") }
    private val EFFECT_TAG by lazy { Regex("""\{[^}]*\}""") }

    /** 去掉字符串开头的 UTF-8 BOM(如果存在)。 */
    fun stripBom(content: String): String = content.removePrefix("﻿")

    /** 剥离 HTML 标签与 ASS/花括号特效标签,并 trim。 */
    fun stripTags(text: String): String =
        EFFECT_TAG.replace(HTML_TAG.replace(text, ""), "").trim()
}
