package cn.luoluna.sweven.ai.tools.net

import java.net.URLEncoder

/**
 * Bing 网页抓取（免密钥，默认搜索源）。
 *
 * 抓 `bing.com/search` 的 HTML，按 `li.b_algo` 分块解析标题/链接/摘要。
 * 免密钥抓取的不确定性（反爬页、结构改版、需要代理）由 [SearchChain] 的多源回退兜底；
 * 这里只负责「解析」这件事，[parse] 是纯函数、单测覆盖，改版时只改选择器。
 */
object BingSearchProvider : SearchProvider {
    override val kind: String = SearchProviderKind.BING
    override val displayName: String = "Bing 抓取"

    override suspend fun search(
        options: SearchOptions,
        query: String,
        count: Int,
        freshness: String?,
        site: String?,
    ): List<SearchItem> {
        val url = buildString {
            append("https://www.bing.com/search?q=")
            append(encode(queryWithSite(query, site)))
            append("&setlang=zh-CN&cc=CN&count=")
            append(count.coerceIn(1, 20))
            freshnessFilter(freshness)?.let { append("&filters=").append(encode(it)) }
        }
        return parse(httpGetText(url), count)
    }

    /** freshness → Bing filters 表达式 */
    internal fun freshnessFilter(freshness: String?): String? = when (freshness) {
        "day" -> """ex1:"ez1""""
        "week" -> """ex1:"ez2""""
        "month" -> """ex1:"ez3""""
        else -> null
    }

    private val H2_ANCHOR = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val ANY_ANCHOR = Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val PARAGRAPH = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    internal fun parse(html: String, limit: Int = 10): List<SearchItem> {
        val chunks = html.split("""<li class="b_algo""").drop(1)
        return chunks.mapNotNull { chunk ->
            val anchor = H2_ANCHOR.find(chunk) ?: ANY_ANCHOR.find(chunk) ?: return@mapNotNull null
            val url = HtmlText.unescape(anchor.groupValues[1]).trim()
            val title = HtmlText.text(anchor.groupValues[2])
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            // Bing 自身链接（跳转/广告）不算结果
            if (url.startsWith("https://www.bing.com/") || url.startsWith("https://bing.com/")) return@mapNotNull null
            val snippet = PARAGRAPH.find(chunk)?.groupValues?.get(1)?.let { HtmlText.text(it) }.orEmpty()
            SearchItem(index = 0, title = title, url = url, snippet = snippet)
        }.distinctBy { it.url }.take(limit)
    }
}

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
