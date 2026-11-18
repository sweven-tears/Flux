package cn.luoluna.sweven.ai.tools.local

import cn.luoluna.sweven.ai.tools.FluxTool
import cn.luoluna.sweven.ai.tools.net.HtmlText
import cn.luoluna.sweven.ai.tools.net.httpGetText
import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 网页正文抓取：搜索摘要不够时读原文（配合 [buildWebSearchTool] 使用）。
 *
 * 只做「取正文文本」：去 script/style → 去标签 → 折叠空白 → 截断。
 * 不做 JS 渲染（动态页面可能取不到正文）；仅访问公网，重定向逐跳校验且最多 3 跳。
 */
internal fun buildFetchUrlTool(): FluxTool = FluxTool(
    name = "fetch_url",
    displayName = "网页正文抓取",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "url",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("要抓取的网页地址（http/https）"))
                    },
                )
                put(
                    "maxChars",
                    buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("返回正文长度上限，默认 8000，最大 20000"))
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("url")) })
    },
    description = "抓取指定网页的正文文本（去标签后的纯文本），用于核实搜索结果里的具体内容。" +
        "摘要不足、需要引用原文数据时使用；动态渲染页面可能取不到正文。",
) { args ->
    val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (url.isEmpty()) return@FluxTool toolErrorJson("url 不能为空")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return@FluxTool toolErrorJson("url 必须以 http:// 或 https:// 开头")
    }
    val maxChars = (args["maxChars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_CHARS)
        .coerceIn(1_000, MAX_MAX_CHARS)
    try {
        val html = httpGetText(url, timeoutSeconds = 20)
        val text = HtmlText.text(HtmlText.stripScripts(html))
        val truncated = text.length > maxChars
        buildJsonObject {
            put("url", url)
            put("content", if (truncated) text.take(maxChars) else text)
            if (truncated) put("truncated", JsonPrimitive(true))
        }.toString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toolErrorJson("抓取失败：${(e.message ?: e.javaClass.simpleName).take(300)}")
    }
}

private const val DEFAULT_MAX_CHARS = 8_000
private const val MAX_MAX_CHARS = 20_000
