package dev.insua.jellycast.subtitle

/**
 * SRT/VTT/ASS 共用的标签清洗逻辑。
 *
 * Jellyfin 把 ASS 字幕转成 SRT/VTT 时不会剥离 ASS 特效覆盖标签(`{\pos(...)}`、
 * `{\fn...}`、`{\r}` 等),这些标签会原样出现在实测数据里,必须与 HTML 样式标签
 * (`<i>`、`<b>` 等)一起剥离,否则会直接展示给用户。
 */
internal object SubtitleTags {
    private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val EFFECT_TAG = Regex("""\{[^}]*}""")

    /** 去掉字符串开头的 UTF-8 BOM(如果存在)。 */
    fun stripBom(content: String): String = content.removePrefix("﻿")

    /** 剥离 HTML 标签与 ASS/花括号特效标签,并 trim。 */
    fun stripTags(text: String): String =
        EFFECT_TAG.replace(HTML_TAG.replace(text, ""), "").trim()
}
