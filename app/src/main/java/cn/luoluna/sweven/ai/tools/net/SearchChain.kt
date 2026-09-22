package cn.luoluna.sweven.ai.tools.net

import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZonedDateTime

/**
 * 多源回退搜索链：
 *
 * 1. 先试用户选的源（默认 Bing 抓取；SearXNG 需填实例地址）
 * 2. 失败或「没有结果」时依次回退其它免密钥源（Bing → DuckDuckGo → 已配置的 SearXNG）
 * 3. 全部失败 → 抛异常，消息里带上**每个源各自的原因**，便于用户区分
 *    「被墙/风控」与「配置写错了」
 *
 * 免密钥抓取随时可能被引擎改版/风控，多源回退是这套方案能用的前提。
 */
object SearchChain {

    suspend fun search(
        options: SearchOptions,
        query: String,
        count: Int,
        freshness: String?,
        site: String?,
        /** 可注入（单测用假 provider 验证回退与聚合错误）；默认即全部已实现源 */
        providers: List<SearchProvider> = SearchProviders.all(),
    ): SearchResult {
        val preferred = providers.firstOrNull { it.kind == options.kind } ?: providers.firstOrNull()
            ?: throw IllegalStateException("没有可用的搜索源")
        val order = buildList {
            add(preferred)
            providers
                .filterNot { it.kind == preferred.kind }
                .filter { !it.needsEndpoint || !options.endpoint.isNullOrBlank() }
                .forEach { add(it) }
        }
        val failures = mutableListOf<String>()
        for (provider in order) {
            val items = try {
                provider.search(options, query, count, freshness, site)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${provider.displayName}：${e.message?.take(200) ?: e.javaClass.simpleName}"
                continue
            }
            if (items.isEmpty()) {
                failures += "${provider.displayName}：没有结果"
                continue
            }
            return SearchResult(
                provider = provider.displayName,
                retrievedAt = ZonedDateTime.now().withNano(0).toString(),
                items = items.take(count).mapIndexed { index, item -> item.copy(index = index + 1) },
            )
        }
        throw IllegalStateException(failures.joinToString("；"))
    }

    /** 结果 → 模型可读的 JSON；超过 [maxChars] 时截条并置 truncated=true */
    fun resultJson(result: SearchResult, maxChars: Int): String {
        val kept = mutableListOf<JsonObject>()
        var length = 0
        var truncated = false
        for (item in result.items) {
            val obj = buildJsonObject {
                put("index", item.index)
                put("title", item.title)
                put("url", item.url)
                put("snippet", item.snippet)
                item.publishedDate?.takeIf { it.isNotBlank() }?.let { put("publishedDate", it) }
            }
            val size = obj.toString().length
            if (length + size > maxChars && kept.isNotEmpty()) {
                truncated = true
                break
            }
            kept += obj
            length += size
        }
        return buildJsonObject {
            put("provider", result.provider)
            put("retrievedAt", result.retrievedAt)
            put("items", JsonArray(kept))
            if (truncated) put("truncated", JsonPrimitive(true))
        }.toString()
    }

    /** 把 HTTP/JSON 解析失败包装成统一的工具错误文本 */
    fun failureJson(message: String): String = toolErrorJson("联网搜索失败：$message")

    /** SearXNG 响应解析：{"results":[{"title","url","content","publishedDate"}]} */
    internal fun parseSearxItems(body: String, limit: Int): List<SearchItem> {
        val root = cn.luoluna.sweven.ai.net.ChatJson.json.parseToJsonElement(body).jsonObject
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.stringOrNull("url") ?: return@mapNotNull null
            SearchItem(
                index = 0,
                title = HtmlText.text(obj.stringOrNull("title").orEmpty()).ifBlank { url },
                url = url,
                snippet = HtmlText.text(obj.stringOrNull("content").orEmpty()),
                publishedDate = obj.stringOrNull("publishedDate"),
            )
        }.take(limit)
    }
}

/** JSON 里按 key 取非空字符串（解析 fixture 与真实响应共用） */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
