package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.buildChatBody
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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 局域网聚合路由服务器（OpenAI 兼容协议）：
 * - GET  /v1/models            聚合模型列表，id 格式：厂商别名/模型名
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
                        put("input", modalities(m.inputText, m.inputImage, m.inputVideo))
                        put("output", modalities(m.outputText, m.outputImage, m.outputVideo))
                    }
                )
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
        val payload = buildChatBody(
            modelName = modelName,
            messages = messages,
            stream = stream,
            defaults = route.advancedParams,
            base = body,
        )

        val upstream = try {
            client.openChat(route.provider.baseUrl, apiKey.key, payload)
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
