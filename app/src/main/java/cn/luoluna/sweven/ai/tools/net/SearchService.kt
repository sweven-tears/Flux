package cn.luoluna.sweven.ai.tools.net

import cn.luoluna.sweven.ai.net.SharedHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 联网搜索的公共类型与 provider 注册表。
 *
 * 设计参考 `docs/rikkahub/search`（SearchService 接口 + 各 provider 实现），按本项目形态裁剪：
 * - V1 只做**免密钥**源：Bing HTML 抓取（默认）、DuckDuckGo HTML（兜底）、SearXNG（自填实例，最稳）
 * - 付费源（博查/智谱/Tavily/Brave）留 [SearchProviderKind] 与 [SearchOptions.apiKey] 的位置，
 *   后续加一个 provider 文件 + [SearchProviders.of] 分支即可接入
 * - 解析函数一律是纯函数（`parse(body)`），便于 JVM 单测用 fixture 覆盖、引擎改版时只改选择器
 */
data class SearchOptions(
    val kind: String = SearchProviderKind.BING,
    /** SearXNG 自填实例地址（如 https://searx.example.com） */
    val endpoint: String? = null,
    /** 付费源 API Key（本期未使用，留位） */
    val apiKey: String? = null,
)

data class SearchItem(
    val index: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val publishedDate: String? = null,
)

data class SearchResult(
    val provider: String,
    val retrievedAt: String,
    val items: List<SearchItem>,
)

interface SearchProvider {
    val kind: String
    val displayName: String

    /** true = 必须由用户填写实例地址（SearXNG） */
    val needsEndpoint: Boolean get() = false

    /**
     * 执行一次搜索；返回结果列表（空列表视为「这个源没给出结果」，由 [SearchChain] 决定是否回退）。
     * 失败请抛异常（消息给用户看）。
     */
    suspend fun search(
        options: SearchOptions,
        query: String,
        count: Int,
        freshness: String?,
        site: String?,
    ): List<SearchItem>
}

object SearchProviderKind {
    const val BING = "bing"
    const val DUCKDUCKGO = "duckduckgo"
    const val SEARXNG = "searxng"

    /** 下拉顺序：默认源在前 */
    val all: List<String> = listOf(BING, DUCKDUCKGO, SEARXNG)

    fun isKnown(kind: String?): Boolean = kind != null && kind in all
}

object SearchProviders {
    private val providers: List<SearchProvider> = listOf(
        BingSearchProvider,
        DuckDuckGoSearchProvider,
        SearXNGSearchProvider,
    )

    fun all(): List<SearchProvider> = providers

    fun of(kind: String?): SearchProvider? = providers.firstOrNull { it.kind == kind }

    fun label(kind: String?): String = of(kind)?.displayName ?: of(SearchProviderKind.BING)!!.displayName
}

/** 常见浏览器 UA：免密钥抓取必须带，否则多数引擎直接返回反爬页 */
internal const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** 工具层的 GET（文本）：统一超时/UA/语言，非 2xx 抛带响应片段的异常 */
internal suspend fun httpGetText(
    url: String,
    headers: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 15,
): String = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_UA)
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
    headers.forEach { (name, value) -> builder.header(name, value) }
    val client = SharedHttp.client.newBuilder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
    try {
        client.newCall(builder.get().build()).execute().use { response ->
            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}（${body.take(120)}）")
            }
            body
        }
    } catch (e: CancellationException) {
        throw e
    }
}

/** 把 `site:xxx` 限定折进查询串（各引擎都支持该语法，比逐个适配参数更稳） */
internal fun queryWithSite(query: String, site: String?): String {
    val host = site?.trim()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: return query
    return "$query site:$host"
}
