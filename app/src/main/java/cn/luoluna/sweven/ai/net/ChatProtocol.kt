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

/** 携带原始响应体的 HTTP 错误，供测试日志等场景展示完整内容 */
class HttpApiException(
    val httpCode: Int,
    val rawBody: String,
    message: String,
) : Exception(message)

/** 模型输入/输出类型 */
object Modality {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VIDEO = "video"
}

/** 解析 SSE data 行中的增量思考内容（DeepSeek reasoning_content / OpenRouter reasoning） */
fun parseReasoningContent(payload: String): String? = try {
    val obj = ChatJson.json.parseToJsonElement(payload).jsonObject
    val delta = ((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("delta") as? JsonObject
    ((delta?.get("reasoning_content") ?: delta?.get("reasoning")) as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.contentOrNull
} catch (_: Exception) {
    null
}

/**
 * 增量解析 <think>…</think> 包裹的思考内容（Qwen 等模型把思考混在 content 里）。
 * 标签跨 chunk 断开时扣住尾部避免闪烁。
 */
class ThinkParser {
    private val raw = StringBuilder()
    private var opened = false
    private var closed = false

    /** 当前解析出的 (思考内容, 正文) */
    var reasoning: String = ""
        private set
    var content: String = ""
        private set

    fun append(delta: String) {
        raw.append(delta)
        reparse()
    }

    private fun reparse() {
        val s = raw.toString()
        var reasoningPart = ""
        var contentPart = ""
        var rest = s
        if (!opened) {
            val i = rest.indexOf(THINK_OPEN)
            if (i >= 0) {
                contentPart += rest.substring(0, i)
                rest = rest.substring(i + THINK_OPEN.length)
                opened = true
            } else {
                val hold = holdback(rest, THINK_OPEN)
                contentPart += rest.substring(0, rest.length - hold)
                reasoning = reasoningPart
                content = contentPart
                return
            }
        }
        if (!closed) {
            val j = rest.indexOf(THINK_CLOSE)
            if (j >= 0) {
                reasoningPart += rest.substring(0, j)
                contentPart += rest.substring(j + THINK_CLOSE.length)
                closed = true
            } else {
                val hold = holdback(rest, THINK_CLOSE)
                reasoningPart += rest.substring(0, rest.length - hold)
            }
        } else {
            contentPart += rest
        }
        reasoning = reasoningPart
        content = contentPart
    }

    private fun holdback(s: String, tag: String): Int {
        for (len in (tag.length - 1) downTo 1) {
            if (s.endsWith(tag.substring(0, len))) return len
        }
        return 0
    }

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
    }
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
 * 将思考深度合入请求体（在自定义 body 合入前调用，customBody 可覆盖）：
 * - auto：不注入任何思考参数，是否思考由模型/厂商默认行为决定
 * - off：Qwen 系注入 enable_thinking=false，其余家族不注入（默认即不思考）
 * - low / medium / high：注入 OpenAI 兼容的 reasoning_effort
 */
fun applyThinkingDepth(
    body: JsonObject,
    depth: String?,
    modelName: String,
): JsonObject {
    if (depth.isNullOrBlank() || depth == ThinkingDepth.DEFAULT || depth == ThinkingDepth.AUTO) return body
    val lower = modelName.lowercase()
    return when (depth) {
        ThinkingDepth.OFF -> {
            if ("qwen" in lower) {
                JsonObject(body + ("enable_thinking" to JsonPrimitive(false)))
            } else {
                body
            }
        }
        ThinkingDepth.LOW, ThinkingDepth.MEDIUM, ThinkingDepth.HIGH -> {
            val merged = JsonObject(body + ("reasoning_effort" to JsonPrimitive(depth)))
            if ("qwen" in lower) {
                JsonObject(merged + ("enable_thinking" to JsonPrimitive(true)))
            } else {
                merged
            }
        }
        else -> body
    }
}

/** 思考深度档位（存于 ConversationEntity.thinkingDepth） */
object ThinkingDepth {
    /** 自动：不注入任何思考参数（模型/厂商默认行为），未设置的会话同样按自动处理 */
    const val AUTO = "auto"
    const val OFF = "off"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    /** 未设置（不注入任何思考相关参数） */
    const val DEFAULT = "default"

    /** 输入框菜单的展示顺序与文案 */
    val LABELS = linkedMapOf(
        AUTO to "自动",
        OFF to "关闭",
        LOW to "低",
        MEDIUM to "中",
        HIGH to "高",
    )
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

object ModelType {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val EMBEDDING = "embedding"
}

/** 从 /models 响应解析出的模型信息（含自动匹配的模态与能力） */
data class RemoteModel(
    val id: String,
    val displayName: String? = null,
    val type: String = ModelType.CHAT,
    val inputText: Boolean = true,
    val inputImage: Boolean = false,
    val inputVideo: Boolean = false,
    val outputText: Boolean = true,
    val outputImage: Boolean = false,
    val outputVideo: Boolean = false,
    val tools: Boolean = false,
    val reasoning: Boolean = false,
)

private fun stringListOf(obj: JsonObject, vararg keys: String): List<String> {
    var el: JsonElement? = obj
    for (k in keys) {
        el = (el as? JsonObject)?.get(k) ?: return emptyList()
    }
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
}

/**
 * 解析 /models 响应为结构化模型列表：
 * - 优先读取响应里的声明字段（OpenRouter architecture.input_modalities、supported_parameters 等）
 * - 无声明时按模型名启发式匹配类型 / 模态 / 推理能力
 */
fun extractModelInfos(bodyText: String): List<RemoteModel> {
    val root = ChatJson.json.parseToJsonElement(bodyText).jsonObject
    val arr = (root["data"] as? JsonArray)
        ?: (root["models"] as? JsonArray)
        ?: JsonArray(emptyList())
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = obj.stringAt("id") ?: obj.stringAt("name") ?: obj.stringAt("model")
        ?: return@mapNotNull null
        val display = obj.stringAt("display_name") ?: obj.stringAt("displayName")?.substringAfterLast('/')
        val lower = id.lowercase()

        // 类型启发式
        val isEmbed = "embed" in lower
        val isVideoGen = listOf("video", "veo", "sora", "kling", "wan-2", "hunyuan-video").any { it in lower }
        val isImageGen = !isEmbed && !isVideoGen && listOf(
            "image", "dall", "flux", "seedream", "cogview", "diffusion",
            "midjourney", "stable-diff", "irag", "qwen-image", "recraft",
        ).any { it in lower }
        val type = when {
            isEmbed -> ModelType.EMBEDDING
            isVideoGen || isImageGen -> ModelType.IMAGE
            else -> ModelType.CHAT
        }

        // 声明的模态（有则优先）
        val inDeclared = stringListOf(obj, "architecture", "input_modalities")
            .ifEmpty { stringListOf(obj, "input_modalities") }
            .map { it.lowercase() }
        val outDeclared = stringListOf(obj, "architecture", "output_modalities")
            .ifEmpty { stringListOf(obj, "output_modalities") }
            .map { it.lowercase() }

        fun defaults(): RemoteModel = when (type) {
            ModelType.EMBEDDING -> RemoteModel(id, display, type, inputText = true, outputText = false)
            ModelType.IMAGE -> RemoteModel(
                id, display, type,
                inputText = true, inputImage = "image" in lower || "omni" in lower,
                outputText = isVideoGen, outputImage = !isVideoGen, outputVideo = isVideoGen,
            )
            else -> RemoteModel(
                id, display, type,
                inputImage = VISION_HINTS.any { it in lower },
                inputVideo = false, outputText = true,
            )
        }

        var m = defaults()
        if (inDeclared.isNotEmpty()) {
            m = m.copy(
                inputText = "text" in inDeclared,
                inputImage = "image" in inDeclared,
                inputVideo = "video" in inDeclared,
            )
        }
        if (outDeclared.isNotEmpty()) {
            m = m.copy(
                outputText = "text" in outDeclared,
                outputImage = "image" in outDeclared,
                outputVideo = "video" in outDeclared,
            )
        }

        // 能力：声明字段优先（supported_parameters / capabilities），否则启发式
        val supported = stringListOf(obj, "supported_parameters").map { it.lowercase() } +
            stringListOf(obj, "capabilities").map { it.lowercase() }
        val declaredTools = supported.any { it.contains("tool") || it.contains("function") }
        val declaredReasoning = supported.any { it.contains("reason") || it.contains("thinking") }
        val nameReasoning = REASONING_HINTS.any { it in lower }
        val nameTools = type == ModelType.CHAT
        m = m.copy(
            tools = declaredTools || nameTools,
            reasoning = declaredReasoning || (!declaredReasoning && supported.isEmpty() && nameReasoning),
        )
        m
    }
}

private val VISION_HINTS = listOf(
    "vision", "-vl", "vl-", "omni", "gpt-4o", "gemini", "claude", "qwen-vl",
    "glm-4v", "internvl", "llava", "pixtral", "grok-4", "doubao-1.5-vision",
)

private val REASONING_HINTS = listOf(
    "thinking", "reasoner", "reasoning", "-r1", "r1-", "qwq", "o1", "o3", "o4-",
    "magistral", "gemini-2.5", "deepseek-v4", "longcat-flash-2-thinking",
)

/**
 * 将模型的自定义 body（JSON 对象文本）合入请求体：非法 JSON 忽略，自定义键覆盖默认值。
 */
fun mergeCustomBody(body: JsonObject, customBody: String?): JsonObject {
    if (customBody.isNullOrBlank()) return body
    return try {
        val extra = ChatJson.json.parseToJsonElement(customBody).jsonObject
        JsonObject(body + extra)
    } catch (_: Exception) {
        body
    }
}

/** 解析自定义请求头文本（每行 "Name: Value"），非法行忽略 */
fun parseCustomHeaders(customHeaders: String?): Map<String, String> {
    if (customHeaders.isNullOrBlank()) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (line in customHeaders.lines()) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val name = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty() && !name.equals("authorization", ignoreCase = true)
            && !name.equals("host", ignoreCase = true) && !name.equals("content-length", ignoreCase = true)
        ) {
            result[name] = value
        }
    }
    return result
}

/** Gemini 官方 API 的内置工具 → 请求体注入（google_search / url_context / 图像输出） */
fun applyBuiltinTools(
    body: JsonObject,
    googleSearch: Boolean,
    urlContext: Boolean,
    imageGen: Boolean,
): JsonObject {
    val tools = buildList {
        if (googleSearch) add(buildJsonObject { put("google_search", buildJsonObject { }) })
        if (urlContext) add(buildJsonObject { put("url_context", buildJsonObject { }) })
    }
    var merged = body
    if (tools.isNotEmpty() && merged["tools"] == null) {
        merged = JsonObject(merged + ("tools" to JsonArray(tools)))
    }
    if (imageGen && merged["modalities"] == null) {
        merged = JsonObject(
            merged + ("modalities" to JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image"))))
        )
    }
    return merged
}

/** 非流式响应中的工具调用项 */
data class ToolCall(val name: String, val arguments: String)

private fun JsonObject.firstChoiceMessage(): JsonObject? =
    ((this["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.get("message") as? JsonObject

/** 从非流式响应提取 choices[0].message.content */
fun extractMessageContent(response: JsonObject): String? =
    (response.firstChoiceMessage()?.get("content") as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.content

/** 从非流式响应提取 choices[0].message.tool_calls（模型不支持工具调用时返回空列表） */
fun extractToolCalls(response: JsonObject): List<ToolCall> {
    val message = response.firstChoiceMessage() ?: return emptyList()
    val calls = message["tool_calls"] as? JsonArray ?: return emptyList()
    return calls.mapNotNull { el ->
        val fn = (el as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
        val name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        ToolCall(name, (fn["arguments"] as? JsonPrimitive)?.content ?: "{}")
    }
}
