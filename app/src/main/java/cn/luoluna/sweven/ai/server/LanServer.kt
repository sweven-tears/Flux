package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.db.ref

import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.AbnormalVia
import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.RequestLogDao
import cn.luoluna.sweven.ai.data.db.RequestLogEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupDao
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteMemberDao
import cn.luoluna.sweven.ai.data.db.VideoJobDao
import cn.luoluna.sweven.ai.data.db.VideoJobEntity
import cn.luoluna.sweven.ai.data.repo.FailureKind
import cn.luoluna.sweven.ai.data.repo.ModelAbnormalRecorder
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.data.repo.RouteGroupTarget
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.data.repo.SmartPolicy
import cn.luoluna.sweven.ai.data.repo.aggregateCapability
import cn.luoluna.sweven.ai.data.repo.classifyFailure
import cn.luoluna.sweven.ai.data.repo.eligibleCandidates
import cn.luoluna.sweven.ai.data.repo.featuresOfRequestBody
import cn.luoluna.sweven.ai.data.repo.intersectCapability
import cn.luoluna.sweven.ai.data.repo.isSelfGatewayUrl
import cn.luoluna.sweven.ai.data.repo.modalityLabel
import cn.luoluna.sweven.ai.data.repo.orderCandidates
import cn.luoluna.sweven.ai.data.repo.pickFastRound
import cn.luoluna.sweven.ai.data.repo.terminal
import cn.luoluna.sweven.ai.data.repo.SMART_FAST_ROUND_LIMIT
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.extractUsage
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.net.parseRetryAfterMs
import cn.luoluna.sweven.ai.net.stringAt
import cn.luoluna.sweven.ai.net.streamFlagOf
import cn.luoluna.sweven.ai.net.toOpenAiChatResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import kotlin.coroutines.coroutineContext

/**
 * 局域网聚合路由服务器（OpenAI 兼容协议）：
 * - GET  /v1/models            模型列表：开启「开放所有模型」时含 厂商别名/模型名，另含路由分组与 flux-auto 聚合路由
 * - POST /v1/chat/completions  按 model 聚合 id 路由到对应厂商并代理转发
 * - 首次使用的设备需在 App 内批准（401 + 待授权申请）
 */
