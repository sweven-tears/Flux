package cn.luoluna.sweven.ai.net

import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.db.NetworkLogDao
import cn.luoluna.sweven.ai.data.db.NetworkLogEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * 全局网络日志：OkHttp 应用级拦截器统一打点（ProviderClient 与 VideoHttp 均已挂载，
 * 覆盖聊天/模型列表/连接测试/生图/图片下载/视频任务，含局域网网关转发的上游请求）。
 * 开关关闭时直接放行零开销；开启后请求头/体与响应头/体截断落库（network_logs），
 * 响应体在读取关闭时才算完成并写库，流式响应捕获的是 data: 帧原文前缀。
 */
object NetworkLog {

    private const val MAX_REQUEST_BODY = 64 * 1024
    private const val MAX_RESPONSE_BODY = 128 * 1024
    private const val MAX_ERROR = 500

    @Volatile private var dao: NetworkLogDao? = null
    @Volatile private var settings: AppSettings? = null

    // 单协程串行写库：拦截器线程只入队，避免网络高频请求拖慢 IO 线程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<NetworkLogEntity>(capacity = 256)

    fun init(app: App) {
        settings = app.settings
        dao = app.database.networkLogDao()
        scope.launch {
            for (entry in queue) {
                runCatching {
                    dao?.insert(entry)
                    dao?.prune(keepCount())
                }
            }
        }
    }

    private fun keepCount(): Int = settings?.networkLogKeep?.value ?: 300

    val interceptor = Interceptor { chain ->
        if (settings?.networkLogEnabled?.value != true) return@Interceptor chain.proceed(chain.request())
        val request = chain.request()
        val startedAt = System.currentTimeMillis()
        val requestHeaders = headersJson(request.headers)
        val requestBody = requestBodyText(request.body)
        val response = try {
            chain.proceed(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            submit(
                NetworkLogEntity(
                    at = startedAt,
                    tag = tagOf(request.url),
                    method = request.method,
                    url = SensitiveDataRedactor.redactUrl(request.url.toString()),
                    ok = false,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody?.let { SensitiveDataRedactor.redactText(it) },
                    error = SensitiveDataRedactor.redactText("连接失败: ${e.message ?: e.javaClass.simpleName}", maxLength = MAX_ERROR),
                )
            )
            throw e
        }
        val responseHeaders = headersJson(response.headers)
        // 二进制响应（图片/视频下载）不捕获内容，只记元信息
        val captureBody = isTextLike(response.body?.contentType())
        val capture = response.body?.let { ResponseCapture(it, MAX_RESPONSE_BODY) }
        val loggedResponse = if (capture != null) {
            response.newBuilder().body(capture).build()
        } else {
            response
        }
        loggedResponse.also {
            // 响应体关闭（读取完成/中断/取消）时落库：流式在透传结束的 finally close 触发
            capture?.onClosed = {
                submit(
                    NetworkLogEntity(
                        at = startedAt,
                        tag = tagOf(request.url),
                        method = request.method,
                        url = SensitiveDataRedactor.redactUrl(request.url.toString()),
                        httpCode = response.code,
                        ok = response.isSuccessful,
                        durationMs = System.currentTimeMillis() - startedAt,
                        requestHeaders = requestHeaders,
                        requestBody = requestBody?.let { SensitiveDataRedactor.redactText(it) },
                        responseHeaders = responseHeaders,
                        responseBody = if (captureBody) capture.capturedText()?.let { SensitiveDataRedactor.redactText(it) } else null,
                        error = if (response.isSuccessful) null else SensitiveDataRedactor.redactText("HTTP ${response.code}", maxLength = MAX_ERROR),
                    )
                )
            }
        }
    }

    private fun submit(entry: NetworkLogEntity) {
        queue.trySend(entry)
    }

    /** 文本类响应体（JSON/文本/SSE）才捕获内容 */
    private fun isTextLike(mediaType: MediaType?): Boolean {
        mediaType ?: return true
        val s = "${mediaType.type}/${mediaType.subtype}".lowercase()
        return s.contains("json") || s.contains("text") || s.contains("event-stream") ||
            s.contains("xml") || s.contains("html")
    }

    private fun headersJson(headers: Headers): String = buildJsonObject {
        for (name in headers.names()) {
            put(name, SensitiveDataRedactor.redactHeader(name, headers.values(name).joinToString(", ")))
        }
    }.toString()

    /** 请求体读取为文本（one-shot body 等读取失败时忽略）；超长截断 */
    private fun requestBodyText(body: RequestBody?): String? {
        body ?: return null
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val all = buffer.readByteArray()
            val head = String(all, 0, minOf(all.size.toLong(), MAX_REQUEST_BODY.toLong()).toInt(), Charsets.UTF_8)
            if (all.size > MAX_REQUEST_BODY) "$head\n…（已截断）" else head
        }.getOrNull()
    }

    /** 按路径粗分类别：聊天补全/模型列表/生图/视频，其余 other */
    private fun tagOf(url: HttpUrl): String {
        val path = url.encodedPath
        return when {
            path.contains("chat/completions") || path.endsWith("/messages") ||
                path.contains("GenerateContent") -> "chat"
            path.contains("/models") -> "models"
            path.contains("/images") -> "image"
            path.contains("video", ignoreCase = true) -> "video"
            else -> "other"
        }
    }
}

/**
 * 响应体捕获包装：读取时把前 [maxBytes] 字节镜像进 [captured]，其余直通；
 * 底层 source 关闭（成功读完/中断/取消）时回调 [onClosed] 一次。
 */
private class ResponseCapture(
    private val delegate: ResponseBody,
    private val maxBytes: Int,
) : ResponseBody() {
    val captured = Buffer()

    /** 关闭回调；拦截器侧在构建响应后设置 */
    @Volatile var onClosed: (() -> Unit)? = null
    @Volatile private var closeNotified = false

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0 && captured.size < maxBytes) {
                // 从 sink 末尾镜像本次读到的字节（copyTo 不消费 sink）
                val take = minOf(read, maxBytes - captured.size)
                sink.copyTo(captured, sink.size - read, take)
            }
            return read
        }

        override fun close() {
            try {
                super.close()
            } finally {
                if (!closeNotified) {
                    closeNotified = true
                    onClosed?.invoke()
                }
            }
        }
    }.buffer()

    fun capturedText(): String? = runCatching {
        val snapshot = captured.copy()
        val text = snapshot.readUtf8()
        if (captured.size >= maxBytes) "$text\n…（已截断）" else text
    }.getOrNull()
}
