package cn.luoluna.sweven.ai.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object ChatJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/** 从 JsonObject 取嵌套字段的小工具 */
fun JsonObject.stringAt(vararg keys: String): String? {
    var el: JsonElement? = this
    for (k in keys) {
        el = (el as? JsonObject)?.get(k) ?: return null
    }
    return (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}

fun JsonObject.doubleAt(vararg keys: String): Double? {
    val s = stringAt(*keys) ?: return null
    return s.toDoubleOrNull()
}

fun JsonObject.intAt(vararg keys: String): Int? {
    val s = stringAt(*keys) ?: return null
    return s.toIntOrNull()
}

class ApiException(message: String) : Exception(message)

/** 模型输入/输出类型 */
object Modality {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
}

/** 解析 SSE data 行中的增量文本（OpenAI 兼容 chunk） */
fun parseDeltaContent(payload: String): String? = try {
    val obj = ChatJson.json.parseToJsonElement(payload).jsonObject
    val delta = ((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("delta") as? JsonObject
    (delta?.get("content") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
} catch (_: Exception) {
    null
}

/** 解析上游错误响应体，尽量提取 OpenAI 风格的 message */
fun parseErrorMessage(httpCode: Int, body: String?): String {
    val trimmed = body?.trim().orEmpty()
    if (trimmed.isNotEmpty()) {
        try {
            val obj = ChatJson.json.parseToJsonElement(trimmed).jsonObject
            val msg = obj.stringAt("error", "message")
                ?: obj.stringAt("error")
                ?: obj.stringAt("message")
            if (!msg.isNullOrBlank()) return "HTTP $httpCode: $msg"
        } catch (_: Exception) {
        }
        return "HTTP $httpCode: ${trimmed.take(300)}"
    }
    return "HTTP $httpCode"
}

/**
 * 构造 OpenAI 兼容 /chat/completions 请求体。
 * [defaults] 中的参数仅在未设置时写入（供局域网代理为客户端补默认值）。
 */
fun buildChatBody(
    modelName: String,
    messages: JsonArray,
    stream: Boolean,
    defaults: AdvancedParams? = null,
    base: JsonObject? = null,
): JsonObject {
    val merged: MutableMap<String, JsonElement> = base?.toMutableMap() ?: mutableMapOf()
    merged["model"] = JsonPrimitive(modelName)
    merged["messages"] = messages
    merged["stream"] = JsonPrimitive(stream)
    defaults?.let { d ->
        fun putIfAbsent(key: String, value: JsonPrimitive) {
            val existing = merged[key]
            if (existing == null || existing is JsonNull) merged[key] = value
        }
        d.temperature?.let { putIfAbsent("temperature", JsonPrimitive(it)) }
        d.topP?.let { putIfAbsent("top_p", JsonPrimitive(it)) }
        d.maxTokens?.let { putIfAbsent("max_tokens", JsonPrimitive(it)) }
        d.presencePenalty?.let { putIfAbsent("presence_penalty", JsonPrimitive(it)) }
        d.frequencyPenalty?.let { putIfAbsent("frequency_penalty", JsonPrimitive(it)) }
    }
    return JsonObject(merged)
}

data class AdvancedParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
) {
    companion object {
        fun from(body: JsonObject): AdvancedParams? {
            val temperature = body.doubleAt("temperature")
            val topP = body.doubleAt("top_p")
            val maxTokens = body.intAt("max_tokens")
            val presence = body.doubleAt("presence_penalty")
            val frequency = body.doubleAt("frequency_penalty")
            if (temperature == null && topP == null && maxTokens == null &&
                presence == null && frequency == null
            ) return null
            return AdvancedParams(temperature, topP, maxTokens, presence, frequency)
        }
    }
}

/** 从消息实体列表构造 OpenAI messages 数组，图片以 image_url(base64 data URL) 附加 */
fun buildMessagesJson(
    items: List<Triple<String, String, List<String>>>,
): JsonArray = kotlinx.serialization.json.buildJsonArray {
    for ((role, content, images) in items) {
        if (content.isBlank() && images.isEmpty()) continue
        add(
            buildJsonObject {
                put("role", role)
                if (images.isEmpty()) {
                    put("content", content)
                } else {
                    put(
                        "content",
                        kotlinx.serialization.json.buildJsonArray {
                            if (content.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", content)
                                    }
                                )
                            }
                            for (url in images) {
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            buildJsonObject { put("url", url) }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            }
        )
    }
}

fun streamFlagOf(body: JsonObject): Boolean =
    (body["stream"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull ?: false

/** 供 JSON 中提取模型 data 列表（兼容 data 数组与 models 数组两种返回） */
fun extractModelNames(bodyText: String): List<String> {
    val root = ChatJson.json.parseToJsonElement(bodyText).jsonObject
    val arr = (root["data"] as? JsonArray)
        ?: (root["models"] as? JsonArray)
        ?: JsonArray(emptyList())
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        obj.stringAt("id") ?: obj.stringAt("name") ?: obj.stringAt("model")
    }
}
