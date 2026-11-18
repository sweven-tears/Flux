package cn.luoluna.sweven.ai.net.video

import cn.luoluna.sweven.ai.net.ApiException
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.parseErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.net.URLEncoder

// ---------------- 共享请求助手 ----------------

private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
    headers.forEach { (k, v) -> header(k, v) }
    return this
}

internal suspend fun videoGetJson(
    url: String,
    apiKey: String?,
    headers: Map<String, String>,
    authMode: String = "bearer",
): Result<JsonObject> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .videoAuth(apiKey, authMode)
            .applyHeaders(headers)
            .get()
            .build()
        VideoHttp.client.newCall(request).execute().use { resp ->
            val text = resp.throwIfFailed(apiKey)
            JSON.parseToJsonElement(text).jsonObject
        }
    }
}

internal suspend fun videoPostJson(
    url: String,
    apiKey: String?,
    headers: Map<String, String>,
    authMode: String = "bearer",
    body: JsonObject,
): Result<JsonObject> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .videoAuth(apiKey, authMode)
            .applyHeaders(headers)
            .post(postJsonBody(body))
            .build()
        VideoHttp.client.newCall(request).execute().use { resp ->
            val text = resp.throwIfFailed(apiKey)
            JSON.parseToJsonElement(text).jsonObject
        }
    }
}

internal suspend fun videoDelete(
    url: String,
    apiKey: String?,
    headers: Map<String, String>,
    authMode: String = "bearer",
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder()
            .url(url)
            .videoAuth(apiKey, authMode)
            .applyHeaders(headers)
            .delete()
            .build()
        VideoHttp.client.newCall(request).execute().use { it.close() }
    }
}

/** 经 [downloadVideoBytes] 下载；URL 为空时报错（部分形态没有 /content 兜底端点） */
internal suspend fun downloadExtractedUrl(
    url: String?,
    apiKey: String?,
    authMode: String = "bearer",
): Result<ByteArray> {
    val target = url ?: return Result.failure(ApiException("查询响应中未包含视频 URL"))
    return downloadVideoBytes(target, apiKey, authMode)
}

// ---------------- sora：OpenAI 兼容 /videos（创建→轮询→/content 下载） ----------------

class SoraCompatVideoAdapter : VideoApiAdapter {
    override val shapeId = "sora"
    private fun base(ctx: VideoRequestContext) = ctx.baseUrl.trimEnd('/')

    override suspend fun submit(ctx: VideoRequestContext): Result<String> = videoPostJson(
        "${base(ctx)}/videos", ctx.apiKey, ctx.headers,
        body = buildJsonObject {
            put("model", ctx.model)
            put("prompt", ctx.prompt)
            ctx.extraBody?.forEach { (k, v) -> put(k, v) }
        },
    ).mapCatching { it.str("id") ?: throw ApiException("上游未返回任务 id") }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> =
        videoGetJson("${base(ctx)}/videos/$jobKey", ctx.apiKey, ctx.headers).mapCatching { obj ->
            VideoTask(
                status = normalizeVideoStatus(obj.str("status")),
                videoUrl = extractVideoUrl(obj),
                error = obj.obj("error")?.str("message"),
            )
        }

    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> =
        videoDelete("${base(ctx)}/videos/$jobKey", ctx.apiKey, ctx.headers)

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> {
        task?.videoUrl?.let { return downloadVideoBytes(it, ctx.apiKey) }
        return downloadVideoBytes("${base(ctx)}/videos/$jobKey/content", ctx.apiKey)
    }
}

// ---------------- agnes：/videos 创建 + 主机根 /agnesapi 查询（video_id + model_name） ----------------

class AgnesVideoAdapter : VideoApiAdapter {
    override val shapeId = "agnes"
    private fun base(ctx: VideoRequestContext) = ctx.baseUrl.trimEnd('/')

