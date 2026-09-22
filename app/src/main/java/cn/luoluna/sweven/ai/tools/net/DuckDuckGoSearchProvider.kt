package cn.luoluna.sweven.ai.tools.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * DuckDuckGo HTML 版抓取（免密钥，兜底搜索源）。
 *
 * 抓 `html.duckduckgo.com/html/`，解析 `result__a` / `result__snippet`；
 * 结果链接是 `/l/?uddg=<encoded>` 跳转形式，[resolveDdgUrl] 还原真实地址。
 * 国内网络访问 DDG 通常需要代理，因此它排在 Bing 之后作为第二源。
 */
object DuckDuckGoSearchProvider : SearchProvider {
    override val kind: String = SearchProviderKind.DUCKDUCKGO
    override val displayName: String = "DuckDuckGo 抓取"

    override suspend fun search(
        options: SearchOptions,
        query: String,
        count: Int,
        freshness: String?,
        site: String?,
    ): List<SearchItem> {
        val url = buildString {
            append("https://html.duckduckgo.com/html/?q=")
            append(encode(queryWithSite(query, site)))
            freshnessParam(freshness)?.let { append("&df=").append(it) }
        }
        return parse(httpGetText(url), count)
    }

    /** freshness → DDG 的 df 参数 */
    internal fun freshnessParam(freshness: String?): String? = when (freshness) {
        "day" -> "d"
        "week" -> "w"
        "month" -> "m"
        else -> null
    }

    private val RESULT = Regex(
        """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SNIPPET = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    internal fun parse(html: String, limit: Int = 10): List<SearchItem> {
        val matches = RESULT.findAll(html).toList()
        return matches.mapIndexedNotNull { index, match ->
            val url = resolveDdgUrl(HtmlText.unescape(match.groupValues[1]))
            val title = HtmlText.text(match.groupValues[2])
            if (title.isBlank() || url.isBlank()) return@mapIndexedNotNull null
            // 摘要限定在「本条结果到下一结果之间」，避免串到别人的摘要
            val end = matches.getOrNull(index + 1)?.range?.first ?: html.length
            val snippet = SNIPPET.find(html, match.range.last + 1)
                ?.takeIf { it.range.first < end }
                ?.groupValues?.get(1)
                ?.let { HtmlText.text(it) }
                .orEmpty()
            SearchItem(index = 0, title = title, url = url, snippet = snippet)
        }.distinctBy { it.url }.take(limit)
    }

    /** DDG 结果链接是 `/l/?uddg=<encoded>` 跳转形式，还原真实地址（解析不出时原样返回） */
    internal fun resolveDdgUrl(raw: String): String {
        val absolute = HtmlText.absoluteUrl("https://duckduckgo.com", raw)
        val httpUrl = absolute.toHttpUrlOrNull() ?: return absolute
        val target = httpUrl.queryParameter("uddg") ?: return absolute
        return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(absolute)
    }
}
