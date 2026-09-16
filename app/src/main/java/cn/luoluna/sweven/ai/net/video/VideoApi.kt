package cn.luoluna.sweven.ai.net.video

import cn.luoluna.sweven.ai.net.ApiException
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.NetworkLog
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.parseErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** 一次视频任务的全部上游信息（适配器据此构造请求） */
data class VideoRequestContext(
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val headers: Map<String, String> = emptyMap(),
    /** 厂商聊天协议（gemini 提示可能走 veo 原生形态），仅作探测排序提示 */
    val protocol: String = ProviderProtocol.OPENAI,
    val prompt: String,
    /** 模型自定义 body 等附加参数（已解析 JSON 对象），适配器按自家文档翻译 */
    val extraBody: JsonObject? = null,
)

/** 归一化后的任务状态：running / completed / failed */
data class VideoTask(
    val status: String,
    val videoUrl: String? = null,
    val error: String? = null,
)

/**
 * 视频生成上游适配器：一种对接形态一个实现。
 * 加新厂商 = 加一个实现类并在 [VideoAdapters.all] 注册；
 * shapeId 会持久化到模型行（ModelEntity.videoApiShape），探测一次终身使用。
 */
interface VideoApiAdapter {
    val shapeId: String

    /** 提交任务，返回后续查询用的任务键（各形态含义不同：id/task_id/video_id/operation name） */
    suspend fun submit(ctx: VideoRequestContext): Result<String>

    /** 查询任务状态（归一化 + 尽量提取成品视频 URL） */
    suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask>

    /** 取消任务（尽力而为；上游不支持时本地取消依然有效） */
    suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit>

    /** 下载成品视频字节（[task] 携带查询时提取到的 URL 时优先直下） */
    suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray>
}

/** 视频子系统独立 HTTP：提交/查询常规超时，成品下载放宽到 10 分钟 */
internal object VideoHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 全局网络日志打点（设置页开关，关闭时零开销直通）；downloadClient 由本 client 派生自动继承
        .addInterceptor(NetworkLog.interceptor)
        .build()

    val downloadClient: OkHttpClient = client.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()
}

internal val JSON get() = ChatJson.json

internal fun Request.Builder.videoAuth(apiKey: String?, mode: String = "bearer"): Request.Builder {
    if (!apiKey.isNullOrBlank()) {
        when (mode) {
            "goog" -> header("x-goog-api-key", apiKey)
            else -> header("Authorization", "Bearer $apiKey")
        }
    }
    return this
}

internal fun postJsonBody(body: JsonObject) =
    body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

/** 解析上游错误：非 2xx 抛 HttpApiException（带原始体），消息经 parseErrorMessage（含 detail 键） */
internal fun Response.throwIfFailed(): String {
    val body = body?.string().orEmpty()
    if (!isSuccessful) throw HttpApiException(code, body, parseErrorMessage(code, body))
    return body
}

/** 主机根：剥掉 baseUrl 尾部的版本段（/v1、/v4 等），Agnes 的 /agnesapi 挂在主机根下 */
internal fun hostRootOf(baseUrl: String): String =
    baseUrl.trimEnd('/').replace(Regex("/v\\d+[a-zA-Z]*$"), "")

/** 全词表状态归一：各家词形（completed/succeed/Success/Queueing/Preparing/in_progress…）→ running/completed/failed */
fun normalizeVideoStatus(raw: String?): String = when (raw?.lowercase()) {
    "completed", "succeeded", "success", "succeed", "done" -> "completed"
    "failed", "error", "cancelled", "canceled" -> "failed"
    else -> "running"
}

private val URL_KEYS = setOf("url", "video_url", "download_url", "file_url", "uri", "video_uri")

/**
 * 从任务响应 JSON 提取成品视频 URL：
 * ① 键名优先（url/video_url/download_url/file_url/uri…，递归嵌套，覆盖 Kling task_result.videos[].url、
 *    Veo …video.uri、Runway output[]、MiniMax file.download_url 等）；
 * ② 找不到再深度优先找首个 http(s) 且含 .mp4/.webm/.mov 的字符串值。
 */