class LanServer(
    private val port: Int,
    private val router: ModelRouter,
    private val auth: DeviceAuthManager,
    private val client: ProviderClient,
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val smartRouteGroupDao: SmartRouteGroupDao,
    private val smartRouteMemberDao: SmartRouteMemberDao,
    private val settings: AppSettings,
    private val logDao: RequestLogDao,
    /** 模型异常记录写入点（真实上游失败落库，供「模型异常记录」页决策） */
    private val abnormalRecorder: ModelAbnormalRecorder,
    /** 视频生成（Sora 兼容 /v1/videos）：任务管理器与任务表 */
    private val videoJobs: VideoJobManager,
    private val videoJobDao: VideoJobDao,
    @Suppress("unused") private val authDao: AuthDeviceDao,
) {

    private var engine: io.ktor.server.engine.ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    private companion object {
        /** 请求日志保留条数，写入时自动修剪 */
        const val LOG_KEEP = 1000
    }

    /**
     * 请求日志落盘（开关关闭时忽略）。每条 /v1/chat/completions 请求一条，
     * 含校验失败路径；流式在透传结束/中断的 finally 里记，客户端断开按成功计。
     */
    private suspend fun ApplicationCall.logRequest(
        device: AuthDeviceEntity,
        model: String,
        via: String?,
        stream: Boolean,
        startedAt: Long,
        ttftMs: Long?,
        ok: Boolean,
        error: String?,
        promptTokens: Long? = null,
        completionTokens: Long? = null,
    ) {
        if (!settings.logRequestsEnabled.value) return
        runCatching {
            logDao.insert(
                RequestLogEntity(
                    at = System.currentTimeMillis(),
                    deviceId = device.deviceId,
                    ip = peerIp(),
                    model = model,
                    via = via,
                    stream = stream,
                    ttftMs = ttftMs,
                    totalMs = System.currentTimeMillis() - startedAt,
                    ok = ok,
                    error = error?.take(300),
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                )
            )
            logDao.prune(LOG_KEEP)
        }
    }

    /**
     * 落一条模型异常记录（真实上游失败：连接失败 / HTTP 错误 / 超时 / 流中断）。
     * 网关自判的校验失败（messages 缺失、未授权、分组为空等）不记，避免把网关自身的问题记到厂商头上。
     */
    private suspend fun recordAbnormal(
        provider: ProviderEntity,
        modelName: String,
        httpCode: Int?,
        error: String?,
    ) {
        abnormalRecorder.record(
            providerId = provider.id,
            providerAlias = provider.alias,
            modelName = modelName,
            via = AbnormalVia.LAN,
            httpCode = httpCode,
            error = error,
        )
    }

    /** 从流式载荷帧里提取 usage（仅帧内容含 "usage" 时才解析，避免每帧 parse） */
    private fun usageOfPayload(payload: String): Pair<Long?, Long?>? {
        if (!payload.contains("\"usage\"")) return null
        return runCatching {
            extractUsage(ChatJson.json.parseToJsonElement(payload).jsonObject)
        }.getOrNull()
    }

    fun start() {
        if (engine != null) return
        engine = embeddedServer(
            Netty,
            port = port,
            host = "0.0.0.0",
            configure = {
                // Netty 默认 10s 写超时会掐断慢客户端（省电模式/弱信号）的 SSE 长流，0 = 关闭
                responseWriteTimeoutSeconds = 0
                // 死连接靠 TCP keepalive 清理
                tcpKeepAlive = true
            },
        ) { module() }
            .start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        engine = null
    }

    private fun Application.module() {
        routing {
            get("/") {
                call.respondText(infoJson(), ContentType.Application.Json)
            }
            get("/v1/models") {
                if (call.authorize() == null) return@get
                call.respondText(aggregateModelsJson(), ContentType.Application.Json)
            }
            post("/v1/chat/completions") {
                val device = call.authorize() ?: return@post
                call.handleChatCompletion(device)
            }
            // 视频生成（Sora 兼容形态）：POST /v1/videos 创建 → GET /v1/videos/{id} 轮询 → /content 下载
            post("/v1/videos") {
                if (call.authorize() == null) return@post
                call.handleVideoCreate()
            }
            get("/v1/videos/{id}") {
                if (call.authorize() == null) return@get
                call.respondVideoStatus(call.parameters["id"].orEmpty())
            }
            get("/v1/videos/{id}/content") {
                if (call.authorize() == null) return@get
                call.respondVideoContent(call.parameters["id"].orEmpty())
            }
        }
    }

    private fun infoJson(): String = buildJsonObject {
        put("name", "Flux")
        // mDNS 生效时告知客户端主机名，可直接用 http://<host>:端口/v1 访问
        ServerState.mdnsHost.value?.let { put("mdns", it) }
        put("object", "phone_router_info")
        put("model_format", "厂商别名/模型名")
        put("auth", "首次使用需在 Flux App 内批准设备（请求头 X-Device-Id 可选）")
        put(
            "video_format",
            "视频生成（Sora 兼容）：POST /v1/videos {\"model\":\"厂商别名/模型名\",\"prompt\":\"...\"} " +
                "→ 轮询 GET /v1/videos/{id} 至 completed → GET /v1/videos/{id}/content 下载 mp4",
        )
        put(
            "endpoints",
            JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonPrimitive("GET /v1/models"),
                    kotlinx.serialization.json.JsonPrimitive("POST /v1/chat/completions"),
                    kotlinx.serialization.json.JsonPrimitive("POST /v1/videos"),
                    kotlinx.serialization.json.JsonPrimitive("GET /v1/videos/{id}"),
                    kotlinx.serialization.json.JsonPrimitive("GET /v1/videos/{id}/content"),
                )
            ),
        )
    }.toString()

    /**
     * 对端 IP（数值形式）。request.origin.remoteHost 会走 InetSocketAddress.getHostName()
     * 触发阻塞式反向 DNS——本机 127.0.0.1 立即命中 hosts，外部设备 IP 则要等 DNS 超时，
     * 每请求一次、动辄数秒。改为直接读 Netty channel 上已解析的 InetAddress，不做任何域名反解。
     */
    private fun ApplicationCall.peerIp(): String {
        val remote = (this as? NettyApplicationCall)
            ?.context?.channel()?.remoteAddress() as? InetSocketAddress
        return remote?.address?.hostAddress
            ?: request.origin.remoteHost
            ?: "unknown"
    }

    /** 设备鉴权：放行返回设备实体（响应模式等设备级配置来源）；拒绝时已响应 401/403，返回 null */
    private suspend fun ApplicationCall.authorize(): AuthDeviceEntity? {
        val ip = peerIp()
        val deviceId = request.headers[DeviceAuthManager.HEADER_DEVICE_ID]
            ?.takeIf { it.isNotBlank() }
            ?: "ip-$ip"
        return when (val decision = auth.check(deviceId, ip)) {
            is AuthDecision.Allowed -> decision.device
            AuthDecision.Blocked -> {
                respondOpenAiError(HttpStatusCode.Forbidden, "该设备已被禁止使用聚合模型")
                null
            }
            is AuthDecision.NeedsApproval -> {
                respondOpenAiError(
                    HttpStatusCode.Unauthorized,
                    "设备未授权：请在 Flux App 的「局域网」页面批准该设备 (ID: $deviceId)。" +
                        "Device not authorized. Approve it in the Flux app.",
                )
                null
            }
        }
    }

    private suspend fun aggregateModelsJson(): String {
        // 停用厂商的模型不对外提供
        val providers = providerDao.all().filter { it.enabled }.associateBy { it.id }
        val enabled = modelDao.observeEnabled().first()
        val data = mutableListOf<JsonElement>()
        // 单个模型（厂商别名/模型名）：仅在「开放所有模型」时对外暴露，关闭后客户端只能选聚合路由
        if (settings.lanOpenAllModels.value) {
            data += enabled.mapNotNull { m ->
                val p = providers[m.providerId] ?: return@mapNotNull null
                buildJsonObject {
                    put("id", "${p.alias}/${m.ref}")
                    put("object", "model")
                    put("owned_by", p.alias)
                    put(
                        "phone_router",
                        buildJsonObject {
                            put("display", m.alias?.takeIf { it.isNotBlank() } ?: m.ref)
                            put("type", m.type)
                            put("input", modalities(m.inputText, m.inputImage, m.inputVideo))
                            put("output", modalities(m.outputText, m.outputImage, m.outputVideo))
                            put("capabilities", modalities(m.capabilityTools, m.capabilityReasoning, false))
                        }
                    )
                }
            }
        }
        // 聚合路由 flux-auto：候选为全部启用模型（厂商顺序+模型顺序），能力取并集；无候选时不出现
        val chain = router.allModelsChain()
        if (settings.lanSmartAiEnabled.value && chain.isNotEmpty()) {
            val agg = aggregateCapability(chain.map { it.second })
            data += buildJsonObject {
                put("id", SmartAi.MODEL_NAME)
                put("object", "model")
                put("owned_by", SmartAi.MODEL_NAME)
                put(
                    "phone_router",
                    buildJsonObject {
                        put("display", "${SmartAi.MODEL_NAME}（全部模型聚合）")
                        put("type", "chat")
                        put("input", modalities(agg.inputText, agg.inputImage, agg.inputVideo))
                        put("output", modalities(agg.outputText, agg.outputImage, agg.outputVideo))
                        put("capabilities", modalities(agg.tools, agg.reasoning, false))
                    }
                )
            }
        }
        // 路由分组：启用且至少有一个可用成员的分组以 flux-<groupKey> 对外暴露（能力取成员交集，短板口径）
        val groups = smartRouteGroupDao.all().filter { it.enabled }
        if (groups.isNotEmpty()) {
            val allMembers = smartRouteMemberDao.all()
            groups.forEach { g ->
                val memberModels = allMembers
                    .filter { it.groupId == g.id && it.enabled && it.providerId in providers }
                    .mapNotNull { modelDao.find(it.providerId, it.modelName) }
                if (memberModels.isEmpty()) return@forEach
                val agg = intersectCapability(memberModels)
                data += buildJsonObject {
                    put("id", g.routeName)
                    put("object", "model")
                    put("owned_by", "flux")
                    put(
                        "phone_router",
                        buildJsonObject {
                            put("display", "${g.routeName}（路由分组 · ${memberModels.size} 成员组内切换）")
                            put("type", "chat")
                            put("input", modalities(agg.inputText, agg.inputImage, agg.inputVideo))
                            put("output", modalities(agg.outputText, agg.outputImage, agg.outputVideo))
                            put("capabilities", modalities(agg.tools, agg.reasoning, false))
                        }
                    )
                }
            }
        }
        return buildJsonObject {
            put("object", "list")
            put("data", JsonArray(data))
        }.toString()
    }

    private fun modalities(text: Boolean, image: Boolean, video: Boolean): JsonArray {
        val list = mutableListOf<kotlinx.serialization.json.JsonPrimitive>()
        if (text) list.add(kotlinx.serialization.json.JsonPrimitive("text"))
        if (image) list.add(kotlinx.serialization.json.JsonPrimitive("image"))
        if (video) list.add(kotlinx.serialization.json.JsonPrimitive("video"))
        return JsonArray(list)
    }

    private suspend fun ApplicationCall.handleChatCompletion(device: AuthDeviceEntity) {
        val startedAt = System.currentTimeMillis()
        val raw = try {
            receiveText()
        } catch (e: Exception) {
            logRequest(device, "-", null, false, startedAt, null, false, "读取请求体失败: ${e.message}")
            respondOpenAiError(HttpStatusCode.BadRequest, "读取请求体失败: ${e.message}")
            return
        }
        val body: JsonObject = try {
            ChatJson.json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            logRequest(device, "-", null, false, startedAt, null, false, "请求体不是合法 JSON: ${e.message}")
            respondOpenAiError(HttpStatusCode.BadRequest, "请求体不是合法 JSON: ${e.message}")
            return
        }
        val aggregateId = body.stringAt("model")
        if (aggregateId.isNullOrBlank()) {
            logRequest(device, "-", null, false, startedAt, null, false, "model 字段缺失")
            respondOpenAiError(HttpStatusCode.BadRequest, "model 字段必填，格式：厂商别名/模型名")
            return
        }
        if (aggregateId == SmartAi.MODEL_NAME) {
            // 响应模式按请求设备区分：不同设备对首字延迟的需求不同
            handleSmartCompletion(body, device, device.responseMode, startedAt)
            return
        }
        // 路由分组：flux-<groupKey>（分组键不含 /，与「厂商别名/模型名」聚合 id 区分开）
        if ('/' !in aggregateId) {
            RouteGroupTarget.groupKeyOf(aggregateId)?.let { key ->
                val group = smartRouteGroupDao.byKey(key)
                if (group == null || !group.enabled) {
                    logRequest(device, aggregateId, null, false, startedAt, null, false, "路由分组不存在或已禁用")
                    respondOpenAiError(
                        HttpStatusCode.NotFound,
                        "路由分组 '$aggregateId' 不存在或已禁用，可用模型见 GET /v1/models",
                    )
                    return
                }
                handleGroupCompletion(group, body, device, device.responseMode, startedAt)
                return
            }
        }
        // 单个模型：仅「开放所有模型」时可用，关闭后只留路由分组与 flux-auto 聚合路由
        if (!settings.lanOpenAllModels.value) {
            logRequest(device, aggregateId, null, false, startedAt, null, false, "未开放单个模型")
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "模型 '$aggregateId' 未开放：Flux App 的「局域网」页已关闭「开放所有模型」，" +
                    "当前只提供路由分组与 ${SmartAi.MODEL_NAME} 聚合路由，可用模型见 GET /v1/models",
            )
            return
        }
        val route = router.resolveByAggregateId(aggregateId)
        if (route == null) {
            logRequest(device, aggregateId, null, false, startedAt, null, false, "模型不存在")
            respondOpenAiError(HttpStatusCode.NotFound, "模型 '$aggregateId' 不存在，聚合模型 id 格式：厂商别名/模型名，可用模型见 GET /v1/models")
            return
        }
        val apiKey = route.apiKey
        if (apiKey == null || apiKey.key.isBlank()) {
            logRequest(device, aggregateId, null, false, startedAt, null, false, "厂商 '${route.provider.alias}' 未配置 API Key")
            respondOpenAiError(HttpStatusCode.BadRequest, "厂商 '${route.provider.alias}' 尚未配置 API Key")
            return
        }
        val messages = body["messages"] as? JsonArray
        if (messages == null) {
            logRequest(device, aggregateId, null, false, startedAt, null, false, "messages 字段缺失")
            respondOpenAiError(HttpStatusCode.BadRequest, "messages 字段必填")
            return
        }
        val stream = streamFlagOf(body)
        val modelName = aggregateId.substring(aggregateId.indexOf('/') + 1)
        // 请求体用上游模型 id（name）；区分名 refName 只用于会话/网关引用，绝不能发给上游
        val upstreamName = route.model?.name ?: modelName
        var payload = buildChatBody(
            modelName = upstreamName,
            messages = messages,
            stream = stream,
            defaults = route.advancedParams,
            base = body,
        )
        payload = mergeCustomBody(payload, route.model?.customBody)
        if ((route.provider.presetId == "gemini" || route.provider.protocol == ProviderProtocol.GEMINI) && route.model != null) {
            payload = applyBuiltinTools(
                payload,
                route.model.builtinGoogleSearch,
                route.model.builtinUrlContext,
                route.model.builtinImageGen,
            )
        }
        val extraHeaders = parseCustomHeaders(route.model?.customHeaders)
        val via = "${route.provider.alias}/$modelName"
        val protocol = route.provider.protocol

        val upstream = try {
            client.openChat(route.provider.baseUrl, apiKey.key, payload, extraHeaders, protocol)
        } catch (e: Exception) {
            val message = "上游连接失败: ${e.message ?: e.javaClass.simpleName}"
            logRequest(device, aggregateId, via, stream, startedAt, null, false, message)
            recordAbnormal(route.provider, modelName, null, message)
            respondOpenAiError(HttpStatusCode.BadGateway, message)
            return
        }

        if (!upstream.isSuccessful) {
            val errBody = client.readBody(upstream)
            upstream.close()
            val message = "上游 HTTP ${upstream.code}: ${errBody.take(200)}"
            logRequest(device, aggregateId, via, stream, startedAt, null, false, message)
            recordAbnormal(route.provider, modelName, upstream.code, message)
            respondText(
                errBody.ifBlank { """{"error":{"message":"upstream HTTP ${upstream.code}"}}""" },
                ContentType.Application.Json,
                HttpStatusCode.fromValue(upstream.code),
            )
            return
        }

        if (stream) {
            var ttft: Long? = null
            var promptTok: Long? = null
            var completionTok: Long? = null
            var streamError: String? = null
            try {
                respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                    if (protocol == ProviderProtocol.OPENAI) {
                        // OpenAI 兼容上游：原始帧透传，保留 usage/finish_reason 等字段
                        client.ssePayloads(upstream).collect { payloadLine ->
                            if (ttft == null) ttft = System.currentTimeMillis() - startedAt
                            usageOfPayload(payloadLine)?.let { (p, c) ->
                                if (p != null) promptTok = p
                                if (c != null) completionTok = c
                            }
                            writeStringUtf8("data: $payloadLine\n\n")
                            flush()
                        }
                    } else {
                        // 原生协议上游：解析归一化后回译为 OpenAI chunk 帧
                        client.chatEvents(upstream, protocol).collect { ev ->
                            if (ttft == null) ttft = System.currentTimeMillis() - startedAt
                            chatEventToChunkLine(ev, modelName)?.let {
                                writeStringUtf8("data: $it\n\n")
                                flush()
                            }
                        }
                    }
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                }
            } catch (e: CancellationException) {
                // 客户端提前断开：数据已正常流动，按成功记录
                streamError = "客户端中断"
                throw e
            } catch (e: Exception) {
                streamError = "流式传输中断: ${e.message ?: e.javaClass.simpleName}"
                recordAbnormal(route.provider, modelName, null, streamError)
                throw e
            } finally {
                upstream.close()
                val err = streamError
                withContext(NonCancellable) {
                    logRequest(
                        device, aggregateId, via, stream, startedAt, ttft,
                        ok = err == null || err == "客户端中断",
                        error = err,
                        promptTokens = promptTok,
                        completionTokens = completionTok,
                    )
                }
            }
        } else {
            val respBody = client.readBody(upstream)
            upstream.close()
            if (protocol == ProviderProtocol.OPENAI) {
                val usage = runCatching {
                    extractUsage(ChatJson.json.parseToJsonElement(respBody).jsonObject)
                }.getOrNull()
                logRequest(
                    device, aggregateId, via, stream, startedAt, null, true, null,
                    usage?.first, usage?.second,
                )
                respondText(respBody, ContentType.Application.Json)
            } else {
                // 原生协议非流式响应归一化为 OpenAI 形态后返回
                val normalized = runCatching {
                    toOpenAiChatResponse(protocol, ChatJson.json.parseToJsonElement(respBody).jsonObject)
                }.getOrNull()
                val usage = normalized?.let { extractUsage(it) }
                if (normalized == null) recordAbnormal(route.provider, modelName, null, "响应归一化失败")
                logRequest(
                    device, aggregateId, via, stream, startedAt, null, normalized != null,
                    if (normalized == null) "响应归一化失败" else null,
                    usage?.first, usage?.second,
                )
                respondText(normalized?.toString() ?: respBody, ContentType.Application.Json)
            }
        }
    }

    /**
     * flux-auto 聚合模型：候选为全部启用模型（厂商顺序×模型顺序）的合集，
     * 按请求模态剔除发过去必然失败的候选后逐个回退。
     * 流式候选限时建立连接，成功后原样透传 SSE；非流式候选请求完整 JSON 后转发。
     * 响应模式按请求设备区分（device.responseMode），构造 [SmartPolicy]；
     * 每次尝试结果打点 [SmartHealth]，与 App 内聊天共享健康状态；
     * 整次请求（含校验失败与全部候选失败）写一条 [RequestLogEntity]。
     */
    private suspend fun ApplicationCall.handleSmartCompletion(
        rawBody: JsonObject,
        device: AuthDeviceEntity,
        mode: String,
        startedAt: Long,
    ) {
        val stream = streamFlagOf(rawBody)
        val aggregateId = SmartAi.MODEL_NAME
        if (!settings.lanSmartAiEnabled.value) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "${SmartAi.MODEL_NAME} 未开放")
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "${SmartAi.MODEL_NAME} 未开放：请在 Flux App 的「局域网」页面开启 ${SmartAi.MODEL_NAME} 聚合路由",
            )
            return
        }
        val routes = router.allModelsChain().map { (provider, model) -> provider.id to model.ref }
        if (routes.isEmpty()) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "${SmartAi.MODEL_NAME} 没有可用的候选模型")
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "${SmartAi.MODEL_NAME} 没有可聚合的启用模型，请检查厂商与模型是否已启用",
            )
            return
        }
        val messages = rawBody["messages"] as? JsonArray
        if (messages == null) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "messages 字段缺失")
            respondOpenAiError(HttpStatusCode.BadRequest, "messages 字段必填")
            return
        }
        val policy = SmartPolicy(
            mode = mode,
            fastMs = settings.smartFastFirstTokenMs.value.toLong(),
            chatMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L,
            leisureMs = settings.smartLeisureFirstTokenSeconds.value * 1000L,
            cooldownThreshold = settings.smartCooldownThreshold.value,
            cooldownMs = settings.smartCooldownSeconds.value * 1000L,
        )
        val maxRounds = settings.smartRouteMaxRounds.value.coerceAtLeast(1)

        // 先解析并校验全部候选（自动选择：按请求模态剔除能力不匹配的），再按健康桶 + 特征匹配稳定排序：
        // 冷却/降权候选垫底兜底，桶内带图/视频输入优先多模态候选、带 tools 优先支持工具的
        val features = featuresOfRequestBody(rawBody, messages)
        val now = System.currentTimeMillis()
        val resolved = routes.mapNotNull { (providerId, modelName) ->
            val r = router.resolveById(providerId, modelName) ?: return@mapNotNull null
            val apiKey = r.apiKey?.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 指向本机网关自身的候选直接跳过，避免自连回环
            if (isSelfGatewayUrl(r.provider.baseUrl, port)) return@mapNotNull null
            // 模型配置行可能已被删除，此时退回空配置继续路由
            val model = r.model ?: ModelEntity(providerId = r.provider.id, name = modelName)
            SmartPrepared(r.provider, apiKey, model, r.advancedParams)
        }
        // 用户手动熔断的厂商：候选整体跳过（人工决策覆盖自动健康状态），先过滤再谈「没有可用候选」
        val usable = resolved.filter { !it.provider.circuitOpen }
        if (usable.isEmpty() && resolved.isNotEmpty()) {
            val message = "全部候选所在厂商均处于手动熔断状态，请在 Flux App 的「模型异常记录」页点「可重试」解除"
            logRequest(device, aggregateId, null, stream, startedAt, null, false, message)
            respondOpenAiError(HttpStatusCode.BadGateway, "${SmartAi.MODEL_NAME} $message")
            return
        }
        val eligible = eligibleCandidates(usable, features, modelOf = { it.model })
        if (eligible.isEmpty()) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "无候选支持请求模态")
            respondOpenAiError(
                HttpStatusCode.BadGateway,
                "${SmartAi.MODEL_NAME} 没有支持${features.modalityLabel()}的可用模型" +
                    "（厂商停用/缺 Key，或启用模型均不具备该能力）",
            )
            return
        }
        val prepared = orderCandidates(
            eligible,
            features,
            modelOf = { it.model },
            bucketOf = { SmartHealth.bucket(it.provider.id, it.model.ref, now) },
        )

        executeSmartCandidates(
            aggregateId = aggregateId,
            rawBody = rawBody,
            messages = messages,
            prepared = prepared,
            device = device,
            policy = policy,
            maxRounds = maxRounds,
            startedAt = startedAt,
        )
    }

    /**
     * 路由分组（flux-<groupKey>）：组内成员按顺序回退。
     * 与 flux-auto 的差别：候选只在组内，且同样按请求模态剔除能力不匹配的成员，
     * 保证「组内参数基本一致」；其余（健康桶排序、轮巡、透传、日志）走 [executeSmartCandidates]。
     */
    private suspend fun ApplicationCall.handleGroupCompletion(
        group: SmartRouteGroupEntity,
        rawBody: JsonObject,
        device: AuthDeviceEntity,
        mode: String,
        startedAt: Long,
    ) {
        val stream = streamFlagOf(rawBody)
        val aggregateId = group.routeName
        val messages = rawBody["messages"] as? JsonArray
        if (messages == null) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "messages 字段缺失")
            respondOpenAiError(HttpStatusCode.BadRequest, "messages 字段必填")
            return
        }
        val members = smartRouteMemberDao.forGroup(group.id).filter { it.enabled }
        if (members.isEmpty()) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "分组没有启用的成员")
            respondOpenAiError(HttpStatusCode.NotFound, "分组 '$aggregateId' 没有启用的成员，请先在「智能路由」中配置")
            return
        }
        val features = featuresOfRequestBody(rawBody, messages)
        val now = System.currentTimeMillis()
        val resolved = members.mapNotNull { member ->
            val r = router.resolveById(member.providerId, member.modelName) ?: return@mapNotNull null
            val apiKey = r.apiKey?.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 指向本机网关自身的成员直接跳过，避免自连回环
            if (isSelfGatewayUrl(r.provider.baseUrl, port)) return@mapNotNull null
            val model = r.model ?: ModelEntity(providerId = r.provider.id, name = member.modelName)
            SmartPrepared(r.provider, apiKey, model, r.advancedParams)
        }
        // 自动选择：模态不匹配的成员不参与本次路由（组内参数一致的承诺）
        // 用户手动熔断的厂商：成员整体跳过（人工决策覆盖自动健康状态）
        val usable = resolved.filter { !it.provider.circuitOpen }
        if (usable.isEmpty() && resolved.isNotEmpty()) {
            val message = "分组 '$aggregateId' 的成员所在厂商均处于手动熔断状态，请在 Flux App 的「模型异常记录」页点「可重试」解除"
            logRequest(device, aggregateId, null, stream, startedAt, null, false, message)
            respondOpenAiError(HttpStatusCode.BadGateway, message)
            return
        }
        val eligible = eligibleCandidates(usable, features, modelOf = { it.model })
        if (eligible.isEmpty()) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "分组内无支持当前模态的可用成员")
            respondOpenAiError(
                HttpStatusCode.BadGateway,
                "分组 '$aggregateId' 内没有支持${features.modalityLabel()}的可用成员" +
                    "（厂商停用/缺 Key，或成员均不具备该能力）",
            )
            return
        }
        val prepared = orderCandidates(
            eligible,
            features,
            modelOf = { it.model },
            bucketOf = { SmartHealth.bucket(it.provider.id, it.model.ref, now) },
        )
        val policy = SmartPolicy(
            mode = mode,
            fastMs = settings.smartFastFirstTokenMs.value.toLong(),
            chatMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L,
            leisureMs = settings.smartLeisureFirstTokenSeconds.value * 1000L,
            cooldownThreshold = settings.smartCooldownThreshold.value,
            cooldownMs = settings.smartCooldownSeconds.value * 1000L,
        )
        val maxRounds = settings.smartRouteMaxRounds.value.coerceAtLeast(1)
        executeSmartCandidates(aggregateId, rawBody, messages, prepared, device, policy, maxRounds, startedAt)
    }

    /**
     * 智能路由候选执行核心（smartAi 与路由分组共用）：轮巡 [maxRounds] 轮逐个尝试候选，
     * 非流式成功即转发 JSON，流式建连后透传/回译 SSE；每次尝试打点 [SmartHealth] 并落模型异常记录。
     *
     * 失败按 HTTP 状态码分类决定去留（见 [FailureKind]）：请求参数错误立即终态，
     * 本轮全部鉴权失败也提前终态，其余换候选继续；用户标记为不可重试的厂商只给一次机会。
     */
    private suspend fun ApplicationCall.executeSmartCandidates(
        aggregateId: String,
        rawBody: JsonObject,
        messages: JsonArray,
        prepared: List<SmartPrepared>,
        device: AuthDeviceEntity,
        policy: SmartPolicy,
        maxRounds: Int,
        startedAt: Long,
    ) {
        val stream = streamFlagOf(rawBody)
        // 轮巡 N 轮：每轮按顺序尝试全部候选，全部失败才进入下一轮；
        // 每个候选保留最后一次失败原因，轮巡结束汇总返回，明确「全部候选不可用」
        val lastErrors = LinkedHashMap<String, String>()
        // 用户标记为不可重试的厂商：首字前失败过一次就不再进入后续轮次（等效「不参与重试链」）
        val retired = HashSet<String>()
        for (round in 1..maxRounds) {
            // 健康桶整轮取同一时刻，避免轮内冷却状态漂移
            val roundNow = System.currentTimeMillis()
            val pool = prepared.filter { "${it.provider.id}:${it.model.ref}" !in retired }
            // 极速模式首轮只发健康且速度未知/实测达标的候选（限制学费上界）；一轮无成功后放宽为完整链
            val roundCandidates = if (policy.mode == SmartModes.FAST && round == 1) {
                pickFastRound(
                    pool,
                    SMART_FAST_ROUND_LIMIT,
                    bucketOf = { SmartHealth.bucket(it.provider.id, it.model.ref, roundNow) },
                    fastCapableOf = { SmartHealth.fastCapable(it.provider.id, it.model.ref, policy.fastMs) },
                )
            } else {
                pool
            }
            if (roundCandidates.isEmpty()) continue

            // 本轮各候选的失败分类：本轮全部鉴权失败时提前终态（换谁都没用，别再轮巡）
            val roundKinds = mutableListOf<FailureKind>()

            /**
             * 一次候选失败：落一条模型异常记录 + 按分类打点健康度。
             * 参数错误 / 鉴权失败 / 模型不存在不进冷却（见 [FailureKind.countsTowardCooldown]）；
             * 极速轮的「等首字超时」只记慢（不累积失败，放宽轮成功会带回真实测速）。
             */
            suspend fun reportFailure(
                candidate: SmartPrepared,
                kind: FailureKind,
                message: String,
                httpCode: Int? = null,
                retryAfterMs: Long? = null,
            ) {
                lastErrors[candidate.label] = message
                roundKinds += kind
                if (!candidate.provider.retryable) {
                    retired += "${candidate.provider.id}:${candidate.model.ref}"
                }
                recordAbnormal(candidate.provider, candidate.model.ref, httpCode, message)
                when {
                    kind == FailureKind.TIMEOUT && policy.mode == SmartModes.FAST && round == 1 ->
                        SmartHealth.recordSlow(candidate.provider.id, candidate.model.ref, policy.fastMs)
                    kind == FailureKind.RATE_LIMIT ->
                        SmartHealth.recordCooldown(
                            candidate.provider.id, candidate.model.ref, roundNow,
                            retryAfterMs ?: policy.cooldownMs,
                        )
                    else -> SmartHealth.recordFailure(
                        candidate.provider.id, candidate.model.ref, roundNow,
                        policy.cooldownThreshold, policy.cooldownMs, kind,
                    )
                }
            }

            for (candidate in roundCandidates) {
                val timeoutMs = policy.timeoutMs(round)
                val timeoutLabel = "${timeoutMs / 1000}s"
                val startedAt = System.currentTimeMillis()
                var payload = buildChatBody(
                    modelName = candidate.model.name,
                    messages = messages,
                    stream = stream,
                    defaults = candidate.advancedParams,
                    base = rawBody,
                )
                payload = mergeCustomBody(payload, candidate.model.customBody)
                if (candidate.provider.presetId == "gemini" || candidate.provider.protocol == ProviderProtocol.GEMINI) {
                    payload = applyBuiltinTools(
                        payload,
                        candidate.model.builtinGoogleSearch,
                        candidate.model.builtinUrlContext,
                        candidate.model.builtinImageGen,
                    )
                }
                val extraHeaders = parseCustomHeaders(candidate.model.customHeaders)

                if (!stream) {
                    // 非流式候选：带读超时的完整请求，成功即转发（原生协议由 chatOnce 归一化为 OpenAI 形态）
                    val result = client.chatOnce(
                        candidate.provider.baseUrl, candidate.apiKey, payload, extraHeaders,
                        candidate.provider.protocol,
                    )
                    val resp = result.getOrNull()
                    if (resp == null) {
                        val e = result.exceptionOrNull()
                        val http = e as? HttpApiException
                        val kind = classifyFailure(http?.httpCode)
                        val message = e?.message ?: "请求失败"
                        reportFailure(candidate, kind, message, http?.httpCode, http?.retryAfterMs)
                        if (kind.terminal) {
                            logRequest(device, aggregateId, candidate.label, stream, startedAt, null, false, message)
                            respondOpenAiError(
                                HttpStatusCode.BadRequest,
                                "$aggregateId 请求参数有误（HTTP ${http?.httpCode}）：$message（未再尝试其他候选）",
                            )
                            return
                        }
                        continue
                    }
                    SmartHealth.recordSuccess(
                        candidate.provider.id, candidate.model.ref, System.currentTimeMillis() - startedAt,
                    )
                    val usage = extractUsage(resp)
                    logRequest(
                        device, aggregateId, candidate.label, stream, startedAt, null, true, null,
                        usage?.first, usage?.second,
                    )
                    respondText(resp.toString(), ContentType.Application.Json)
                    return
                }

                // 流式候选：限时建立连接，超时或连接失败（拒绝/DNS/坏地址）均取消并尝试下一个
                val upstream = try {
                    val call = client.newStreamingCall(
                        candidate.provider.baseUrl, candidate.apiKey, payload, extraHeaders,
                        candidate.provider.protocol,
                    )
                    withTimeoutOrNull(timeoutMs) {
                        withContext(Dispatchers.IO) { call.execute() }
                    }.also { if (it == null) call.cancel() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(candidate, FailureKind.NETWORK, "连接失败（${e.message ?: e.javaClass.simpleName}）")
                    continue
                }
                if (upstream == null) {
                    reportFailure(candidate, FailureKind.TIMEOUT, "$timeoutLabel 内未建立连接")
                    continue
                }
                if (!upstream.isSuccessful) {
                    val code = upstream.code
                    val retryAfterMs = parseRetryAfterMs(upstream.header("Retry-After"))
                    val errBody = client.readBody(upstream)
                    upstream.close()
                    val kind = classifyFailure(code)
                    val message = buildString {
                        append("上游 HTTP $code")
                        val detail = errBody.take(200)
                        if (detail.isNotBlank()) append(": $detail")
                    }
                    reportFailure(candidate, kind, message, code, retryAfterMs)
                    if (kind.terminal) {
                        logRequest(device, aggregateId, candidate.label, stream, startedAt, null, false, message)
                        respondOpenAiError(
                            HttpStatusCode.BadRequest,
                            "$aggregateId 请求参数有误（HTTP $code）：$message（未再尝试其他候选）",
                        )
                        return
                    }
                    continue
                }

                // 建连成功 ≠ 有输出：限时等首个 data 帧，超时同样切换下一个
                val payloads = Channel<String>(Channel.UNLIMITED)
                // ApplicationCall 不是 CoroutineScope，用请求自身的 coroutineContext 挂载生产者，
                // 请求取消时随之取消
                val producer = CoroutineScope(coroutineContext).launch(Dispatchers.IO) {
                    try {
                        if (candidate.provider.protocol == ProviderProtocol.OPENAI) {
                            client.ssePayloads(upstream).collect { payloads.send(it) }
                        } else {
                            // 原生协议：解析归一化后回译为 OpenAI chunk 帧
                            client.chatEvents(upstream, candidate.provider.protocol).collect { ev ->
                                chatEventToChunkLine(ev, candidate.model.ref)?.let { payloads.send(it) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 首帧前失败按无输出处理；首帧后失败让透传侧感知中断
                        payloads.close(e)
                    } finally {
                        payloads.close()
                    }
                }
                val firstPayload = try {
                    withTimeoutOrNull(timeoutMs) { payloads.receiveCatching().getOrNull() }
                } catch (e: CancellationException) {
                    producer.cancel()
                    upstream.close()
                    throw e
                }
                if (firstPayload == null) {
                    producer.cancel()
                    upstream.close()
                    reportFailure(candidate, FailureKind.TIMEOUT, "$timeoutLabel 内无输出")
                    continue
                }
                SmartHealth.recordSuccess(
                    candidate.provider.id, candidate.model.ref, System.currentTimeMillis() - startedAt,
                )
                val ttft = System.currentTimeMillis() - startedAt
                var promptTok: Long? = null
                var completionTok: Long? = null
                usageOfPayload(firstPayload)?.let { (p, c) ->
                    if (p != null) promptTok = p
                    if (c != null) completionTok = c
                }
                var streamError: String? = null
                try {
                    respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                        writeStringUtf8("data: $firstPayload\n\n")
                        flush()
                        for (payloadLine in payloads) {
                            usageOfPayload(payloadLine)?.let { (p, c) ->
                                if (p != null) promptTok = p
                                if (c != null) completionTok = c
                            }
                            writeStringUtf8("data: $payloadLine\n\n")
                            flush()
                        }
                        writeStringUtf8("data: [DONE]\n\n")
                        flush()
                    }
                } catch (e: CancellationException) {
                    // 客户端提前断开：数据已正常流动，按成功记录
                    streamError = "客户端中断"
                    throw e
                } catch (e: Exception) {
                    // 透传中断：上游不稳信号，累积健康度失败后原样抛给 Ktor 断开客户端
                    streamError = "流式传输中断: ${e.message ?: e.javaClass.simpleName}"
                    reportFailure(candidate, FailureKind.UPSTREAM, streamError)
                    throw e
                } finally {
                    producer.cancel()
                    upstream.close()
                    val err = streamError
                    withContext(NonCancellable) {
                        logRequest(
                            device, aggregateId, candidate.label, true, startedAt, ttft,
                            ok = err == null || err == "客户端中断",
                            error = err,
                            promptTokens = promptTok,
                            completionTokens = completionTok,
                        )
                    }
                }
                return
            }
            // 本轮全部候选都是鉴权失败：换谁都没用，提前终态而不是把 N 轮轮巡跑满
            if (roundKinds.isNotEmpty() && roundKinds.all { it == FailureKind.AUTH }) {
                val summary = lastErrors.entries.joinToString("；") { "${it.key}：${it.value}" }
                val message = "$aggregateId 全部候选均鉴权失败（HTTP 401/403），已提前停止：$summary"
                logRequest(device, aggregateId, null, stream, startedAt, null, false, message)
                respondOpenAiError(HttpStatusCode.Unauthorized, message)
                return
            }
        }
        val summary = lastErrors.entries.joinToString("；") { "${it.key}：${it.value}" }
        logRequest(device, aggregateId, null, stream, startedAt, null, false, "全部候选不可用：$summary")
        respondOpenAiError(
            HttpStatusCode.BadGateway,
            "$aggregateId 全部 ${prepared.size} 个候选均不可用（已尝试 $maxRounds 轮）：$summary",
        )
    }

    /**
     * 归一化流事件 → OpenAI chat.completion.chunk 帧文本（原生协议上游回译给 OpenAI 客户端）。
     * Delta/ReasoningDelta → delta 帧；Failed → error 帧；Done → null（由调用方收尾 [DONE]）。
     * Usage 不回译为空 choices 帧：避免下游客户端未请求 stream_options 时对空 choices 数组措手不及。
     */
    private fun chatEventToChunkLine(ev: ChatEvent, model: String): String? {
        fun chunk(delta: JsonObject) = buildJsonObject {
            put("id", "chatcmpl-flux")
            put("object", "chat.completion.chunk")
            put("model", model)
            put(
                "choices",
                buildJsonArray {
                    add(buildJsonObject { put("index", 0); put("delta", delta) })
                },
            )
        }.toString()
        return when (ev) {
            is ChatEvent.Delta -> chunk(buildJsonObject { put("content", ev.text) })
            is ChatEvent.ReasoningDelta -> chunk(buildJsonObject { put("reasoning_content", ev.text) })
            is ChatEvent.Failed -> buildJsonObject {
                put("error", buildJsonObject { put("message", ev.message) })
            }.toString()
            // 客户端自带 tools 时上游的 tool_calls 原样透传（网关不代执行），聚合结果按完整调用一次性下发
            is ChatEvent.ToolCalls -> chunk(
                buildJsonObject {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            ev.calls.forEachIndexed { i, call ->
                                add(
                                    buildJsonObject {
                                        put("index", i)
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            },
                                        )
                                    }
                                )
                            }
                        },
                    )
                }
            )
            is ChatEvent.Usage -> null
            ChatEvent.Done -> null
        }
    }

    private suspend fun ApplicationCall.respondOpenAiError(status: HttpStatusCode, message: String) {
        val body = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("message", message)
                    put("type", "phone_router_error")
                    put("code", status.value)
                }
            )
        }.toString()
        respondText(body, ContentType.Application.Json, status)
    }

    // ---- 视频生成（Sora 兼容形态）：网关统一对外，内部按模型路由到对应厂商适配器 ----

    private fun lanJobIdOf(idParam: String): Long? =
        idParam.removePrefix("flux-").takeIf { it != idParam }?.toLongOrNull()

    /** 创建视频任务：model=「厂商别名/模型名」（裸模型名需唯一），其余字段透传给上游适配器 */
    private suspend fun ApplicationCall.handleVideoCreate() {
        val body: JsonObject = try {
            ChatJson.json.parseToJsonElement(receiveText()).jsonObject
        } catch (e: Exception) {
            respondOpenAiError(HttpStatusCode.BadRequest, "请求体不是合法 JSON: ${e.message}")
            return
        }
        val modelId = body.stringAt("model")
        val prompt = body.stringAt("prompt")
        if (modelId.isNullOrBlank() || prompt.isNullOrBlank()) {
            respondOpenAiError(HttpStatusCode.BadRequest, "model 与 prompt 必填（model 格式：厂商别名/模型名）")
            return
        }
        val providers = providerDao.all().filter { it.enabled }.associateBy { it.id }
        val target: Pair<ProviderEntity, ModelEntity>? = if ('/' in modelId) {
            val alias = modelId.substringBefore('/').trim()
            val name = modelId.substringAfter('/').trim()
            val p = providerDao.byAlias(alias)?.takeIf { it.enabled }
            val m = p?.let { modelDao.find(it.id, name) }
            if (p != null && m != null) p to m else null
        } else {
            val matches = modelDao.observeEnabled().first()
                .filter { it.ref == modelId && it.outputVideo }
                .mapNotNull { m -> providers[m.providerId]?.let { it to m } }
            if (matches.size > 1) {
                respondOpenAiError(
                    HttpStatusCode.BadRequest,
                    "模型名「$modelId」在多个厂商存在，请用「厂商别名/模型名」格式（候选：" +
                        matches.joinToString("、") { "${it.first.alias}/${it.second.ref}" } + "）",
                )
                return
            }
            matches.firstOrNull()
        }
        val (provider, model) = target ?: run {
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "视频模型「$modelId」不存在：须为已启用厂商下标记了视频输出（outputVideo）的模型",
            )
            return
        }
        if (!model.outputVideo) {
            respondOpenAiError(HttpStatusCode.BadRequest, "「${provider.alias}/${model.ref}」未标记视频输出能力，不能用于视频生成")
            return
        }
        val key = router.keyFor(model, provider)
        if (key == null || key.key.isBlank()) {
            respondOpenAiError(HttpStatusCode.BadRequest, "厂商「${provider.alias}」尚未配置 API Key")
            return
        }
        val extras = JsonObject(body.filterKeys { it != "model" && it != "prompt" })
        val jobId = videoJobs.submit(
            conversationId = VIDEO_JOB_NO_CONVERSATION,
            provider = provider,
            apiKey = key.key,
            model = model,
            prompt = prompt,
            extraBody = extras.takeIf { it.isNotEmpty() },
        )
        jobId.fold(
            onSuccess = { id ->
                respondText(
                    buildJsonObject {
                        put("id", "flux-$id")
                        put("object", "video")
                        put("status", "queued")
                        put("model", modelId)
                        put("created_at", System.currentTimeMillis() / 1000)
                    }.toString(),
                    ContentType.Application.Json,
                )
            },
            onFailure = { e ->
                respondOpenAiError(HttpStatusCode.BadGateway, "视频任务提交失败：${e.message ?: e.javaClass.simpleName}")
            },
        )
    }

    /** 轮询任务状态（映射为 Sora 状态词表） */
    private suspend fun ApplicationCall.respondVideoStatus(idParam: String) {
        val jobId = lanJobIdOf(idParam) ?: run {
            respondOpenAiError(HttpStatusCode.NotFound, "视频任务不存在（id 格式：flux-<数字>）")
            return
        }
        val job = videoJobDao.byId(jobId) ?: run {
            respondOpenAiError(HttpStatusCode.NotFound, "视频任务不存在")
            return
        }
        val status = when (job.status) {
            VideoJobEntity.STATUS_PENDING -> "queued"
            VideoJobEntity.STATUS_RUNNING -> "in_progress"
            VideoJobEntity.STATUS_DONE -> "completed"
            else -> "failed"
        }
        respondText(
            buildJsonObject {
                put("id", "flux-${job.id}")
                put("object", "video")
                put("status", status)
                put("model", job.modelName)
                put("created_at", job.createdAt / 1000)
                job.error?.let { put("error", it) }
            }.toString(),
            ContentType.Application.Json,
        )
    }

    /** 下载成品视频（完成后流式回本地 mp4 文件） */
    private suspend fun ApplicationCall.respondVideoContent(idParam: String) {
        val jobId = lanJobIdOf(idParam) ?: run {
            respondOpenAiError(HttpStatusCode.NotFound, "视频任务不存在（id 格式：flux-<数字>）")
            return
        }
        val job = videoJobDao.byId(jobId) ?: run {
            respondOpenAiError(HttpStatusCode.NotFound, "视频任务不存在")
            return
        }
        val path = job.videoPath
        if (job.status != VideoJobEntity.STATUS_DONE || path == null) {
            respondOpenAiError(HttpStatusCode.NotFound, "任务尚未完成或无可用视频（当前状态：${job.status}）")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            respondOpenAiError(HttpStatusCode.NotFound, "视频文件已被清理")
            return
        }
        respondBytes(file.readBytes(), ContentType("video", "mp4"))
    }
}

/** 已通过校验的 smartAi 候选（解析成功、有 Key、非自连），供排序后逐个尝试 */
private data class SmartPrepared(
    val provider: ProviderEntity,
    val apiKey: String,
    val model: ModelEntity,
    val advancedParams: AdvancedParams?,
) {
    val label: String
        get() = "${provider.alias}/${model.alias?.takeIf { it.isNotBlank() } ?: model.ref}"
}
