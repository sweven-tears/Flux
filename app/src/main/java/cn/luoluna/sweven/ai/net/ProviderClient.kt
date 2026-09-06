package cn.luoluna.sweven.ai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
    data class Failed(val message: String) : ChatEvent
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

    suspend fun listModels(baseUrl: String, apiKey: String?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/models")
                    .header("Authorization", "Bearer ${apiKey.orEmpty()}")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(parseErrorMessage(resp.code, body))
                    extractModelNames(body)
                }
            }
        }

    suspend fun testConnection(baseUrl: String, apiKey: String?): TestResult {
        val start = System.currentTimeMillis()
        return try {
            val models = listModels(baseUrl, apiKey).getOrThrow()
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
    suspend fun openChat(baseUrl: String, apiKey: String?, body: JsonObject): Response =
        withContext(Dispatchers.IO) {
            val jsonBody = body.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer ${apiKey.orEmpty()}")
                .header("Accept", "text/event-stream, application/json")
                .post(jsonBody)
                .build()
            streamHttp.newCall(request).execute()
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
    fun chatDeltas(baseUrl: String, apiKey: String?, body: JsonObject): Flow<ChatEvent> = flow {
        val resp = try {
            openChat(baseUrl, apiKey, body)
        } catch (e: Exception) {
            emit(ChatEvent.Failed("连接失败: ${e.message ?: e.javaClass.simpleName}"))
            return@flow
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                emit(ChatEvent.Failed(parseErrorMessage(r.code, readBody(r))))
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
                val delta = parseDeltaContent(payload)
                if (!delta.isNullOrEmpty()) emit(ChatEvent.Delta(delta))
            }
            emit(ChatEvent.Done)
        }
    }.flowOn(Dispatchers.IO)
}
