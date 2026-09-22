package cn.luoluna.sweven.ai.tools.net

import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免密钥搜索源的解析与回退链测试（全部离线，用固定 fixture）：
 * 引擎改版时改选择器 → 这里先用 fixture 把预期钉住；[SearchChain] 用假 provider 验证回退与错误聚合。
 */
class SearchParseTest {

    // ---------------- Bing ----------------

    private val bingHtml = """
        <ol id="b_results">
          <li class="b_algo">
            <h2><a href="https://kotlinlang.org/docs/home.html">Kotlin 文档</a></h2>
            <div class="b_caption"><p>Kotlin 官方文档，包含语言指南与 API 参考。</p></div>
          </li>
          <li class="b_algo">
            <h2><a href="https://example.com/a?x=1&amp;y=2">示例 &amp; 站点</a></h2>
            <p>这是第二段摘要。</p>
          </li>
          <li class="b_algo">
            <h2><a href="https://www.bing.com/aclick?ld=xyz">Bing 广告</a></h2>
            <p>广告摘要</p>
          </li>
        </ol>
    """.trimIndent()

    @Test
    fun `Bing 抓取解析标题链接摘要并过滤站内链接`() {
        val items = BingSearchProvider.parse(bingHtml)
        assertEquals(2, items.size)
        assertEquals("Kotlin 文档", items[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", items[0].url)
        assertEquals("Kotlin 官方文档，包含语言指南与 API 参考。", items[0].snippet)
        // HTML 实体反转义 + 过滤 bing.com 站内链接
        assertEquals("示例 & 站点", items[1].title)
        assertEquals("https://example.com/a?x=1&y=2", items[1].url)
        assertEquals("这是第二段摘要。", items[1].snippet)
    }

    @Test
    fun `Bing 反爬页或空结果返回空列表`() {
        assertTrue(BingSearchProvider.parse("<html><body>no results</body></html>").isEmpty())
        assertTrue(BingSearchProvider.parse("").isEmpty())
    }

    @Test
    fun `Bing 时间范围映射为 filters 表达式`() {
        assertEquals("""ex1:"ez1"""", BingSearchProvider.freshnessFilter("day"))
        assertEquals("""ex1:"ez3"""", BingSearchProvider.freshnessFilter("month"))
        assertNull(BingSearchProvider.freshnessFilter("any"))
        assertNull(BingSearchProvider.freshnessFilter(null))
    }

    // ---------------- DuckDuckGo ----------------

    private val ddgHtml = """
        <div class="result results_links results_links_deep web-result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fpage&amp;rut=abc">示例页面</a>
          <a class="result__snippet" href="#">这是示例页面的摘要内容。</a>
        </div>
        <div class="result results_links results_links_deep web-result">
          <a rel="nofollow" class="result__a" href="https://direct.example.net/x">直链结果</a>
          <a class="result__snippet" href="#">第二个摘要。</a>
        </div>
    """.trimIndent()

    @Test
    fun `DuckDuckGo 解析并还原跳转链接`() {
        val items = DuckDuckGoSearchProvider.parse(ddgHtml)
        assertEquals(2, items.size)
        assertEquals("示例页面", items[0].title)
        assertEquals("https://example.org/page", items[0].url)
        assertEquals("这是示例页面的摘要内容。", items[0].snippet)
        assertEquals("直链结果", items[1].title)
        assertEquals("https://direct.example.net/x", items[1].url)
        assertEquals("第二个摘要。", items[1].snippet)
    }

    @Test
    fun `DuckDuckGo 时间范围映射为 df 参数`() {
        assertEquals("d", DuckDuckGoSearchProvider.freshnessParam("day"))
        assertEquals("m", DuckDuckGoSearchProvider.freshnessParam("month"))
        assertNull(DuckDuckGoSearchProvider.freshnessParam("any"))
    }

    // ---------------- SearXNG ----------------

    @Test
    fun `SearXNG 解析 JSON 结果`() {
        val body = """
            {"query":"kotlin","results":[
              {"title":"Kotlin <b>语言</b>","url":"https://kotlinlang.org","content":"官方站点 &amp; 文档","publishedDate":"2026-01-02"},
              {"title":"无链接条目","content":"应被跳过"},
              {"url":"https://example.com","content":"标题为空时回落 url"}
            ]}
        """.trimIndent()
        val items = SearchChain.parseSearxItems(body, 10)
        assertEquals(2, items.size)
        assertEquals("Kotlin 语言", items[0].title)
        assertEquals("官方站点 & 文档", items[0].snippet)
        assertEquals("2026-01-02", items[0].publishedDate)
        assertEquals("https://example.com", items[1].title)
    }

    @Test
    fun `SearXNG 未开启 JSON 时给出可读错误`() {
        val error = runCatching { runBlocking { SearXNGSearchProvider.search(SearchOptions(), "q", 5, null, null) } }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("实例地址"))
    }

    // ---------------- 回退链 ----------------

    private class FakeProvider(
        override val kind: String,
        private val items: List<SearchItem> = emptyList(),
        private val error: String? = null,
    ) : SearchProvider {
        override val displayName: String = kind
        override suspend fun search(
            options: SearchOptions,
            query: String,
            count: Int,
            freshness: String?,
            site: String?,
        ): List<SearchItem> {
            error?.let { throw IllegalStateException(it) }
            return items
        }
    }

    @Test
    fun `首选源失败时回退到下一个可用源`() {
        val result = runBlocking {
            SearchChain.search(
                options = SearchOptions(kind = "a"),
                query = "q",
                count = 5,
                freshness = null,
                site = null,
                providers = listOf(
                    FakeProvider("a", error = "被风控"),
                    FakeProvider("b", items = listOf(SearchItem(0, "标题", "https://x", "摘要"))),
                ),
            )
        }
        assertEquals("b", result.provider)
        assertEquals(1, result.items.size)
        assertEquals(1, result.items[0].index)
    }

    @Test
    fun `空结果也算失败并继续回退`() {
        val result = runBlocking {
            SearchChain.search(
                options = SearchOptions(kind = "a"),
                query = "q",
                count = 5,
                freshness = null,
                site = null,
                providers = listOf(
                    FakeProvider("a"),
                    FakeProvider("b", items = listOf(SearchItem(0, "t", "https://x", "s"))),
                ),
            )
        }
        assertEquals("b", result.provider)
    }

    @Test
    fun `全部源失败时聚合每个源的原因`() {
        val error = runCatching {
            runBlocking {
                SearchChain.search(
                    options = SearchOptions(kind = "a"),
                    query = "q",
                    count = 5,
                    freshness = null,
                    site = null,
                    providers = listOf(
                        FakeProvider("a", error = "HTTP 403"),
                        FakeProvider("b"),
                    ),
                )
            }
        }.exceptionOrNull()
        val message = error?.message.orEmpty()
        assertTrue(message, message.contains("a：HTTP 403"))
        assertTrue(message, message.contains("b：没有结果"))
    }

    @Test
    fun `需要实例地址的源在未配置时被跳过`() {
        val error = runCatching {
            runBlocking {
                SearchChain.search(
                    options = SearchOptions(kind = "bing"),
                    query = "q",
                    count = 5,
                    freshness = null,
                    site = null,
                    providers = listOf(
                        FakeProvider("bing", error = "抓取失败"),
                        object : SearchProvider {
                            override val kind: String = "searxng"
                            override val displayName: String = "searxng"
                            override val needsEndpoint: Boolean = true
                            override suspend fun search(
                                options: SearchOptions,
                                query: String,
                                count: Int,
                                freshness: String?,
                                site: String?,
                            ): List<SearchItem> = listOf(SearchItem(0, "t", "https://x", "s"))
                        },
                    ),
                )
            }
        }.exceptionOrNull()
        // 未填实例地址 → searxng 不进回退链，错误里只应出现 bing 的原因
        val message = error?.message.orEmpty()
        assertTrue(message, message.contains("bing：抓取失败"))
        assertFalse(message, message.contains("searxng"))
    }

    // ---------------- 结果 JSON ----------------

    @Test
    fun `结果 JSON 超长时截条并标记 truncated`() {
        val result = SearchResult(
            provider = "Bing 抓取",
            retrievedAt = "2026-09-22T13:00:00+08:00",
            items = (1..5).map { SearchItem(it, "标题$it", "https://x/$it", "摘" .repeat(50)) },
        )
        val json = ChatJson.json.parseToJsonElement(SearchChain.resultJson(result, 300)).jsonObject
        assertEquals("Bing 抓取", json["provider"]?.jsonPrimitive?.content)
        assertEquals("true", json["truncated"]?.jsonPrimitive?.content)
        assertTrue(json["items"]!!.jsonArray.size in 1..4)

        val full = ChatJson.json.parseToJsonElement(SearchChain.resultJson(result, 10_000)).jsonObject
        assertNull(full["truncated"])
        assertEquals(5, full["items"]!!.jsonArray.size)
    }

    // ---------------- 文本工具 ----------------

    @Test
    fun `HTML 文本处理`() {
        assertEquals("a b", HtmlText.text("<p>a</p><div>b</div>"))
        assertEquals("Tom & Jerry <3", HtmlText.unescape("Tom &amp; Jerry &lt;3"))
        assertEquals("'x' “y”", HtmlText.unescape("&#39;x&#39; &#8220;y&#8221;"))
        assertEquals("https://x.com/p", HtmlText.absoluteUrl("https://x.com", "/p"))
        assertEquals("https://x.com/p", HtmlText.absoluteUrl("https://x.com", "//x.com/p"))
        assertFalse(HtmlText.stripScripts("<script>bad()</script>ok").contains("bad"))
    }

    @Test
    fun `site 限定折进查询串`() {
        assertEquals("kotlin site:example.com", queryWithSite("kotlin", "https://example.com/"))
        assertEquals("kotlin", queryWithSite("kotlin", "   "))
        assertEquals("kotlin", queryWithSite("kotlin", null))
    }

    @Test
    fun `JSON 辅助取字符串`() {
        val obj = ChatJson.json.parseToJsonElement("""{"a":"x","b":"  ","c":1}""") as JsonObject
        assertEquals("x", obj.stringOrNull("a"))
        assertNull(obj.stringOrNull("b"))
        assertNull(obj.stringOrNull("missing"))
    }
}
