package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.AbnormalVia
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.UsageInfo
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
    /** 厂商 id + 模型名：健康度打点的唯一键 */
    val providerId: Long,
    val modelName: String,
    /** 厂商别名：异常记录落库时的快照（别名改动后历史仍可读） */
    val providerAlias: String,
    val baseUrl: String,
    val apiKey: String,
    val extraHeaders: Map<String, String>,
    val body: JsonObject,
    /** 厂商接口协议（ProviderProtocol 取值）：请求构建与 SSE 解析按协议分发 */
    val protocol: String = ProviderProtocol.OPENAI,
    /** 厂商是否允许重试（用户手动标记）：false 时首字前失败过一次就不再进入后续轮次 */
    val retryable: Boolean = true,
) {
    /** 健康度/异常记录的唯一键 */
    val key: String get() = "$providerId:$modelName"
}

/** 智能路由策略：响应模式 + 各档首字超时 + 冷却参数（App 内与局域网链路共用） */
data class SmartPolicy(
    val mode: String = SmartModes.CHAT,
    val fastMs: Long = 2_000,
    val chatMs: Long = 30_000,
    val leisureMs: Long = 120_000,
    val cooldownThreshold: Int = 2,
    val cooldownMs: Long = 60_000,
) {
    fun timeoutMs(round: Int): Long = firstTokenTimeoutMs(mode, round, fastMs, chatMs, leisureMs)
}

/** 智能路由执行事件 */
sealed interface SmartEvent {
    /** 即将尝试第 [index] 个候选（附 providerId/modelName，消费方据此记录实际生成模型） */
    data class Trying(
        val index: Int,
        val label: String,
        val providerId: Long,
        val modelName: String,
    ) : SmartEvent
    data class Delta(val text: String) : SmartEvent
    data class ReasoningDelta(val text: String) : SmartEvent

    /** 胜出候选的 usage 统计（透传上游，各字段可空，按「最后非空值」合并） */
    data class Usage(val info: UsageInfo) : SmartEvent
    data object Done : SmartEvent

    /** 终态失败：全部候选耗尽，或已开始输出后中断（不再回退）；中断自上游时附带其 HTTP 详情 */
    data class Failed(
        val message: String,
        val httpCode: Int? = null,
        val rawBody: String? = null,
        /** 失败分类：聊天页据此区分「请求有问题」与「上游都挂了」 */
        val kind: FailureKind = FailureKind.EXHAUSTED,
    ) : SmartEvent
}

/**
 * 智能路由回退链：按候选顺序发起流式请求，
 * 收到首个内容事件之前失败或超过当轮首字超时均切换下一个候选；
 * 一旦开始输出内容，失败即终态（避免向用户重复输出半截内容）。
 * 整条链尝试 [maxRounds] 轮仍全部失败才报「全部候选均不可用」，并附各候选最后一次的失败原因。
 *
 * 极速模式：首轮只发健康且实测速度达标的候选（[pickFastRound]）并用极速档超时，
 * 超时只记「慢」不记失败，一轮无成功后放宽为完整链 + 聊天档超时。
 * 每次尝试结果实时打点到 [SmartHealth]，驱动三桶排序与极速轮过滤。
 */
