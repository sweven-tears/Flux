package cn.luoluna.sweven.ai.ui.chat

import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具调用链的「逻辑轮次」聚合（纯函数，JVM 单测覆盖）。
 *
 * 库里的结构是 OpenAI 协议要求的配对形态：一条 `assistant(带 toolCallsJson)` + 若干 `role=tool`
 * 结果消息，多轮调用就是多组这样的配对——**这个结构不动**（回传上游、续聊、协议合规都依赖它），
 * 只在渲染层把它聚合成本文件定义的「逻辑轮次」，让一次提问内的多轮工具调用
 * 像思考链一样收在一个回复里（折叠展示），而不是散成多个气泡。
 *
 * 规则：
 * 1. 连续的「工具请求助手消息 + 其后的工具结果」归为一个 [ToolStep]，多轮则多个 step；
 * 2. 紧随其后第一条**不带工具调用**的助手消息 = 本轮最终答复（[ChatItem.ToolTurn.answer]）；
 * 3. 没有最终答复（达到用户配置的轮数上限 / 中断 / 报错）时 answer = null，界面仍以折叠卡呈现整条链；
 * 4. 找不到配对助手消息的孤儿工具消息单独成 [ChatItem.Single]（老数据），内容不丢。
 */
data class ToolCallView(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 工具链里的一步：一次助手请求 + 它请求的调用 + 对应的结果消息 */
data class ToolStep(
    val assistant: MessageEntity,
    val calls: List<ToolCallView>,
    val results: List<MessageEntity>,
)

/** 列表渲染单元：普通单条消息，或一个「逻辑轮次」（工具链 + 其后的最终答复） */
sealed interface ChatItem {
    val key: String

    data class Single(val message: MessageEntity) : ChatItem {
        override val key: String get() = "m-${message.id}"
    }

    data class ToolTurn(val steps: List<ToolStep>, val answer: MessageEntity?) : ChatItem {
        override val key: String get() = "t-${steps.first().assistant.id}"
    }
}

/** 助手消息是否在请求工具调用（toolCallsJson 非空即视为工具轮，即使解析失败也不丢内容） */
fun isToolRequest(message: MessageEntity): Boolean =
    message.role == Role.ASSISTANT && !message.toolCallsJson.isNullOrBlank()

/** 解析助手消息里的工具调用列表（容错：解析失败返回空列表，界面回落「未知工具」） */
fun parseToolCalls(raw: String?): List<ToolCallView> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { ChatJson.json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val function = obj["function"] as? JsonObject
        val name = ((function?.get("name") ?: obj["name"]) as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "未知工具"
        val arguments = ((function?.get("arguments") ?: obj["arguments"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        ToolCallView(id = id, name = name, arguments = arguments)
    }
}

/**
 * toolCallId → 工具名 的预计算表。
 * 取代原先「每条工具消息都全表遍历 + 字符串 contains」的 O(n²) 查找。
 */
fun toolCallNamesOf(messages: List<MessageEntity>): Map<String, String> {
    val map = HashMap<String, String>()
    messages.forEach { message ->
        if (isToolRequest(message)) {
            parseToolCalls(message.toolCallsJson).forEach { call ->
                if (call.id.isNotEmpty()) map[call.id] = call.name
            }
        }
    }
    return map
}

fun buildChatTurns(messages: List<MessageEntity>): List<ChatItem> {
    val items = ArrayList<ChatItem>(messages.size)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (isToolRequest(message)) {
            val steps = ArrayList<ToolStep>()
            var cursor = index
            // 连续的工具轮（模型一轮接一轮调用）归入同一逻辑轮次
            while (cursor < messages.size && isToolRequest(messages[cursor])) {
                val calls = parseToolCalls(messages[cursor].toolCallsJson)
                val ids = calls.map { it.id }.filter { it.isNotEmpty() }.toSet()
                var next = cursor + 1
                val results = ArrayList<MessageEntity>()
                while (next < messages.size && messages[next].role == Role.TOOL) {
                    val resultId = messages[next].toolCallId
                    if (resultId != null && ids.isNotEmpty() && resultId !in ids) break
                    results += messages[next]
                    next++
                }
                steps += ToolStep(assistant = messages[cursor], calls = calls, results = results)
                cursor = next
            }
            val answer = messages.getOrNull(cursor)
                ?.takeIf { it.role == Role.ASSISTANT && !isToolRequest(it) }
            items += ChatItem.ToolTurn(steps = steps, answer = answer)
            index = if (answer != null) cursor + 1 else cursor
        } else {
            items += ChatItem.Single(message)
            index++
        }
    }
    return items
}
