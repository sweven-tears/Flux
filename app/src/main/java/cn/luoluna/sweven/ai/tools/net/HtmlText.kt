package cn.luoluna.sweven.ai.tools.net

/**
 * 极简 HTML 文本工具（不引入 jsoup：只做「取文本」这件事）。
 * 全部纯函数，单测覆盖。
 */
internal object HtmlText {

    private val TAG_REGEX = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT_REGEX = Regex("<(script|style|noscript)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ENTITY_REGEX = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    /** 去标签 + 反转义 + 折叠空白 → 单行纯文本 */
    fun text(html: String): String = collapse(unescape(stripTags(html)))

    /** 去掉所有标签（保留文本） */
    fun stripTags(html: String): String = TAG_REGEX.replace(html, " ")

    /** 去掉 script/style 整块（正文抓取时用） */
    fun stripScripts(html: String): String = SCRIPT_REGEX.replace(html, " ")

    /** 折叠连续空白为单空格并 trim */
    fun collapse(text: String): String = WHITESPACE_REGEX.replace(text, " ").trim()

    /** 常见 HTML 实体反转义 */
    fun unescape(text: String): String = ENTITY_REGEX.replace(text) { match ->
        val raw = match.groupValues[1]
        when {
            raw.startsWith("#x") || raw.startsWith("#X") ->
                raw.drop(2).toIntOrNull(16)?.let { code -> code.toChar().toString() } ?: match.value

            raw.startsWith("#") ->
                raw.drop(1).toIntOrNull()?.let { code -> code.toChar().toString() } ?: match.value

            else -> when (raw.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                "ensp", "emsp", "thinsp" -> " "
                "hellip" -> "…"
                "mdash" -> "—"
                "ndash" -> "–"
                else -> match.value
            }
        }
    }

    /** 相对/协议相对 URL → 绝对 URL（解析不出时原样返回） */
    fun absoluteUrl(base: String, url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> base.trimEnd('/') + trimmed
            else -> base.trimEnd('/') + "/" + trimmed
        }
    }
}