class SmartRouteExecutor(
    private val client: ProviderClient,
    private val recorder: ModelAbnormalRecorder,
) {

    fun stream(
        candidates: List<SmartCandidate>,
        policy: SmartPolicy,
        maxRounds: Int,
    ): Flow<SmartEvent> = channelFlow {
        if (candidates.isEmpty()) {
            send(SmartEvent.Failed("没有可用的候选模型"))
            return@channelFlow
        }
        val rounds = maxRounds.coerceAtLeast(1)
        val fastRound = policy.mode == SmartModes.FAST
        // 每个候选保留最后一次失败原因（跨轮覆盖），终态汇总展示
        val lastErrors = LinkedHashMap<String, String>()
        // 用户标记为不可重试的候选：首字前失败过一次就不再进入后续轮次（等效「不参与重试链」）
        val retired = HashSet<String>()
        for (round in 1..rounds) {
            // 健康桶整轮取同一时刻，避免轮内冷却状态漂移
            val now = System.currentTimeMillis()
            val pool = candidates.filter { it.key !in retired }
            // 极速模式首轮只发健康且速度未知/实测达标的候选；一轮无成功后放宽为完整链
            val roundCandidates = if (fastRound && round == 1) {
                pickFastRound(
                    pool,
                    SMART_FAST_ROUND_LIMIT,
                    bucketOf = { SmartHealth.bucket(it.providerId, it.modelName, now) },
                    fastCapableOf = { SmartHealth.fastCapable(it.providerId, it.modelName, policy.fastMs) },
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
             * 极速轮的「等首字超时」只记慢（上游正在生成，只是没赶上极短窗口）。
             */
            suspend fun reportFailure(
                candidate: SmartCandidate,
                kind: FailureKind,
                message: String,
                httpCode: Int? = null,
                retryAfterMs: Long? = null,
            ) {
                lastErrors[candidate.label] = message
                roundKinds += kind
                if (!candidate.retryable) retired += candidate.key
                recorder.record(
                    candidate.providerId,
                    candidate.providerAlias,
                    candidate.modelName,
                    AbnormalVia.APP,
                    httpCode,
                    message,
                )
                when {
                    kind == FailureKind.TIMEOUT && fastRound && round == 1 ->
                        SmartHealth.recordSlow(candidate.providerId, candidate.modelName, policy.fastMs)
                    kind == FailureKind.RATE_LIMIT ->
                        SmartHealth.recordCooldown(
                            candidate.providerId, candidate.modelName, now, retryAfterMs ?: policy.cooldownMs,
                        )
                    else -> SmartHealth.recordFailure(
                        candidate.providerId, candidate.modelName, now,
                        policy.cooldownThreshold, policy.cooldownMs, kind,
                    )
                }
            }

            candidateLoop@ for ((index, candidate) in roundCandidates.withIndex()) {
                send(SmartEvent.Trying(index, candidate.label, candidate.providerId, candidate.modelName))
                val timeoutMs = policy.timeoutMs(round)
                val startedAt = System.currentTimeMillis()

                // 连接 + 等响应头阶段：整体受首字超时约束，超时直接 cancel 底层 Call；
                // 连接被拒/超时/DNS 失败/坏地址等异常同样视为该候选失败，切换下一个
                val call = try {
                    client.newStreamingCall(
                        candidate.baseUrl, candidate.apiKey, candidate.body, candidate.extraHeaders,
                        candidate.protocol,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportFailure(candidate, FailureKind.NETWORK, "连接失败（${e.message ?: e.javaClass.simpleName}）")
                    continue@candidateLoop
                }
                val resp = try {
                    withTimeoutOrNull(timeoutMs) {
                        withContext(Dispatchers.IO) { call.execute() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    call.cancel()
                    reportFailure(candidate, FailureKind.NETWORK, "连接失败（${e.message ?: e.javaClass.simpleName}）")
                    continue@candidateLoop
                }
                if (resp == null) {
                    call.cancel()
                    reportFailure(candidate, FailureKind.TIMEOUT, "${timeoutLabel(timeoutMs)} 内未建立连接")
                    continue@candidateLoop
                }

                val events = Channel<ChatEvent>(Channel.UNLIMITED)
                val producer = launch(Dispatchers.IO) {
                    try {
                        client.chatEvents(resp, candidate.protocol).collect { events.send(it) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        events.send(ChatEvent.Failed(e.message ?: e.javaClass.simpleName))
                    } finally {
                        events.close()
                    }
                }
                var gotContent = false
                var firstTokenMs = -1L
                var success = false
                try {
                    while (true) {
                        val ev: ChatEvent? = if (gotContent) {
                            // 已开始输出：不设超时，等待流自然结束
                            events.receiveCatching().getOrNull()
                        } else {
                            withTimeoutOrNull(timeoutMs) { events.receive() }
                        }
                        when (ev) {
                            null -> {
                                if (gotContent) {
                                    success = true
                                } else {
                                    reportFailure(candidate, FailureKind.TIMEOUT, "${timeoutLabel(timeoutMs)} 内无输出")
                                }
                                break
                            }
                            is ChatEvent.Delta -> {
                                if (!gotContent) {
                                    gotContent = true
                                    firstTokenMs = System.currentTimeMillis() - startedAt
                                }
                                send(SmartEvent.Delta(ev.text))
                            }
                            is ChatEvent.ReasoningDelta -> {
                                if (!gotContent) {
                                    gotContent = true
                                    firstTokenMs = System.currentTimeMillis() - startedAt
                                }
                                send(SmartEvent.ReasoningDelta(ev.text))
                            }
                            is ChatEvent.Usage -> {
                                // usage 不计入内容事件（Anthropic 首帧即带输入 token），只透传给消费方
                                send(SmartEvent.Usage(ev.info))
                            }
                            is ChatEvent.Failed -> {
                                val kind = classifyFailure(ev.httpCode)
                                if (gotContent) {
                                    // 已开始输出后中断：不再回退，直接终态；中断本身也是上游不稳信号
                                    reportFailure(candidate, FailureKind.UPSTREAM, ev.message, ev.httpCode)
                                    send(
                                        SmartEvent.Failed(
                                            "${candidate.label}：${ev.message}",
                                            ev.httpCode,
                                            ev.rawBody,
                                            FailureKind.UPSTREAM,
                                        )
                                    )
                                    return@channelFlow
                                }
                                reportFailure(candidate, kind, ev.message, ev.httpCode, ev.retryAfterMs)
                                if (kind.terminal) {
                                    // 请求本身有问题：换候选、再轮巡都不会好，立刻把错误交回用户
                                    send(
                                        SmartEvent.Failed(
                                            "${candidate.label} 返回 HTTP ${ev.httpCode}：${ev.message}" +
                                                "（请求本身有问题，未再尝试其他候选）",
                                            ev.httpCode,
                                            ev.rawBody,
                                            kind,
                                        )
                                    )
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
                    if (firstTokenMs >= 0) {
                        SmartHealth.recordSuccess(candidate.providerId, candidate.modelName, firstTokenMs)
                    }
                    send(SmartEvent.Done)
                    return@channelFlow
                }
            }
            // 本轮全部候选都是鉴权失败：换谁都没用，提前终态而不是把 N 轮轮巡跑满
            if (roundKinds.isNotEmpty() && roundKinds.all { it == FailureKind.AUTH }) {
                val summary = lastErrors.entries.joinToString("；") { "${it.key}：${it.value}" }
                send(
                    SmartEvent.Failed(
                        "全部候选均鉴权失败（HTTP 401/403），已提前停止：$summary",
                        kind = FailureKind.AUTH,
                    )
                )
                return@channelFlow
            }
            // 一轮跑完仍未成功，继续下一轮（极速首轮放宽，末轮不做区分，由外层 for 控制）
        }
        val summary = lastErrors.entries.joinToString("；") { "${it.key}：${it.value}" }
        send(SmartEvent.Failed("全部 ${candidates.size} 个候选均不可用（已尝试 $rounds 轮）：$summary"))
    }

    private fun timeoutLabel(ms: Long) = "${ms / 1000}s"
}

/** baseUrl 是否指向本机 LAN 网关自身（回环地址/本机任意接口地址 + 相同端口）：智能路由候选需跳过，避免自连回环 */
fun isSelfGatewayUrl(baseUrl: String, lanPort: Int): Boolean {
    val url = baseUrl.toHttpUrlOrNull() ?: return false
    if (url.port != lanPort) return false
    val host = url.host.removePrefix("[").removeSuffix("]")
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    // mDNS 主机名（flux.local 及自定义前缀 <label>.flux.local）同样指向本机网关
    if (host == "flux.local" || host.endsWith(".flux.local")) return true
    return host in localInterfaceAddresses()
}

/** 本机全部网络接口的数值地址（含 IPv4/IPv6），缓存 60s：接口变化（切 Wi-Fi/热点）后短暂窗口内仍按旧地址判断，可接受 */
private const val LOCAL_ADDR_TTL_MS = 60_000L

private val localAddressesCache = object {
    @Volatile var addresses: Set<String> = emptySet()
    @Volatile var loadedAt: Long = 0L
}

private fun localInterfaceAddresses(): Set<String> {
    val now = System.currentTimeMillis()
    if (now - localAddressesCache.loadedAt < LOCAL_ADDR_TTL_MS) return localAddressesCache.addresses
    val result = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .map { it.hostAddress?.substringBefore('%') ?: "" }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrDefault(emptySet())
    localAddressesCache.addresses = result
    localAddressesCache.loadedAt = now
    return result
}
