package cn.luoluna.sweven.ai.net

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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

/**
 * 从消息实体列表构造 OpenAI messages 数组，图片以 image_url(base64 data URL) 附加。
 * 组装时保证 user/assistant 严格交替（协议转换类网关会把相邻同角色消息合并成一条）：
 * - 空 content 的 assistant（仅 reasoning 或失败轮次）以空格占位保留，不再整条丢弃
 * - 相邻同角色消息之间插入对方角色空格占位
 */
fun buildMessagesJson(
    items: List<Triple<String, String, List<String>>>,
): JsonArray = kotlinx.serialization.json.buildJsonArray {
    fun message(role: String, content: String, images: List<String>) {
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

    var lastChatRole: String? = null
    for ((role, content, images) in items) {
        var emitContent = content
        if (emitContent.isBlank() && images.isEmpty()) {
            if (role != ROLE_ASSISTANT) continue
            emitContent = PLACEHOLDER_CONTENT
        }
        if (role != ROLE_SYSTEM) {
            if (lastChatRole == role) {
                message(if (role == ROLE_USER) ROLE_ASSISTANT else ROLE_USER, PLACEHOLDER_CONTENT, emptyList())
            }
            lastChatRole = role
        }
        message(role, emitContent, images)
    }
}

/** 角色占位文本：空到无法再空，但不会被任何网关当作空消息丢弃 */
private const val PLACEHOLDER_CONTENT = " "

/** 与 data.db 的 Role 常量对应；net 层不反向依赖数据层，此处以字面量复写 */
private const val ROLE_SYSTEM = "system"
private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"

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
    /** 厂商声明的上下文窗口（tokens），无声明时为 null */
    val contextLength: Int? = null,
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
            // 上下文窗口：OpenRouter 顶层 context_length，其他厂商可能挂在 top_provider 下
            contextLength = obj.intAt("context_length")
                ?: obj.intAt("top_provider", "context_length"),
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
 * messages / model / stream 由组装层根据会话与目标模型生成，自定义 body 中的同名键一律忽略，
 * 避免整体覆盖聊天历史或把请求改向别的模型。
 */
private val CUSTOM_BODY_RESERVED_KEYS = setOf("messages", "model", "stream")

fun mergeCustomBody(body: JsonObject, customBody: String?): JsonObject {
    if (customBody.isNullOrBlank()) return body
    return try {
        val extra = ChatJson.json.parseToJsonElement(customBody).jsonObject
        JsonObject(body + extra.filterKeys { it !in CUSTOM_BODY_RESERVED_KEYS })
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

/**
 * 从响应 JSON 提取 usage 的 token 计数（非流式响应或流式里带 usage 的帧）。
 * 兼容 prompt/completion 与 input/output 两种命名；没有 usage 字段返回 null。
 */
fun extractUsage(response: JsonObject): Pair<Long?, Long?>? {
    val usage = response["usage"] as? JsonObject ?: return null
    fun num(vararg keys: String): Long? {
        var el: JsonElement? = usage
        for (k in keys) {
            el = (el as? JsonObject)?.get(k) ?: return null
        }
        return (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toLongOrNull()
    }
    val prompt = num("prompt_tokens") ?: num("input_tokens")
    val completion = num("completion_tokens") ?: num("output_tokens")
    if (prompt == null && completion == null) return null
    return prompt to completion
}

// ==================== 多协议适配（OpenAI / Gemini / Anthropic） ====================

/** 厂商接口协议（ProviderEntity.protocol 的取值） */
object ProviderProtocol {
    /** OpenAI 兼容：/chat/completions，Bearer 鉴权 */
    const val OPENAI = "openai"
    /** Gemini 原生：generateContent / streamGenerateContent，x-goog-api-key 鉴权 */
    const val GEMINI = "gemini"
    /** Anthropic 原生：/messages，x-api-key + anthropic-version 鉴权 */
    const val ANTHROPIC = "anthropic"
}

/** Anthropic 协议的版本头 */
const val ANTHROPIC_VERSION = "2023-06-01"

/** 各协议 SSE 帧的归一化解析结果：正文增量 / 思考增量 / 错误 / 流结束 / token 用量 */
data class StreamChunk(
    val text: String? = null,
    val reasoning: String? = null,
    val error: String? = null,
    val done: Boolean = false,
    val usage: UsageInfo? = null,
)

/**
 * 归一化的 token 用量（三协议流式/非流式共用）。
 * 各字段可空：流式里 usage 可能拆在多帧（如 Anthropic 首帧给输入、末帧给输出），
 * 消费方按「最后非空值覆盖」逐字段合并。
 */
data class UsageInfo(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    /** prompt 中命中缓存的 token 数（OpenAI prompt_tokens_details.cached_tokens / Anthropic cache_read / Gemini cachedContent） */
    val cachedTokens: Long? = null,
)

/** 解析 OpenAI 兼容流式尾帧（或非流式响应体）中的 usage，含缓存命中明细；无 usage 字段返回 null */
fun parseOpenAiUsage(payload: String): UsageInfo? = try {
    val obj = ChatJson.json.parseToJsonElement(payload).jsonObject
    val usage = obj["usage"] as? JsonObject ?: return null
    fun num(vararg keys: String): Long? {
        var el: JsonElement? = usage
        for (k in keys) {
            el = (el as? JsonObject)?.get(k) ?: return null
        }
        return (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toLongOrNull()
    }
    val prompt = num("prompt_tokens")
    val completion = num("completion_tokens")
    val cached = num("prompt_tokens_details", "cached_tokens")
    if (prompt == null && completion == null && cached == null) null
    else UsageInfo(prompt, completion, cached)
} catch (_: Exception) {
    null
}

/**
 * 解析 Gemini streamGenerateContent 的单个 SSE 帧（GenerateContentResponse JSON）。
 * parts 中 thought=true 的文本归为思考增量；无 candidates 的帧视为心跳忽略。
 */
fun parseGeminiChunk(payload: String): StreamChunk = try {
    val obj = ChatJson.json.parseToJsonElement(payload).jsonObject
    obj.stringAt("error", "message")?.let { return StreamChunk(error = it) }
    // usageMetadata 常出现在末帧，可能与 candidates 同帧，也可能独立成帧，先于 candidates 早退解析
    val usage = (obj["usageMetadata"] as? JsonObject)?.let { u ->
        fun num(key: String) = (u[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toLongOrNull()
        val candidatesT = num("candidatesTokenCount")
        val thoughtsT = num("thoughtsTokenCount")
        UsageInfo(
            promptTokens = num("promptTokenCount"),
            completionTokens = if (candidatesT != null || thoughtsT != null) (candidatesT ?: 0) + (thoughtsT ?: 0) else null,
            cachedTokens = num("cachedContentTokenCount"),
        )
    }
    val candidates = obj["candidates"] as? JsonArray ?: return StreamChunk(usage = usage)
    val content = (candidates.firstOrNull() as? JsonObject)?.get("content") as? JsonObject
    val parts = content?.get("parts") as? JsonArray ?: return StreamChunk(usage = usage)
    val text = StringBuilder()
    val thinking = StringBuilder()
    for (el in parts) {
        val part = el as? JsonObject ?: continue
        val t = (part["text"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: continue
        val isThought = (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
        if (isThought) thinking.append(t) else text.append(t)
    }
    StreamChunk(
        text = text.toString().takeIf { it.isNotEmpty() },
        reasoning = thinking.toString().takeIf { it.isNotEmpty() },
        usage = usage,
    )
} catch (_: Exception) {
    StreamChunk()
}

/**
 * 解析 Anthropic /messages 流式响应的单个 SSE data 帧（事件 JSON 自带 type 字段）。
 * text_delta → 正文；thinking_delta → 思考；message_stop → 结束；error → 失败。
 */
fun parseAnthropicChunk(payload: String): StreamChunk = try {
    val obj = ChatJson.json.parseToJsonElement(payload).jsonObject
    fun num(usage: JsonObject?, key: String) =
        (usage?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toLongOrNull()
    when (obj.stringAt("type")) {
        "content_block_delta" -> when (obj.stringAt("delta", "type")) {
            "text_delta" -> StreamChunk(text = obj.stringAt("delta", "text"))
            "thinking_delta" -> StreamChunk(reasoning = obj.stringAt("delta", "thinking"))
            else -> StreamChunk()
        }
        // 输入 token 在 message_start 的 message.usage（output_tokens 此处恒为 1，忽略）；缓存命中一并带出
        "message_start" -> {
            val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
            StreamChunk(
                usage = usage?.let {
                    UsageInfo(
                        promptTokens = num(it, "input_tokens"),
                        cachedTokens = num(it, "cache_read_input_tokens"),
                    )
                },
            )
        }
        // 输出 token 在 message_delta 的 usage.output_tokens（累计值，最后一帧为准）
        "message_delta" -> {
            val usage = obj["usage"] as? JsonObject
            StreamChunk(usage = usage?.let { UsageInfo(completionTokens = num(it, "output_tokens")) })
        }
        "message_stop" -> StreamChunk(done = true)
        "error" -> StreamChunk(error = obj.stringAt("error", "message") ?: "上游错误")
        else -> StreamChunk()
    }
} catch (_: Exception) {
    StreamChunk()
}

/** 解析 data URL 为 (mimeType, base64)；非 data URL 返回 null */
private fun parseDataUrl(url: String): Pair<String, String>? {
    if (!url.startsWith("data:")) return null
    val marker = ";base64,"
    val semi = url.indexOf(marker)
    if (semi < 0) return null
    val mime = url.substring(5, semi).ifBlank { "image/png" }
    return mime to url.substring(semi + marker.length)
}

/** OpenAI content 元素提取纯文本（字符串或 parts 数组两种形态） */
private fun contentText(content: JsonElement?): String? = when (content) {
    is JsonPrimitive -> content.takeIf { it !is JsonNull }?.contentOrNull
    is JsonArray -> content.mapNotNull { el ->
        ((el as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }.joinToString("").takeIf { it.isNotEmpty() }
    else -> null
}

/** OpenAI content → Anthropic content blocks（text + base64/url image）；空内容以空格占位 */
private fun anthropicContentBlocks(content: JsonElement?): JsonArray {
    val arr = buildJsonArray {
        when (content) {
            is JsonPrimitive -> add(
                buildJsonObject {
                    put("type", "text")
                    put("text", content.takeIf { it !is JsonNull }?.contentOrNull ?: " ")
                }
            )
            is JsonArray -> for (el in content) {
                val part = el as? JsonObject ?: continue
                when (part.stringAt("type")) {
                    "text" -> part.stringAt("text")?.let {
                        add(buildJsonObject { put("type", "text"); put("text", it) })
                    }
                    "image_url" -> {
                        val url = part.stringAt("image_url", "url") ?: continue
                        val dataUrl = parseDataUrl(url)
                        if (dataUrl != null) {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    put(
                                        "source",
                                        buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", dataUrl.first)
                                            put("data", dataUrl.second)
                                        },
                                    )
                                }
                            )
                        } else {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject { put("type", "url"); put("url", url) })
                                }
                            )
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    // 空内容占位，避免上游拒绝空 blocks
    return if (arr.isEmpty()) {
        buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", " ") }) }
    } else {
        arr
    }
}

/** OpenAI content → Gemini parts（text + inlineData/fileData）；空内容以空格占位 */
private fun geminiParts(content: JsonElement?): JsonArray {
    val arr = buildJsonArray {
        when (content) {
            is JsonPrimitive -> content.takeIf { it !is JsonNull }?.contentOrNull?.let {
                add(buildJsonObject { put("text", it) })
            }
            is JsonArray -> for (el in content) {
                val part = el as? JsonObject ?: continue
                when (part.stringAt("type")) {
                    "text" -> part.stringAt("text")?.let { add(buildJsonObject { put("text", it) }) }
                    "image_url" -> {
                        val url = part.stringAt("image_url", "url") ?: continue
                        val dataUrl = parseDataUrl(url)
                        if (dataUrl != null) {
                            add(
                                buildJsonObject {
                                    put(
                                        "inlineData",
                                        buildJsonObject {
                                            put("mimeType", dataUrl.first)
                                            put("data", dataUrl.second)
                                        },
                                    )
                                }
                            )
                        } else {
                            add(buildJsonObject { put("fileData", buildJsonObject { put("fileUri", url) }) })
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    return if (arr.isEmpty()) {
        buildJsonArray { add(buildJsonObject { put("text", " ") }) }
    } else {
        arr
    }
}

/** OpenAI stop（字符串或数组）→ 字符串数组 */
private fun stopSequencesOf(stop: JsonElement?): List<JsonPrimitive>? = when (stop) {
    is JsonPrimitive -> stop.takeIf { it !is JsonNull }?.contentOrNull?.let { listOf(JsonPrimitive(it)) }
    is JsonArray -> stop.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
        .map(::JsonPrimitive).takeIf { it.isNotEmpty() }
    else -> null
}

/** 思考深度档位 → Anthropic thinking 预算（tokens） */
private fun anthropicThinkingBudget(effort: String?): Int? = when (effort) {
    "low" -> 1024
    "medium" -> 8192
    "high" -> 32000
    else -> null
}

/** 思考深度档位 → Gemini thinkingBudget（tokens） */
private fun geminiThinkingBudget(effort: String?): Int? = when (effort) {
    "low" -> 1024
    "medium" -> 8192
    "high" -> 24576
    else -> null
}

/**
 * 规范 OpenAI 请求体 → Anthropic /messages 原生请求体。
 * system 消息合并为 system 字段；max_tokens 必填（缺省 4096，思考开启时保证大于预算）；
 * 思考开启时按 Anthropic 要求不传 temperature/top_p；OpenAI 专属键一律不携带。
 */
fun toAnthropicBody(body: JsonObject): JsonObject {
    val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
    val systemParts = mutableListOf<String>()
    val outMessages = buildJsonArray {
        for (el in messages) {
            val m = el as? JsonObject ?: continue
            when (m.stringAt("role")) {
                "system" -> contentText(m["content"])?.let(systemParts::add)
                "user", "assistant" -> add(
                    buildJsonObject {
                        put("role", m.stringAt("role"))
                        put("content", anthropicContentBlocks(m["content"]))
                    }
                )
            }
        }
    }
    val budget = anthropicThinkingBudget(body.stringAt("reasoning_effort"))
    val baseMax = body.intAt("max_tokens") ?: 4096
    // thinking 开启时 max_tokens 必须大于 budget_tokens
    val maxTokens = if (budget != null) maxOf(baseMax, budget + 2048) else baseMax
    return buildJsonObject {
        put("model", body.stringAt("model").orEmpty())
        put("messages", outMessages)
        put("max_tokens", maxTokens)
        if (streamFlagOf(body)) put("stream", true)
        if (systemParts.isNotEmpty()) put("system", systemParts.joinToString("\n\n"))
        if (budget != null) {
            put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) })
        } else {
            body.doubleAt("temperature")?.let { put("temperature", it) }
            body.doubleAt("top_p")?.let { put("top_p", it) }
        }
        stopSequencesOf(body["stop"])?.let { put("stop_sequences", JsonArray(it)) }
    }
}

/**
 * 规范 OpenAI 请求体 → Gemini generateContent 原生请求体。
 * system 消息合并为 systemInstruction；assistant → model 角色；参数进 generationConfig；
 * 兼容层注入的 google_search/url_context/modalities 转换为原生形态；stream 由端点决定，不携带。
 */
fun toGeminiBody(body: JsonObject): JsonObject {
    val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
    val systemParts = mutableListOf<JsonObject>()
    val contents = buildJsonArray {
        for (el in messages) {
            val m = el as? JsonObject ?: continue
            when (m.stringAt("role")) {
                "system" -> contentText(m["content"])?.let {
                    systemParts.add(buildJsonObject { put("text", it) })
                }
                "user", "assistant" -> add(
                    buildJsonObject {
                        put("role", if (m.stringAt("role") == "assistant") "model" else "user")
                        put("parts", geminiParts(m["content"]))
                    }
                )
            }
        }
    }
    return buildJsonObject {
        put("contents", contents)
        if (systemParts.isNotEmpty()) {
            put("systemInstruction", buildJsonObject { put("parts", JsonArray(systemParts)) })
        }
        val genConfig = buildJsonObject {
            body.doubleAt("temperature")?.let { put("temperature", it) }
            body.doubleAt("top_p")?.let { put("topP", it) }
            body.intAt("max_tokens")?.let { put("maxOutputTokens", it) }
            stopSequencesOf(body["stop"])?.let { put("stopSequences", JsonArray(it)) }
            (body["modalities"] as? JsonArray)?.let { arr ->
                val modalities = arr.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull?.uppercase()
                }
                if (modalities.isNotEmpty()) {
                    put("responseModalities", JsonArray(modalities.map(::JsonPrimitive)))
                }
            }
            geminiThinkingBudget(body.stringAt("reasoning_effort"))?.let { budget ->
                put("thinkingConfig", buildJsonObject { put("thinkingBudget", budget) })
            }
        }
        if (genConfig.isNotEmpty()) put("generationConfig", genConfig)
        (body["tools"] as? JsonArray)?.let { tools ->
            val native = tools.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                when {
                    obj.containsKey("google_search") -> buildJsonObject { put("googleSearch", buildJsonObject {}) }
                    obj.containsKey("url_context") -> buildJsonObject { put("urlContext", buildJsonObject {}) }
                    else -> null
                }
            }
            if (native.isNotEmpty()) put("tools", JsonArray(native))
        }
    }
}

/** 规范 OpenAI 请求体 → 目标协议原生请求体（openai 原样返回） */
fun toUpstreamBody(protocol: String, body: JsonObject): JsonObject = when (protocol) {
    ProviderProtocol.GEMINI -> toGeminiBody(body)
    ProviderProtocol.ANTHROPIC -> toAnthropicBody(body)
    else -> body
}

/** Anthropic 非流式响应 → OpenAI chat completion 形态 */
private fun anthropicToOpenAiResponse(raw: JsonObject): JsonObject {
    val contentArr = raw["content"] as? JsonArray
    val text = StringBuilder()
    val thinking = StringBuilder()
    contentArr?.forEach { el ->
        val block = el as? JsonObject ?: return@forEach
        when (block.stringAt("type")) {
            "text" -> block.stringAt("text")?.let(text::append)
            "thinking" -> block.stringAt("thinking")?.let(thinking::append)
        }
    }
    val finish = when (raw.stringAt("stop_reason")) {
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        null -> null
        else -> "stop"
    }
    return buildJsonObject {
        put("id", raw.stringAt("id") ?: "chatcmpl-anthropic")
        put("object", "chat.completion")
        put("model", raw.stringAt("model") ?: "")
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text.toString())
                                if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
                            },
                        )
                        finish?.let { put("finish_reason", it) }
                    },
                )
            },
        )
        val inputT = raw.intAt("usage", "input_tokens")
        val outputT = raw.intAt("usage", "output_tokens")
        if (inputT != null || outputT != null) {
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", inputT ?: 0)
                    put("completion_tokens", outputT ?: 0)
                    put("total_tokens", (inputT ?: 0) + (outputT ?: 0))
                },
            )
        }
    }
}

/** Gemini 非流式响应 → OpenAI chat completion 形态 */
private fun geminiToOpenAiResponse(raw: JsonObject): JsonObject {
    val candidate = (raw["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
    val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
    val text = StringBuilder()
    val thinking = StringBuilder()
    parts?.forEach { el ->
        val part = el as? JsonObject ?: return@forEach
        val t = (part["text"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: return@forEach
        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) thinking.append(t) else text.append(t)
    }
    val finish = when (candidate?.stringAt("finishReason")) {
        "STOP" -> "stop"
        "MAX_TOKENS" -> "length"
        "SAFETY", "RECITATION", "PROHIBITED_CONTENT" -> "content_filter"
        null -> null
        else -> "stop"
    }
    return buildJsonObject {
        put("id", "chatcmpl-gemini")
        put("object", "chat.completion")
        put("model", raw.stringAt("modelVersion") ?: "")
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text.toString())
                                if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
                            },
                        )
                        finish?.let { put("finish_reason", it) }
                    },
                )
            },
        )
        val usage = raw["usageMetadata"] as? JsonObject
        if (usage != null) {
            val prompt = (usage["promptTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val candidatesT = (usage["candidatesTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val thoughtsT = (usage["thoughtsTokenCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            put(
                "usage",
                buildJsonObject {
                    put("prompt_tokens", prompt)
                    put("completion_tokens", candidatesT + thoughtsT)
                    put("total_tokens", prompt + candidatesT + thoughtsT)
                },
            )
        }
    }
}

/** 原生协议非流式响应归一化为 OpenAI chat completion 形态（openai 原样返回），供测试弹窗与局域网网关复用 */
fun toOpenAiChatResponse(protocol: String, raw: JsonObject): JsonObject = when (protocol) {
    ProviderProtocol.GEMINI -> geminiToOpenAiResponse(raw)
    ProviderProtocol.ANTHROPIC -> anthropicToOpenAiResponse(raw)
    else -> raw
}

/**
 * 各协议 /models 响应归一化为 OpenAI {data:[{id, display_name, context_length?}]} 形态文本，
 * 供 extractModelInfos 统一解析。Anthropic 响应本身即该形态，原样返回。
 */
fun normalizeModelsBody(protocol: String, bodyText: String): String {
    if (protocol != ProviderProtocol.GEMINI) return bodyText
    return try {
        val root = ChatJson.json.parseToJsonElement(bodyText).jsonObject
        val models = root["models"] as? JsonArray ?: return bodyText
        val data = models.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.stringAt("name")?.removePrefix("models/") ?: return@mapNotNull null
            // 只保留可调 generateContent 的生成模型，剔除 embed/aqa 等专用模型
            val methods = stringListOf(obj, "supportedGenerationMethods")
            if (methods.isNotEmpty() && methods.none { it == "generateContent" }) return@mapNotNull null
            buildJsonObject {
                put("id", name)
                obj.stringAt("displayName")?.let { put("display_name", it) }
                obj.intAt("inputTokenLimit")?.let { put("context_length", it) }
            }
        }
        buildJsonObject { put("data", JsonArray(data)) }.toString()
    } catch (_: Exception) {
        bodyText
    }
}

// ==================== 图片生成 ====================

/**
 * 生图响应解析中间态：字节与临时 URL 二选一（url 形态由调用方立即下载转字节，避免链接过期），
 * revisedText 为上游附带的改写提示（OpenAI revised_prompt / Gemini 文本 parts）。
 */
data class ImageGenPayload(
    val bytes: ByteArray?,
    val mimeType: String?,
    val url: String?,
    val revisedText: String?,
)

/**
 * 解析生图响应：
 * - openai：POST /images/generations → data[0] 的 b64_json 或 url（gpt-image 系恒 b64，智谱等回 url）
 * - gemini：generateContent + responseModalities:[TEXT,IMAGE] → parts 中的 inlineData（base64）
 */
fun parseImageResponse(protocol: String, bodyText: String): ImageGenPayload {
    val root = ChatJson.json.parseToJsonElement(bodyText).jsonObject
    return when (protocol) {
        ProviderProtocol.GEMINI -> {
            val parts = (((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                ?: throw ApiException("响应中没有图片内容")
            val texts = StringBuilder()
            var bytes: ByteArray? = null
            var mime = "image/png"
            for (el in parts) {
                val part = el as? JsonObject ?: continue
                (part["text"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.let(texts::append)
                val inline = part["inlineData"] as? JsonObject ?: continue
                val data = inline.stringAt("data") ?: continue
                bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
                inline.stringAt("mimeType")?.let { mime = it }
            }
            if (bytes == null) throw ApiException("响应中没有图片内容")
            ImageGenPayload(bytes, mime, null, texts.toString().takeIf { it.isNotBlank() })
        }
        else -> {
            val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw ApiException("响应中没有图片内容")
            val b64 = first.stringAt("b64_json")
            val url = first.stringAt("url")
            val revised = first.stringAt("revised_prompt")
            when {
                !b64.isNullOrBlank() -> ImageGenPayload(
                    runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                        ?: throw ApiException("图片 base64 解码失败"),
                    "image/png", null, revised,
                )
                !url.isNullOrBlank() -> ImageGenPayload(null, null, url, revised)
                else -> throw ApiException("响应中没有图片内容")
            }
        }
    }
}