    override suspend fun submit(ctx: VideoRequestContext): Result<String> = videoPostJson(
        "${base(ctx)}/videos", ctx.apiKey, ctx.headers,
        body = buildJsonObject {
            put("model", ctx.model)
            put("prompt", ctx.prompt)
            put("mode", "text") // agnes 必填；customBody 可覆盖为 keyframe/reference
            ctx.extraBody?.forEach { (k, v) -> put(k, v) }
        },
    ).mapCatching {
        // 查询用的是 video_id（id/task_id 是任务 ID）
        it.str("video_id") ?: it.str("task_id") ?: it.str("id")
        ?: throw ApiException("上游未返回 video_id")
    }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> {
        val root = hostRootOf(ctx.baseUrl)
        val model = URLEncoder.encode(ctx.model, "UTF-8")
        return videoGetJson("$root/agnesapi?video_id=$jobKey&model_name=$model", ctx.apiKey, ctx.headers)
            .mapCatching { obj ->
                VideoTask(
                    status = normalizeVideoStatus(obj.str("status")),
                    videoUrl = extractVideoUrl(obj),
                    error = obj.obj("error")?.str("message") ?: obj.str("detail"),
                )
            }
    }

    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> =
        videoDelete("${base(ctx)}/videos/$jobKey", ctx.apiKey, ctx.headers)

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> {
        task?.videoUrl?.let { return downloadVideoBytes(it, ctx.apiKey) }
        return downloadVideoBytes("${base(ctx)}/videos/$jobKey/content", ctx.apiKey)
    }
}

// ---------------- kling：/v1/videos/text2video + data.task_id（官方直连 JWT 暂不支持，走中转/百炼 Bearer） ----------------

class KlingVideoAdapter : VideoApiAdapter {
    override val shapeId = "kling"
    private fun base(ctx: VideoRequestContext) = ctx.baseUrl.trimEnd('/')

    override suspend fun submit(ctx: VideoRequestContext): Result<String> = videoPostJson(
        "${base(ctx)}/v1/videos/text2video", ctx.apiKey, ctx.headers,
        body = buildJsonObject {
            put("model_name", ctx.model)
            put("prompt", ctx.prompt)
            ctx.extraBody?.forEach { (k, v) ->
                if (k == "seconds") put("duration", v) else put(k, v)
            }
        },
    ).mapCatching {
        it.obj("data")?.str("task_id") ?: it.str("task_id") ?: it.str("id")
        ?: throw ApiException("上游未返回 task_id")
    }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> =
        videoGetJson("${base(ctx)}/v1/videos/text2video/$jobKey", ctx.apiKey, ctx.headers).mapCatching { obj ->
            val data = obj.obj("data")
            VideoTask(
                status = normalizeVideoStatus(data?.str("task_status")),
                videoUrl = extractVideoUrl(obj),
                error = data?.str("task_status_msg") ?: obj.str("message"),
            )
        }

    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> =
        videoDelete("${base(ctx)}/v1/videos/text2video/$jobKey", ctx.apiKey, ctx.headers)

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> =
        downloadExtractedUrl(task?.videoUrl, ctx.apiKey)
}

// ---------------- minimax：/v1/video_generation + file_id 二跳取下载地址 ----------------

class MinimaxVideoAdapter : VideoApiAdapter {
    override val shapeId = "minimax"
    private fun base(ctx: VideoRequestContext) = ctx.baseUrl.trimEnd('/')

    override suspend fun submit(ctx: VideoRequestContext): Result<String> = videoPostJson(
        "${base(ctx)}/v1/video_generation", ctx.apiKey, ctx.headers,
        body = buildJsonObject {
            put("model", ctx.model)
            put("prompt", ctx.prompt)
            ctx.extraBody?.forEach { (k, v) -> put(k, v) }
        },
    ).mapCatching { it.str("task_id") ?: it.str("id") ?: throw ApiException("上游未返回 task_id") }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> =
        videoGetJson("${base(ctx)}/v1/query/video_generation?task_id=$jobKey", ctx.apiKey, ctx.headers)
            .mapCatching { obj ->
                val status = normalizeVideoStatus(obj.str("status"))
                var videoUrl = extractVideoUrl(obj)
                if (status == "completed" && videoUrl == null) {
                    // Success 但响应只有 file_id：二跳 files/retrieve 拿 download_url
                    val fileId = obj.str("file_id")
                    if (fileId != null) {
                        videoUrl = videoGetJson(
                            "${base(ctx)}/v1/files/retrieve?file_id=$fileId", ctx.apiKey, ctx.headers,
                        ).getOrNull()?.let { extractVideoUrl(it) }
                    }
                }
                VideoTask(
                    status = status,
                    videoUrl = videoUrl,
                    error = obj.obj("base_resp")?.str("status_msg"),
                )
            }

    // 官方无取消端点：返回成功（本地取消依然生效，上游任务自然终态不影响）
    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> = Result.success(Unit)

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> =
        downloadExtractedUrl(task?.videoUrl, ctx.apiKey)
}

// ---------------- runway：/v1/text_to_video + /v1/tasks/{id}（需 X-Runway-Version 头） ----------------

class RunwayVideoAdapter : VideoApiAdapter {
    override val shapeId = "runway"

