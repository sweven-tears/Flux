package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 一个待尝试的候选请求（已解析好厂商/Key/模型与请求体） */
data class SmartCandidate(
    /** 展示名：厂商别名/模型显示名 */
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val extraHeaders: Map<String, String>,
    val body: JsonObject,
)

/** 智能路由执行事件 */
sealed interface SmartEvent {
    /** 即将尝试第 [index] 个候选 */
    data class Trying(val index: Int, val label: String) : SmartEvent
    data class Delta(val text: String) : SmartEvent
    data class ReasoningDelta(val text: String) : SmartEvent
    data object Done : SmartEvent
    /** 终态失败：全部候选耗尽，或已开始输出后中断（不再回退） */
    data class Failed(val message: String) : SmartEvent
}

/**
 * 智能路由回退链：按候选顺序发起流式请求，
 * 收到首个内容事件之前失败或超过 [firstTokenTimeoutMs] 均切换下一个候选；
 * 一旦开始输出内容，失败即终态（避免向用户重复输出半截内容）。
 */
class SmartRouteExecutor(private val client: ProviderClient) {

    fun stream(
        candidates: List<SmartCandidate>,
        firstTokenTimeoutMs: Long,
    ): Flow<SmartEvent> = channelFlow {
        var lastError = "没有可用的候选模型"
        candidateLoop@ for ((index, candidate) in candidates.withIndex()) {
            send(SmartEvent.Trying(index, candidate.label))

            // 连接 + 等响应头阶段：整体受首字超时约束，超时直接 cancel 底层 Call；
            // 连接被拒/超时/DNS 失败/坏地址等异常同样视为该候选失败，切换下一个
            val call = try {
                client.newStreamingCall(candidate.baseUrl, candidate.apiKey, candidate.body, candidate.extraHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${candidate.label}：连接失败（${e.message ?: e.javaClass.simpleName}）"
                continue@candidateLoop
            }
            val resp = try {
                withTimeoutOrNull(firstTokenTimeoutMs) {
                    withContext(Dispatchers.IO) { call.execute() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.cancel()
                lastError = "${candidate.label}：连接失败（${e.message ?: e.javaClass.simpleName}）"
                continue@candidateLoop
            }
            if (resp == null) {
                call.cancel()
                lastError = "${candidate.label}：${timeoutLabel(firstTokenTimeoutMs)} 内未建立连接"
                continue@candidateLoop
            }

            val events = Channel<ChatEvent>(Channel.UNLIMITED)
            val producer = launch(Dispatchers.IO) {
                try {
                    client.chatEvents(resp).collect { events.send(it) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    events.send(ChatEvent.Failed(e.message ?: e.javaClass.simpleName))
                } finally {
                    events.close()
                }
            }
            var gotContent = false
            var success = false
            try {
                while (true) {
                    val ev: ChatEvent? = if (gotContent) {
                        // 已开始输出：不设超时，等待流自然结束
                        events.receiveCatching().getOrNull()
                    } else {
                        withTimeoutOrNull(firstTokenTimeoutMs) { events.receive() }
                    }
                    when (ev) {
                        null -> {
                            if (gotContent) {
                                success = true
                            } else {
                                lastError = "${candidate.label}：${timeoutLabel(firstTokenTimeoutMs)} 内无输出"
                            }
                            break
                        }
                        is ChatEvent.Delta -> {
                            gotContent = true
                            send(SmartEvent.Delta(ev.text))
                        }
                        is ChatEvent.ReasoningDelta -> {
                            gotContent = true
                            send(SmartEvent.ReasoningDelta(ev.text))
                        }
                        is ChatEvent.Failed -> {
                            lastError = "${candidate.label}：${ev.message}"
                            if (gotContent) {
                                send(SmartEvent.Failed(lastError))
                                return@channelFlow
                            }
                            break
                        }
                        ChatEvent.Done -> {
                            success = true
                            break
                        }
                    }
                }
            } finally {
                producer.cancel()
                if (!success) call.cancel()
            }
            if (success) {
                send(SmartEvent.Done)
                return@channelFlow
            }
        }
        send(SmartEvent.Failed(lastError))
    }

    private fun timeoutLabel(ms: Long) = "${ms / 1000}s"
}

/** baseUrl 是否指向本机 LAN 网关自身（回环地址 + 相同端口）：智能路由候选需跳过，避免自连回环 */
fun isSelfGatewayUrl(baseUrl: String, lanPort: Int): Boolean {
    val url = baseUrl.toHttpUrlOrNull() ?: return false
    if (url.port != lanPort) return false
    val host = url.host.removePrefix("[").removeSuffix("]")
    return host == "localhost" || host == "127.0.0.1" || host == "::1"
}
