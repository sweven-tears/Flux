package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.RequestLogDao
import cn.luoluna.sweven.ai.data.db.RequestLogEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteDao
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.data.repo.SmartPolicy
import cn.luoluna.sweven.ai.data.repo.aggregateCapability
import cn.luoluna.sweven.ai.data.repo.featuresOfRequestBody
import cn.luoluna.sweven.ai.data.repo.isSelfGatewayUrl
import cn.luoluna.sweven.ai.data.repo.orderCandidates
import cn.luoluna.sweven.ai.data.repo.pickFastRound
import cn.luoluna.sweven.ai.data.repo.SMART_FAST_ROUND_LIMIT
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.extractUsage
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import kotlin.coroutines.coroutineContext

/**
 * 局域网聚合路由服务器（OpenAI 兼容协议）：
 * - GET  /v1/models            聚合模型列表，id 格式：厂商别名/模型名（可选 smartAi）
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
    private val smartRouteDao: SmartRouteDao,
    private val settings: AppSettings,
    private val logDao: RequestLogDao,
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
        }
    }

    private fun infoJson(): String = buildJsonObject {
        put("name", "Flux")
        put("object", "phone_router_info")
        put("model_format", "厂商别名/模型名")
        put("auth", "首次使用需在 Flux App 内批准设备（请求头 X-Device-Id 可选）")
        put(
            "endpoints",
            JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonPrimitive("GET /v1/models"),
                    kotlinx.serialization.json.JsonPrimitive("POST /v1/chat/completions"),
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
        val data = enabled.mapNotNull { m ->
            val p = providers[m.providerId] ?: return@mapNotNull null
            buildJsonObject {
                put("id", "${p.alias}/${m.name}")
                put("object", "model")
                put("owned_by", p.alias)
                put(
                    "phone_router",
                    buildJsonObject {
                        put("display", m.alias?.takeIf { it.isNotBlank() } ?: m.name)
                        put("type", m.type)
                        put("input", modalities(m.inputText, m.inputImage, m.inputVideo))
                        put("output", modalities(m.outputText, m.outputImage, m.outputVideo))
                        put("capabilities", modalities(m.capabilityTools, m.capabilityReasoning, false))
                    }
                )
            }
        }.toMutableList()
        // 开关开启且存在启用候选时，追加 smartAi 聚合模型（候选厂商停用后同样剔除）
        val smartRoutes = if (settings.lanSmartAiEnabled.value) {
            smartRouteDao.all().filter { it.enabled && it.providerId in providers }
        } else emptyList()
        if (smartRoutes.isNotEmpty()) {
            // 聚合能力 = 启用候选的模态/能力并集（模型行已删除的候选按无能力参与并集）
            val agg = aggregateCapability(
                smartRoutes.mapNotNull { modelDao.find(it.providerId, it.modelName) },
            )
            data.add(
                buildJsonObject {
                    put("id", SmartAi.MODEL_NAME)
                    put("object", "model")
                    put("owned_by", SmartAi.MODEL_NAME)
                    put(
                        "phone_router",
                        buildJsonObject {
                            put("display", "smartAi（智能路由聚合）")
                            put("type", "chat")
                            put("input", modalities(agg.inputText, agg.inputImage, agg.inputVideo))
                            put("output", modalities(agg.outputText, agg.outputImage, agg.outputVideo))
                            put("capabilities", modalities(agg.tools, agg.reasoning, false))
                        }
                    )
                }
            )
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
        var payload = buildChatBody(
            modelName = modelName,
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
            logRequest(device, aggregateId, via, stream, startedAt, null, false, "上游连接失败: ${e.message ?: e.javaClass.simpleName}")
            respondOpenAiError(HttpStatusCode.BadGateway, "上游连接失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (!upstream.isSuccessful) {
            val errBody = client.readBody(upstream)
            upstream.close()
            logRequest(
                device, aggregateId, via, stream, startedAt, null, false,
                "上游 HTTP ${upstream.code}: ${errBody.take(200)}",
            )
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
     * smartAi 聚合模型：按启用候选顺序回退。
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
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "smartAi 未开放")
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "smartAi 未开放：请在 Flux App 的「局域网」页面开启 smartAi 聚合模型",
            )
            return
        }
        val routes = smartRouteDao.all().filter { it.enabled }
        if (routes.isEmpty()) {
            logRequest(device, aggregateId, null, stream, startedAt, null, false, "smartAi 没有启用的候选模型")
            respondOpenAiError(HttpStatusCode.NotFound, "smartAi 没有启用的候选模型，请先在「智能路由」中配置")
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

        // 先解析并校验全部候选，再按请求特征 + 健康桶稳定排序：
        // 冷却/降权候选垫底兜底，桶内带图/视频输入优先多模态候选、带 tools 优先支持工具的
        val features = featuresOfRequestBody(rawBody, messages)
        val now = System.currentTimeMillis()
        val prepared = orderCandidates(
            routes.mapNotNull { route ->
                val resolved = router.resolveById(route.providerId, route.modelName) ?: return@mapNotNull null
                val apiKey = resolved.apiKey?.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // 指向本机网关自身的候选直接跳过，避免自连回环
                if (isSelfGatewayUrl(resolved.provider.baseUrl, port)) return@mapNotNull null
                // 模型配置行可能已被删除，此时退回空配置继续路由
                val model = resolved.model
                    ?: ModelEntity(providerId = resolved.provider.id, name = route.modelName)
                SmartPrepared(resolved.provider, apiKey, model, resolved.advancedParams)
            },
            features,
            modelOf = { it.model },
            bucketOf = { SmartHealth.bucket(it.provider.id, it.model.name, now) },
        )

        // 轮巡 N 轮：每轮按顺序尝试全部候选，全部失败才进入下一轮；
        // 每个候选保留最后一次失败原因，轮巡结束汇总返回，明确「全部候选不可用」
        val lastErrors = LinkedHashMap<String, String>()
        for (round in 1..maxRounds) {
            // 健康桶整轮取同一时刻，避免轮内冷却状态漂移
            val roundNow = System.currentTimeMillis()
            // 极速模式首轮只发健康且速度未知/实测达标的候选（限制学费上界）；一轮无成功后放宽为完整链
            val roundCandidates = if (policy.mode == SmartModes.FAST && round == 1) {
                pickFastRound(
                    prepared,
                    SMART_FAST_ROUND_LIMIT,
                    bucketOf = { SmartHealth.bucket(it.provider.id, it.model.name, roundNow) },
                    fastCapableOf = { SmartHealth.fastCapable(it.provider.id, it.model.name, policy.fastMs) },
                )
            } else {
                prepared
            }
            if (roundCandidates.isEmpty()) continue

            // 极速轮「等首字超时」只记慢（不累积失败，放宽轮成功会带回真实测速）；
            // 其余失败都是病，累积连续失败达到阈值进入冷却
            fun recordTimeoutSlow(candidate: SmartPrepared) {
                if (policy.mode == SmartModes.FAST && round == 1) {
                    SmartHealth.recordSlow(candidate.provider.id, candidate.model.name, policy.fastMs)
                } else {
                    SmartHealth.recordFailure(
                        candidate.provider.id, candidate.model.name, roundNow,
                        policy.cooldownThreshold, policy.cooldownMs,
                    )
                }
            }

            fun recordFailure(candidate: SmartPrepared) {
                SmartHealth.recordFailure(
                    candidate.provider.id, candidate.model.name, roundNow,
                    policy.cooldownThreshold, policy.cooldownMs,
                )
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
                        lastErrors[candidate.label] = result.exceptionOrNull()?.message ?: "请求失败"
                        recordFailure(candidate)
                        continue
                    }
                    SmartHealth.recordSuccess(
                        candidate.provider.id, candidate.model.name, System.currentTimeMillis() - startedAt,
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
                    lastErrors[candidate.label] = "连接失败（${e.message ?: e.javaClass.simpleName}）"
                    recordFailure(candidate)
                    continue
                }
                if (upstream == null) {
                    lastErrors[candidate.label] = "$timeoutLabel 内未建立连接"
                    recordFailure(candidate)
                    continue
                }
                if (!upstream.isSuccessful) {
                    client.readBody(upstream)
                    upstream.close()
                    lastErrors[candidate.label] = "上游 HTTP ${upstream.code}"
                    recordFailure(candidate)
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
                                chatEventToChunkLine(ev, candidate.model.name)?.let { payloads.send(it) }
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
                    lastErrors[candidate.label] = "$timeoutLabel 内无输出"
                    recordTimeoutSlow(candidate)
                    continue
                }
                SmartHealth.recordSuccess(
                    candidate.provider.id, candidate.model.name, System.currentTimeMillis() - startedAt,
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
                    recordFailure(candidate)
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
        }
        val summary = lastErrors.entries.joinToString("；") { "${it.key}：${it.value}" }
        logRequest(device, aggregateId, null, stream, startedAt, null, false, "全部候选不可用：$summary")
        respondOpenAiError(
            HttpStatusCode.BadGateway,
            "smartAi 全部 ${prepared.size} 个候选均不可用（已尝试 $maxRounds 轮）：$summary",
        )
    }

    /**
     * 归一化流事件 → OpenAI chat.completion.chunk 帧文本（原生协议上游回译给 OpenAI 客户端）。
     * Delta/ReasoningDelta → delta 帧；Failed → error 帧；Done → null（由调用方收尾 [DONE]）。
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
}

/** 已通过校验的 smartAi 候选（解析成功、有 Key、非自连），供排序后逐个尝试 */
private data class SmartPrepared(
    val provider: ProviderEntity,
    val apiKey: String,
    val model: ModelEntity,
    val advancedParams: AdvancedParams?,
) {
    val label: String
        get() = "${provider.alias}/${model.alias?.takeIf { it.isNotBlank() } ?: model.name}"
}