    private fun headersOf(ctx: VideoRequestContext): Map<String, String> =
        if (ctx.headers.keys.any { it.equals("X-Runway-Version", true) }) {
            ctx.headers
        } else {
            ctx.headers + ("X-Runway-Version" to RUNWAY_VERSION)
        }

    /** 画幅映射：通用 aspect_ratio → runway 的像素 ratio */
    private fun ratioOf(aspect: String?): String? = when (aspect) {
        "16:9" -> "1280:720"
        "9:16" -> "720:1280"
        "1:1" -> "960:960"
        "4:3" -> "1104:832"
        "3:4" -> "832:1104"
        "21:9" -> "1584:672"
        else -> aspect
    }

    override suspend fun submit(ctx: VideoRequestContext): Result<String> {
        val extras = ctx.extraBody
        val duration = extras?.str("duration") ?: extras?.str("seconds")
        val ratio = ratioOf(extras?.str("aspect_ratio") ?: extras?.str("ratio") ?: extras?.str("size"))
        val body = buildJsonObject {
            put("model", ctx.model)
            put("promptText", ctx.prompt)
            ratio?.let { put("ratio", it) }
            duration?.let { put("duration", it.toIntOrNull() ?: 5) }
            extras?.forEach { (k, v) ->
                if (k !in setOf("duration", "seconds", "aspect_ratio", "ratio", "size")) put(k, v)
            }
        }
        return videoPostJson(
            "${ctx.baseUrl.trimEnd('/')}/v1/text_to_video", ctx.apiKey, headersOf(ctx), body = body,
        ).mapCatching { it.str("id") ?: throw ApiException("上游未返回任务 id") }
    }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> =
        videoGetJson("${ctx.baseUrl.trimEnd('/')}/v1/tasks/$jobKey", ctx.apiKey, headersOf(ctx))
            .mapCatching { obj ->
                VideoTask(
                    status = normalizeVideoStatus(obj.str("status")),
                    videoUrl = extractVideoUrl(obj),
                    error = obj.str("failure") ?: obj.str("error"),
                )
            }

    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> =
        videoDelete("${ctx.baseUrl.trimEnd('/')}/v1/tasks/$jobKey", ctx.apiKey, headersOf(ctx))

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> =
        downloadExtractedUrl(task?.videoUrl, ctx.apiKey)

    private companion object {
        const val RUNWAY_VERSION = "2024-11-06"
    }
}

// ---------------- veo：Gemini 原生 predictLongRunning + operation 轮询（x-goog-api-key） ----------------

class VeoVideoAdapter : VideoApiAdapter {
    override val shapeId = "veo"
    private fun base(ctx: VideoRequestContext) = ctx.baseUrl.trimEnd('/')

    override suspend fun submit(ctx: VideoRequestContext): Result<String> {
        val parameters = buildJsonObject {
            ctx.extraBody?.forEach { (k, v) ->
                when (k) {
                    "seconds" -> put("durationSeconds", v)
                    "aspect_ratio" -> put("aspectRatio", v)
                    "negative_prompt" -> put("negativePrompt", v)
                    else -> put(k, v)
                }
            }
        }
        return videoPostJson(
            "${base(ctx)}/models/${ctx.model}:predictLongRunning", ctx.apiKey, ctx.headers, authMode = "goog",
            body = buildJsonObject {
                put(
                    "instances",
                    kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("prompt", ctx.prompt) })
                    },
                )
                put("parameters", parameters)
            },
        ).mapCatching { it.str("name") ?: throw ApiException("上游未返回 operation name") }
    }

    override suspend fun query(ctx: VideoRequestContext, jobKey: String): Result<VideoTask> =
        videoGetJson("${base(ctx)}/$jobKey", ctx.apiKey, ctx.headers, authMode = "goog").mapCatching { obj ->
            if (obj["done"]?.jsonPrimitive?.contentOrNull == "true") {
                val error = obj.obj("error")?.str("message")
                if (error != null) {
                    VideoTask("failed", error = error)
                } else {
                    VideoTask("completed", videoUrl = extractVideoUrl(obj))
                }
            } else {
                VideoTask("running")
            }
        }

    // operation 不提供取消端点：本地取消
    override suspend fun cancel(ctx: VideoRequestContext, jobKey: String): Result<Unit> = Result.success(Unit)

    override suspend fun download(ctx: VideoRequestContext, jobKey: String, task: VideoTask?): Result<ByteArray> =
        downloadExtractedUrl(task?.videoUrl, ctx.apiKey, authMode = "goog")
}
