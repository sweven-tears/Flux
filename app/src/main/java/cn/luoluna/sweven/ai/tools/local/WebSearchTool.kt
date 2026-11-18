package cn.luoluna.sweven.ai.tools.local

import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.tools.FluxTool
import cn.luoluna.sweven.ai.tools.net.SearchChain
import cn.luoluna.sweven.ai.tools.net.SearchOptions
import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * 联网搜索（免密钥多源回退）：
 * - 默认源 Bing 抓取 → 兜底 DuckDuckGo → 可选自建 SearXNG 实例（配置见「工具中心 → 联网搜索」）
 * - 搜索源与实例地址每次执行时现读设置，改配置无需重启
 * - 全部源失败时把**每个源的原因**写进 `{"error": ...}`，便于区分被墙/风控与配置错误
 *
 * 默认开启（免密钥、开箱可用）；不想每个新会话都带上，可在工具中心一键关掉。
 */
internal fun buildWebSearchTool(settings: AppSettings): FluxTool = FluxTool(
    name = "web_search",
    displayName = "联网搜索",
    description = buildString {
        append("联网搜索：需要实时信息、最新消息或核实事实时使用。\n")
        append("返回 JSON：provider（命中的搜索源）、retrievedAt（本地检索时间，不是发布时间）、")
        append("items[]（index/title/url/snippet，部分源带 publishedDate）。\n")
        append("注意：不要只凭结果顺序判断时效；摘要不够时用 fetch_url 读原文；来源冲突时换个关键词再搜一次。\n")
        append("回答里请用 Markdown 链接标注来源，如 [标题](url)。\n")
        append("今天是 ")
        append(LocalDate.now().toString())
        append("。")
    },
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("搜索关键词，尽量用具体的关键词组"))
                    },
                )
                put(
                    "count",
                    buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("返回条数 1-10，默认取工具中心设置"))
                    },
                )
                put(
                    "freshness",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("day"))
                            add(JsonPrimitive("week"))
                            add(JsonPrimitive("month"))
                            add(JsonPrimitive("any"))
                        })
                        put("description", JsonPrimitive("时间范围过滤：day/week/month/any（any 或不填 = 不限）"))
                    },
                )
                put(
                    "site",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("限定站点域名，如 example.com（可空）"))
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    },
    defaultEnabled = true,
) { args ->
    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (query.isEmpty()) return@FluxTool toolErrorJson("query 不能为空")
    val count = (args["count"]?.jsonPrimitive?.intOrNull ?: settings.searchCount.value).coerceIn(1, 10)
    val freshness = args["freshness"]?.jsonPrimitive?.contentOrNull?.takeIf { it in FRESHNESS }
    val site = args["site"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val options = SearchOptions.fromSettings(settings)
    try {
        val result = SearchChain.search(options, query, count, freshness, site)
        SearchChain.resultJson(result, MAX_RESULT_CHARS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SearchChain.failureJson(e.message?.take(400) ?: e.javaClass.simpleName)
    }
}

private val FRESHNESS = setOf("day", "week", "month")

/** 结果 JSON 总长上限：够模型判断来源，又不至于挤掉上下文 */
private const val MAX_RESULT_CHARS = 8_000
