package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.SharedHttp
import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * HTTP 自定义工具配置（`custom_tools.configJson` 的解析结果）。
 *
 * - [url] 支持 `{参数名}` 占位（URL 场景做 percent-encode）
 * - [headersJson] 支持 JSON 对象（`{"Authorization":"Bearer {token}"}`）或 `Key: Value` 逐行文本，
 *   值里的占位符同样被替换（不编码）
 * - [bodyTemplate] 为空时，带 body 的方法直接把模型给的参数对象作为 JSON 发出
 * - [resultPath] 形如 `data.webPages.value[0].name`（可省 `$.`），解析失败回退整段响应文本
 */
data class HttpToolConfig(
    val method: String,
    val url: String,
    val headersJson: String? = null,
    val bodyTemplate: String? = null,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxChars: Int = DEFAULT_MAX_CHARS,
    val resultPath: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("method", method)
        put("url", url)
        headersJson?.takeIf { it.isNotBlank() }?.let { put("headersJson", it) }
        bodyTemplate?.takeIf { it.isNotBlank() }?.let { put("bodyTemplate", it) }
        put("timeoutMs", timeoutMs)
        put("maxChars", maxChars)
        resultPath?.takeIf { it.isNotBlank() }?.let { put("resultPath", it) }
    }.toString()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DEFAULT_MAX_CHARS = 8_000
        const val MAX_TIMEOUT_MS = 60_000L
        const val MAX_MAX_CHARS = 64_000

        /** 允许的方法（全部不需要额外权限面；HEAD 只取头） */
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /** 会带请求体的方法 */
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        fun parse(json: String): HttpToolConfig {
            val obj = try {
                ChatJson.json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("HTTP 配置不是合法 JSON：${e.message?.take(120).orEmpty()}")
            }
            val url = obj.str("url")?.trim().orEmpty()
            if (url.isEmpty()) throw IllegalArgumentException("HTTP 工具必须填写 url")
            val scheme = url.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("url 必须以 http:// 或 https:// 开头")
            }
            val method = (obj.str("method")?.trim()?.uppercase().orEmpty().ifEmpty { "GET" })
            if (method !in METHODS) throw IllegalArgumentException("不支持的请求方法：$method（可选 ${METHODS.joinToString("/")}）")
            val timeout = obj.str("timeoutMs")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
            val maxChars = obj.str("maxChars")?.toIntOrNull() ?: DEFAULT_MAX_CHARS
            return HttpToolConfig(
                method = method,
                url = url,
                headersJson = obj.str("headersJson")?.takeIf { it.isNotBlank() },
                bodyTemplate = obj.str("bodyTemplate")?.takeIf { it.isNotBlank() },
                timeoutMs = timeout.coerceIn(1_000, MAX_TIMEOUT_MS),
                maxChars = maxChars.coerceIn(500, MAX_MAX_CHARS),
                resultPath = obj.str("resultPath")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** 取字符串字段（数字/布尔也接受其字面量），非原始值返回 null */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

/** 模板里出现的占位符名（去重、保持出现顺序） */
private val PLACEHOLDER_REGEX = Regex("""\{([A-Za-z0-9_]+)\}""")

internal fun placeholdersOf(template: String): List<String> =
    PLACEHOLDER_REGEX.findAll(template).map { it.groupValues[1] }.distinct().toList()

/**
 * 把 `{参数名}` 替换为模型给的实参；[encode] = true 时对值做 percent-encode（URL 场景）。
 * 未提供的占位符原样保留，由调用方决定是否报错。
 */
internal fun renderTemplate(template: String, args: JsonObject, encode: Boolean): String =
    PLACEHOLDER_REGEX.replace(template) { match ->
        val key = match.groupValues[1]
        val value = args[key]?.let { element ->
            when (element) {
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        } ?: match.value
        if (encode) encodeUrlComponent(value) else value
    }

/** 与浏览器 encodeURIComponent 对齐：空格用 %20 而不是 + */
private fun encodeUrlComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** 极简路径抽取：`a.b[0].c`（可带 `$.` 前缀）；任何一步取不到都回退整段文本 */
internal fun pickByPath(text: String, path: String?): String {
    val clean = path?.trim()?.removePrefix("$")?.removePrefix(".")?.takeIf { it.isNotEmpty() } ?: return text
    var current: JsonElement = runCatching { ChatJson.json.parseToJsonElement(text) }.getOrNull() ?: return text
    for (segment in clean.split('.')) {
        val name = segment.substringBefore('[')
        if (name.isNotEmpty()) {
            current = (current as? JsonObject)?.get(name) ?: return text
        }
        for (match in Regex("""\[(\d+)]""").findAll(segment)) {
            val index = match.groupValues[1].toInt()
            current = (current as? JsonArray)?.getOrNull(index) ?: return text
        }
    }
    return when (current) {
        is JsonPrimitive -> current.content
        else -> current.toString()
    }
}

/** 结果截断（模型不需要整篇响应；截断处标注原长，便于用户判断要不要改配置） */
internal fun truncateText(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "\n…（已截断，原长 ${text.length} 字符）"

/**
 * 请求头解析：`{...}` 视为 JSON 对象，否则按 `Key: Value` 逐行；值支持 `{参数名}` 占位（不编码）。
 * 解析失败抛 [IllegalArgumentException]（文案直接给用户看）。
 */
internal fun parseHeaders(raw: String?, args: JsonObject): Map<String, String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyMap()
    val pairs = LinkedHashMap<String, String>()
    if (text.startsWith("{")) {
        val obj = try {
            ChatJson.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("请求头 JSON 不合法：${e.message?.take(120).orEmpty()}")
        }
        obj.forEach { (key, value) ->
            pairs[key] = renderTemplate((value as? JsonPrimitive)?.content.orEmpty(), args, encode = false)
        }
    } else {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val at = trimmed.indexOf(':')
            if (at <= 0) throw IllegalArgumentException("请求头「$trimmed」缺少冒号（应为 Key: Value）")
            val key = trimmed.substring(0, at).trim()
            val value = trimmed.substring(at + 1).trim()
            pairs[key] = renderTemplate(value, args, encode = false)
        }
    }
    return pairs
}

/** HTTP 自定义工具执行器：一次请求 = 一个工具结果文本 */
object HttpToolExecutor {

    suspend fun run(config: HttpToolConfig, args: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            val missing = placeholdersOf(config.url).filterNot { it in args }
            if (missing.isNotEmpty()) {
                return@withContext toolErrorJson("url 模板缺少参数：${missing.joinToString("、")}")
            }
            val url = renderTemplate(config.url, args, encode = true)
            val headers = try {
                parseHeaders(config.headersJson, args)
            } catch (e: IllegalArgumentException) {
                return@withContext toolErrorJson(e.message ?: "请求头解析失败")
            }
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            if (config.method in HttpToolConfig.BODY_METHODS) {
                val bodyText = config.bodyTemplate?.let { renderTemplate(it, args, encode = false) }
                    ?: args.toString()
                builder.method(config.method, bodyText.toRequestBody(mediaType))
            } else {
                builder.method(config.method, null)
            }
            // CustomToolLoader 与 script_tool_dev.test 共用；最终模板 URL 一律 PUBLIC_ONLY。
            SharedHttp.execute(builder.build(), timeoutMillis = config.timeoutMs).use { response ->
                val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                if (!response.isSuccessful) {
                    toolErrorJson("HTTP ${response.code}：${text.take(300)}")
                } else {
                    truncateText(pickByPath(text, config.resultPath), config.maxChars)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolErrorJson("请求失败：${(e.message ?: e.javaClass.simpleName).take(200)}")
        }
    }
}