fun extractVideoUrl(root: JsonElement): String? {
    fun isHttp(s: String) = s.startsWith("http://") || s.startsWith("https://")
    fun byKey(el: JsonElement): String? = when (el) {
        is JsonObject -> {
            el.forEach { (k, v) ->
                if (v is JsonPrimitive && v.isString && k.lowercase() in URL_KEYS && isHttp(v.content)) {
                    return v.content
                }
            }
            el.values.firstNotNullOfOrNull { v ->
                if (v is JsonObject || v is JsonArray) byKey(v) else null
            }
        }
        is JsonArray -> el.firstNotNullOfOrNull { v ->
            if (v is JsonObject || v is JsonArray) byKey(v) else null
        }
        else -> null
    }
    byKey(root)?.let { return it }
    fun dfs(el: JsonElement): String? = when (el) {
        is JsonObject -> el.values.firstNotNullOfOrNull { dfs(it) }
        is JsonArray -> el.firstNotNullOfOrNull { dfs(it) }
        is JsonPrimitive -> el.contentOrNull?.takeIf {
            isHttp(it) && listOf(".mp4", ".webm", ".mov").any { ext -> it.lowercase().contains(ext) }
        }
        else -> null
    }
    return dfs(root)
}

/**
 * 从任意 URL 下载视频字节。**先裸下，401/403 才带 Bearer 重试**——
 * 预签名 URL（S3 类存储）不允许同时出现查询签名与 Authorization 头。
 */
internal suspend fun downloadVideoBytes(
    url: String,
    apiKey: String?,
    authMode: String = "bearer",
): Result<ByteArray> = withContext(Dispatchers.IO) {
    runCatching {
        fun exec(withAuth: Boolean): Response {
            val rb = Request.Builder().url(url).get()
            if (withAuth && !apiKey.isNullOrBlank()) rb.videoAuth(apiKey, authMode)
            return VideoHttp.downloadClient.newCall(rb.build()).execute()
        }
        var resp = exec(false)
        if ((resp.code == 401 || resp.code == 403) && !apiKey.isNullOrBlank()) {
            resp.close()
            resp = exec(true)
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                val errBody = r.body?.string().orEmpty()
                throw HttpApiException(r.code, errBody, parseErrorMessage(r.code, errBody))
            }
            r.body?.bytes() ?: throw ApiException("视频下载失败（空响应）")
        }
    }
}

/** 上游视频任务探测/记忆的注册表：shapeId 与 ModelEntity.videoApiShape 对应 */
object VideoAdapters {
    val all: List<VideoApiAdapter> by lazy {
        listOf(
            SoraCompatVideoAdapter(),
            AgnesVideoAdapter(),
            KlingVideoAdapter(),
            MinimaxVideoAdapter(),
            RunwayVideoAdapter(),
            VeoVideoAdapter(),
        )
    }

    fun byId(shapeId: String?): VideoApiAdapter? = shapeId?.let { id -> all.firstOrNull { it.shapeId == id } }

    /** 提交探测顺序：默认 sora 优先（中转站最大公约数）；gemini 协议提示 veo 原生形态优先 */
    fun probeOrder(protocol: String): List<VideoApiAdapter> =
        if (protocol == ProviderProtocol.GEMINI) {
            all.sortedByDescending { it.shapeId == "veo" }
        } else {
            all
        }
}

/** 简易 JSON 构造（适配器内部用） */
internal fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

internal fun JsonElement.str(vararg path: String): String? {
    var el: JsonElement = this
    for (key in path) {
        el = (el as? JsonObject)?.get(key) ?: return null
    }
    return (el as? JsonPrimitive)?.contentOrNull
}

internal fun JsonElement.obj(vararg path: String): JsonObject? {
    var el: JsonElement = this
    for (key in path) {
        el = (el as? JsonObject)?.get(key) ?: return null
    }
    return el as? JsonObject
}

internal fun JsonElement.at(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
