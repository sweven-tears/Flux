package cn.luoluna.sweven.ai.tools.net

/**
 * SearXNG 自建/公共实例（免密钥，最稳的路径）。
 *
 * 需要实例在 `settings.yml` 的 `search.formats` 里开启 `json`（默认只开 html）；
 * 实例地址由用户在「工具中心 → 联网搜索」里填写。
 */
object SearXNGSearchProvider : SearchProvider {
    override val kind: String = SearchProviderKind.SEARXNG
    override val displayName: String = "SearXNG 实例"
    override val needsEndpoint: Boolean = true

    override suspend fun search(
        options: SearchOptions,
        query: String,
        count: Int,
        freshness: String?,
        site: String?,
    ): List<SearchItem> {
        val base = options.endpoint?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("未填写 SearXNG 实例地址")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw IllegalStateException("实例地址需以 http:// 或 https:// 开头")
        }
        val url = buildString {
            append(base)
            append("/search?q=").append(encode(queryWithSite(query, site)))
            append("&format=json&safesearch=0&categories=general")
            timeRange(freshness)?.let { append("&time_range=").append(it) }
        }
        val body = httpGetText(url, timeoutSeconds = 20, searchOptions = options)
        if (!body.trimStart().startsWith("{")) {
            throw IllegalStateException("实例未开启 JSON 格式（需在实例 search.formats 里加 json）")
        }
        return SearchChain.parseSearxItems(body, count)
    }

    /** freshness → SearXNG 的 time_range */
    internal fun timeRange(freshness: String?): String? = when (freshness) {
        "day" -> "day"
        "week" -> "week"
        "month" -> "month"
        else -> null
    }
}
