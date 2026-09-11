package cn.luoluna.sweven.ai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
 * 调用模型厂商的 OpenAI 兼容接口：
 * GET  {base}/models
 * POST {base}/chat/completions
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
    ): Result<List<RemoteModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/models")
                    .header("Authorization", "Bearer ${apiKey.orEmpty()}")
                    .header("Accept", "application/json")
                    .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(parseErrorMessage(resp.code, body))
                    extractModelInfos(body)
                }
            }
        }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val models = listModels(baseUrl, apiKey, extraHeaders).getOrThrow()
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
    ): Response =
        withContext(Dispatchers.IO) {
            streamHttp.newCall(chatRequest(baseUrl, apiKey, body, extraHeaders)).execute()
        }

    /** 构建未执行的流式请求 Call（供调用方在超时时 cancel 整个请求，如智能路由） */
    fun newStreamingCall(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Call = streamHttp.newCall(chatRequest(baseUrl, apiKey, body, extraHeaders))

    private fun chatRequest(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String>,
    ): Request {
        val jsonBody = body.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${apiKey.orEmpty()}")
            .header("Accept", "text/event-stream, application/json")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(jsonBody)
            .build()
    }

    /** 非流式：读取完整响应体文本（可能为上游错误信息） */
    suspend fun readBody(resp: Response): String = withContext(Dispatchers.IO) {
        resp.body?.string().orEmpty()
    }

    /** 读取 SSE 原始 data 载荷（不含 [DONE]），供局域网代理逐行透传 */
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
    ): Flow<ChatEvent> = flow {
        val resp = try {
            openChat(baseUrl, apiKey, body, extraHeaders)
        } catch (e: Exception) {
            emit(ChatEvent.Failed("连接失败: ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }
        chatEvents(resp).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** 从已打开的 chat completion 响应读取流式事件（负责 close，供智能路由等复用） */
    fun chatEvents(resp: Response): Flow<ChatEvent> = flow {
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
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                val reasoning = parseReasoningContent(payload)
                if (!reasoning.isNullOrEmpty()) emit(ChatEvent.ReasoningDelta(reasoning))
                val delta = parseDeltaContent(payload)
                if (!delta.isNullOrEmpty()) emit(ChatEvent.Delta(delta))
            }
            emit(ChatEvent.Done)
        }
    }.flowOn(Dispatchers.IO)

    /** 非流式聊天：stream=false 请求并返回完整响应 JSON（含 tool_calls 等原始字段） */
    suspend fun chatOnce(
        baseUrl: String,
        apiKey: String?,
        body: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBody = body.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer ${apiKey.orEmpty()}")
                .header("Accept", "application/json")
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .post(jsonBody)
                .build()
            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw HttpApiException(resp.code, respBody, parseErrorMessage(resp.code, respBody))
                }
                ChatJson.json.parseToJsonElement(respBody).jsonObject
            }
        }
    }
}
