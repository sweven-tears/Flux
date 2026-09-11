package cn.luoluna.sweven.ai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

data class TestResult(
    val ok: Boolean,
    val latencyMs: Long,
    val modelCount: Int,
    val message: String,
)

/** 生图最终结果：图片字节 + MIME + 上游附带的改写提示（若有） */
class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val revisedText: String?,
)

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class ReasoningDelta(val text: String) : ChatEvent

    /** 流式失败；HTTP 错误时附带状态码与原始响应体，供聊天页错误详情展示 */
    data class Failed(
        val message: String,
        val httpCode: Int? = null,
        val rawBody: String? = null,
    ) : ChatEvent
    data object Done : ChatEvent
}

/**
 * 调用模型厂商接口，按厂商协议分发：
 * - openai：{base}/chat/completions、{base}/models，Bearer 鉴权
 * - gemini：{base}/models/{model}:streamGenerateContent、{base}/models，x-goog-api-key 鉴权
 * - anthropic：{base}/messages、{base}/models，x-api-key + anthropic-version 鉴权
 *
 * 调用方始终传规范 OpenAI 形态请求体，本类在边界处转换为各协议原生请求体，
 * 并把流式/非流式响应归一化为 ChatEvent / OpenAI chat completion 形态。
 */
class ProviderClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // 流式请求读超时置 0，避免长回复被截断
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun listModels(
        baseUrl: String,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Result<List<RemoteModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl.trimEnd('/')
                val url = when (protocol) {
                    ProviderProtocol.GEMINI -> "$base/models?pageSize=1000"
                    ProviderProtocol.ANTHROPIC -> "$base/models?limit=1000"
                    else -> "$base/models"
                }
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .apply {
                        applyAuth(protocol, apiKey)
                        extraHeaders.forEach { (k, v) -> header(k, v) }
                    }
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(parseErrorMessage(resp.code, body))
                    extractModelInfos(normalizeModelsBody(protocol, body))
                }
            }
        }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val models = listModels(baseUrl, apiKey, extraHeaders, protocol).getOrThrow()
            TestResult(
                ok = true,
                latencyMs = System.currentTimeMillis() - start,
                modelCount = models.size,
                message = "连接成功，发现 ${models.size} 个模型",
            )
        } catch (e: Exception) {
            val msg = when (e) {
                is ApiException -> e.message ?: "请求失败"
                else -> "连接失败: ${e.message ?: e.javaClass.simpleName}"
            }
            TestResult(
                ok = false,
                latencyMs = System.currentTimeMillis() - start,
                modelCount = 0,
                message = msg,
            )
        }
    }

    /** 打开流式/非流式 chat completion 连接，调用方负责 close */
    suspend fun openChat(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Response =
        withContext(Dispatchers.IO) {
            streamHttp.newCall(chatRequest(protocol, baseUrl, apiKey, body, extraHeaders)).execute()
        }

    /** 构建未执行的流式请求 Call（供调用方在超时时 cancel 整个请求，如智能路由） */
    fun newStreamingCall(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Call = streamHttp.newCall(chatRequest(protocol, baseUrl, apiKey, body, extraHeaders))

    private fun Request.Builder.applyAuth(protocol: String, apiKey: String?): Request.Builder = apply {
        when (protocol) {
            ProviderProtocol.GEMINI -> header("x-goog-api-key", apiKey.orEmpty())
            ProviderProtocol.ANTHROPIC -> {
                header("x-api-key", apiKey.orEmpty())
                header("anthropic-version", ANTHROPIC_VERSION)
            }
            else -> header("Authorization", "Bearer ${apiKey.orEmpty()}")
        }
    }

    private fun chatRequest(
        protocol: String,
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String>,
    ): Request {
        val base = baseUrl.trimEnd('/')
        val url = when (protocol) {
            // gemini 流式与否由端点决定，body 中不含 stream 键
            ProviderProtocol.GEMINI -> {
                val model = body.stringAt("model").orEmpty().removePrefix("models/")
                val action = if (streamFlagOf(body)) "streamGenerateContent?alt=sse" else "generateContent"
                "$base/models/$model:$action"
            }
            ProviderProtocol.ANTHROPIC -> "$base/messages"
            else -> "$base/chat/completions"
        }
        val jsonBody = toUpstreamBody(protocol, body).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream, application/json")
            .apply {
                applyAuth(protocol, apiKey)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(jsonBody)
            .build()
    }

    /** 非流式：读取完整响应体文本（可能为上游错误信息） */
    suspend fun readBody(resp: Response): String = withContext(Dispatchers.IO) {
        resp.body?.string().orEmpty()
    }

    /** 读取 SSE 原始 data 载荷（不含 [DONE]），供局域网代理对 OpenAI 兼容上游逐行透传 */
    fun ssePayloads(resp: Response): Flow<String> = flow {
        val source = resp.body!!.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            emit(payload)
        }
    }.flowOn(Dispatchers.IO)

    /** 聊天流式输出（App 内聊天使用） */
    fun chatDeltas(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Flow<ChatEvent> = flow {
        val resp = try {
            openChat(baseUrl, apiKey, body, extraHeaders, protocol)
        } catch (e: Exception) {
            emit(ChatEvent.Failed("连接失败: ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }
        chatEvents(resp, protocol).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** 从已打开的响应读取流式事件（负责 close，供智能路由等复用）；按协议解析 SSE 帧并归一化 */
    fun chatEvents(resp: Response, protocol: String = ProviderProtocol.OPENAI): Flow<ChatEvent> = flow {
        resp.use { r ->
            if (!r.isSuccessful) {
                val body = readBody(r)
                emit(ChatEvent.Failed(parseErrorMessage(r.code, body), r.code, body.takeIf { it.isNotBlank() }))
                return@flow
            }
            val source = r.body?.source()
            if (source == null) {
                emit(ChatEvent.Failed("响应为空"))
                return@flow
            }
            loop@ while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                when (protocol) {
                    ProviderProtocol.GEMINI -> {
                        val chunk = parseGeminiChunk(payload)
                        if (chunk.error != null) {
                            emit(ChatEvent.Failed(chunk.error))
                            return@flow
                        }
                        chunk.reasoning?.let { emit(ChatEvent.ReasoningDelta(it)) }
                        chunk.text?.let { emit(ChatEvent.Delta(it)) }
                    }
                    ProviderProtocol.ANTHROPIC -> {
                        val chunk = parseAnthropicChunk(payload)
                        when {
                            chunk.error != null -> {
                                emit(ChatEvent.Failed(chunk.error))
                                return@flow
                            }
                            chunk.done -> break@loop
                            else -> {
                                chunk.reasoning?.let { emit(ChatEvent.ReasoningDelta(it)) }
                                chunk.text?.let { emit(ChatEvent.Delta(it)) }
                            }
                        }
                    }
                    else -> {
                        if (payload == "[DONE]") break@loop
                        val reasoning = parseReasoningContent(payload)
                        if (!reasoning.isNullOrEmpty()) emit(ChatEvent.ReasoningDelta(reasoning))
                        val delta = parseDeltaContent(payload)
                        if (!delta.isNullOrEmpty()) emit(ChatEvent.Delta(delta))
                    }
                }
            }
            emit(ChatEvent.Done)
        }
    }.flowOn(Dispatchers.IO)

    /** 非流式聊天：请求并返回完整响应 JSON；原生协议响应归一化为 OpenAI chat completion 形态 */
    suspend fun chatOnce(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val request = chatRequest(protocol, baseUrl, apiKey, body, extraHeaders)
            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw HttpApiException(resp.code, respBody, parseErrorMessage(resp.code, respBody))
                }
                toOpenAiChatResponse(protocol, ChatJson.json.parseToJsonElement(respBody).jsonObject)
            }
        }
    }

    /**
     * 文生图：openai 走 {base}/images/generations；gemini 走 generateContent + responseModalities。
     * Anthropic 无生图接口（调用方提前拦截）。返回字节结果；上游回临时 URL 时立即下载，避免过期。
     */
    suspend fun generateImage(
        baseUrl: String,
        apiKey: String?,
        model: String,
        prompt: String,
        extraHeaders: Map<String, String> = emptyMap(),
        protocol: String = ProviderProtocol.OPENAI,
    ): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl.trimEnd('/')
            val (url, body) = when (protocol) {
                ProviderProtocol.GEMINI -> "$base/models/${model.removePrefix("models/")}:generateContent" to
                    buildJsonObject {
                        put(
                            "contents",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                                    },
                                )
                            },
                        )
                        put(
                            "generationConfig",
                            buildJsonObject {
                                put(
                                    "responseModalities",
                                    buildJsonArray { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) },
                                )
                            },
                        )
                    }
                else -> "$base/images/generations" to buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("n", 1)
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    applyAuth(protocol, apiKey)
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                }
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw HttpApiException(resp.code, respBody, parseErrorMessage(resp.code, respBody))
                }
                val payload = parseImageResponse(protocol, respBody)
                when {
                    payload.bytes != null ->
                        GeneratedImage(payload.bytes, payload.mimeType ?: "image/png", payload.revisedText)
                    payload.url != null -> {
                        val (bytes, mime) = downloadImageBytes(payload.url)
                        GeneratedImage(bytes, mime, payload.revisedText)
                    }
                    else -> throw ApiException("响应中没有图片内容")
                }
            }
        }
    }

    /** 下载厂商返回的临时图片 URL 为字节（部分厂商的 url 短时会过期） */
    private suspend fun downloadImageBytes(url: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("图片下载失败（HTTP ${resp.code}）")
            val bytes = resp.body?.bytes() ?: throw ApiException("图片下载失败（空响应）")
            val mime = resp.body?.contentType()?.let { "${it.type}/${it.subtype}" } ?: "image/png"
            bytes to mime
        }
    }
}
