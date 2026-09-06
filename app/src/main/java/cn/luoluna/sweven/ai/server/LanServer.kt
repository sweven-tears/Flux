package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.SmartRouteDao
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.net.stringAt
import cn.luoluna.sweven.ai.net.streamFlagOf
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
    @Suppress("unused") private val authDao: AuthDeviceDao,
) {

    private var engine: io.ktor.server.engine.ApplicationEngine? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
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
                if (!call.authorize()) return@get
                call.respondText(aggregateModelsJson(), ContentType.Application.Json)
            }
            post("/v1/chat/completions") {
                if (!call.authorize()) return@post
                call.handleChatCompletion()
            }
        }
    }

    private fun infoJson(): String = buildJsonObject {
        put("name", "PhoneRouter")
        put("object", "phone_router_info")
        put("model_format", "厂商别名/模型名")
        put("auth", "首次使用需在 PhoneRouter App 内批准设备（请求头 X-Device-Id 可选）")
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

    private suspend fun ApplicationCall.authorize(): Boolean {
        val ip = request.origin.remoteHost ?: "unknown"
        val deviceId = request.headers[DeviceAuthManager.HEADER_DEVICE_ID]
            ?.takeIf { it.isNotBlank() }
            ?: "ip-$ip"
        return when (val decision = auth.check(deviceId, ip)) {
            AuthDecision.Allowed -> true
            AuthDecision.Blocked -> {
                respondOpenAiError(HttpStatusCode.Forbidden, "该设备已被禁止使用聚合模型")
                false
            }
            is AuthDecision.NeedsApproval -> {
                respondOpenAiError(
                    HttpStatusCode.Unauthorized,
                    "设备未授权：请在 PhoneRouter App 的「局域网」页面批准该设备 (ID: $deviceId)。" +
                        "Device not authorized. Approve it in the PhoneRouter app.",
                )
                false
            }
        }
    }

    private suspend fun aggregateModelsJson(): String {
        val providers = providerDao.all().associateBy { it.id }
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
        // 开关开启且存在启用候选时，追加 smartAi 聚合模型
        val smartRoutes = if (settings.lanSmartAiEnabled.value) smartRouteDao.all().filter { it.enabled } else emptyList()
        if (smartRoutes.isNotEmpty()) {
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
                            put("input", modalities(true, false, false))
                            put("output", modalities(true, false, false))
                            put("capabilities", modalities(false, false, false))
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

    private suspend fun ApplicationCall.handleChatCompletion() {
        val raw = try {
            receiveText()
        } catch (e: Exception) {
            respondOpenAiError(HttpStatusCode.BadRequest, "读取请求体失败: ${e.message}")
            return
        }
        val body: JsonObject = try {
            ChatJson.json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            respondOpenAiError(HttpStatusCode.BadRequest, "请求体不是合法 JSON: ${e.message}")
            return
        }
        val aggregateId = body.stringAt("model")
        if (aggregateId.isNullOrBlank()) {
            respondOpenAiError(HttpStatusCode.BadRequest, "model 字段必填，格式：厂商别名/模型名")
            return
        }
        if (aggregateId == SmartAi.MODEL_NAME) {
            handleSmartCompletion(body)
            return
        }
        val route = router.resolveByAggregateId(aggregateId)
        if (route == null) {
            respondOpenAiError(HttpStatusCode.NotFound, "模型 '$aggregateId' 不存在，聚合模型 id 格式：厂商别名/模型名，可用模型见 GET /v1/models")
            return
        }
        val apiKey = route.apiKey
        if (apiKey == null || apiKey.key.isBlank()) {
            respondOpenAiError(HttpStatusCode.BadRequest, "厂商 '${route.provider.alias}' 尚未配置 API Key")
            return
        }
        val messages = body["messages"] as? JsonArray
        if (messages == null) {
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
        if (route.provider.presetId == "gemini" && route.model != null) {
            payload = applyBuiltinTools(
                payload,
                route.model.builtinGoogleSearch,
                route.model.builtinUrlContext,
                route.model.builtinImageGen,
            )
        }
        val extraHeaders = parseCustomHeaders(route.model?.customHeaders)

        val upstream = try {
            client.openChat(route.provider.baseUrl, apiKey.key, payload, extraHeaders)
        } catch (e: Exception) {
            respondOpenAiError(HttpStatusCode.BadGateway, "上游连接失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        try {
            if (!upstream.isSuccessful) {
                val errBody = client.readBody(upstream)
                respondText(
                    errBody.ifBlank { """{"error":{"message":"upstream HTTP ${upstream.code}"}}""" },
                    ContentType.Application.Json,
                    HttpStatusCode.fromValue(upstream.code),
                )
                return
            }
            if (stream) {
                respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                    client.ssePayloads(upstream).collect { payloadLine ->
                        writeStringUtf8("data: $payloadLine\n\n")
                        flush()
                    }
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                }
            } else {
                val respBody = client.readBody(upstream)
                respondText(respBody, ContentType.Application.Json)
            }
        } finally {
            upstream.close()
        }
    }

    /**
     * smartAi 聚合模型：按启用候选顺序回退。
     * 流式候选限时建立连接，成功后原样透传 SSE；非流式候选请求完整 JSON 后转发。
     */
    private suspend fun ApplicationCall.handleSmartCompletion(rawBody: JsonObject) {
        if (!settings.lanSmartAiEnabled.value) {
            respondOpenAiError(
                HttpStatusCode.NotFound,
                "smartAi 未开放：请在 PhoneRouter App 的「局域网」页面开启 smartAi 聚合模型",
            )
            return
        }
        val routes = smartRouteDao.all().filter { it.enabled }
        if (routes.isEmpty()) {
            respondOpenAiError(HttpStatusCode.NotFound, "smartAi 没有启用的候选模型，请先在「智能路由」中配置")
            return
        }
        val messages = rawBody["messages"] as? JsonArray
        if (messages == null) {
            respondOpenAiError(HttpStatusCode.BadRequest, "messages 字段必填")
            return
        }
        val stream = streamFlagOf(rawBody)
        val timeoutMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L

        for (route in routes) {
            val resolved = router.resolveById(route.providerId, route.modelName) ?: continue
            val apiKey = resolved.apiKey ?: continue
            if (apiKey.key.isBlank()) continue
            // 模型配置行可能已被删除，此时退回空配置继续路由
            val model = resolved.model
                ?: ModelEntity(providerId = resolved.provider.id, name = route.modelName)
            var payload = buildChatBody(
                modelName = model.name,
                messages = messages,
                stream = stream,
                defaults = resolved.advancedParams,
                base = rawBody,
            )
            payload = mergeCustomBody(payload, model.customBody)
            if (resolved.provider.presetId == "gemini") {
                payload = applyBuiltinTools(
                    payload,
                    model.builtinGoogleSearch,
                    model.builtinUrlContext,
                    model.builtinImageGen,
                )
            }
            val extraHeaders = parseCustomHeaders(model.customHeaders)

            if (!stream) {
                // 非流式候选：带读超时的完整请求，成功即转发
                val resp = client.chatOnce(resolved.provider.baseUrl, apiKey.key, payload, extraHeaders)
                    .getOrNull() ?: continue
                respondText(resp.toString(), ContentType.Application.Json)
                return
            }

            // 流式候选：限时建立连接，超时取消并尝试下一个
            val call = client.newStreamingCall(resolved.provider.baseUrl, apiKey.key, payload, extraHeaders)
            val upstream = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { call.execute() }
            }
            if (upstream == null) {
                call.cancel()
                continue
            }
            if (!upstream.isSuccessful) {
                client.readBody(upstream)
                upstream.close()
                continue
            }
            try {
                respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                    client.ssePayloads(upstream).collect { payloadLine ->
                        writeStringUtf8("data: $payloadLine\n\n")
                        flush()
                    }
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                }
            } finally {
                upstream.close()
            }
            return
        }
        respondOpenAiError(HttpStatusCode.BadGateway, "smartAi 全部候选模型请求失败")
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
