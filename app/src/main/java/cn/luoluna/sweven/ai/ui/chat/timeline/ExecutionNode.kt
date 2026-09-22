package cn.luoluna.sweven.ai.ui.chat.timeline

import androidx.compose.runtime.Immutable
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.ui.chat.ChatItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 工具调用在链中的状态（RUNNING 只在实时执行期出现，由 withLiveNodes 合成） */
enum class ToolState { PENDING, RUNNING, SUCCESS, FAILED }

/**
 * AI 执行轨迹里的一个节点：思考 / 工具调用 / 工具结果 / 最终答复。
 *
 * 全部为不可变 data class（可安全进 `remember(key)`）；**展开状态不在这里**，
 * 展开态是 UI 状态（见 ExecutionTimeline 的 `expanded` 参数）。
 */
@Immutable
sealed interface ExecutionNode {
    val nodeId: String
    val createdAt: Long

    data class Thinking(
        override val nodeId: String,
        override val createdAt: Long,
        val text: String,
    ) : ExecutionNode

    data class ToolCall(
        override val nodeId: String,
        override val createdAt: Long,
        val name: String,
        val arguments: String,
        val state: ToolState,
    ) : ExecutionNode

    data class ToolResult(
        override val nodeId: String,
        override val createdAt: Long,
        val name: String,
        val content: String,
        val failed: Boolean,
    ) : ExecutionNode

    data class Answer(
        override val nodeId: String,
        override val createdAt: Long,
        val text: String,
        val reasoning: String?,
        val message: MessageEntity,
    ) : ExecutionNode
}

/**
 * 工具结果是否是失败。
 * 工具层（`ChatViewModel.runTool`）把未知工具/参数非法/超时/异常统一包成 `{"error": ...}`，
 * 所以这里只看结果 JSON 里有没有非空 `error` 字段；解析失败（非 JSON）视为成功，避免误报。
 */
fun toolResultFailed(content: String): Boolean {
    val obj = runCatching { ChatJson.json.parseToJsonElement(content) as? JsonObject }.getOrNull()
        ?: return false
    val err = obj["error"] ?: return false
    return when (err) {
        is JsonPrimitive -> err.contentOrNull?.isNotBlank() == true
        else -> true
    }
}

/**
 * 轮次 → 有序执行节点。
 * 顺序：思考（该轮助手的 reasoning + content）→ 每个调用的「工具调用」节点 → 其配对结果节点 → …
 * → 最后是最终答复节点（有答复才有）。孤儿结果（老数据、配不上 id）也保留成结果节点，内容不丢。
 */
fun buildExecutionNodes(turn: ChatItem.ToolTurn): List<ExecutionNode> {
    val nodes = ArrayList<ExecutionNode>()
    turn.steps.forEach { step ->
        val thinking = listOfNotNull(
            step.assistant.reasoning?.takeIf { it.isNotBlank() },
            step.assistant.content.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        if (thinking.isNotBlank()) {
            nodes += ExecutionNode.Thinking(
                nodeId = "th-${step.assistant.id}",
                createdAt = step.assistant.createdAt,
                text = thinking,
            )
        }
        step.calls.forEach { call ->
            val matched = step.results.filter { call.id.isNotEmpty() && it.toolCallId == call.id }
            val failed = matched.isNotEmpty() && matched.all { toolResultFailed(it.content) }
            nodes += ExecutionNode.ToolCall(
                nodeId = "call-${step.assistant.id}-${call.id.ifEmpty { call.name }}",
                createdAt = step.assistant.createdAt,
                name = call.name,
                arguments = call.arguments,
                state = if (failed) ToolState.FAILED else ToolState.SUCCESS,
            )
            matched.forEach { result ->
                nodes += ExecutionNode.ToolResult(
                    nodeId = "res-${result.id}",
                    createdAt = result.createdAt,
                    name = call.name,
                    content = result.content,
                    failed = toolResultFailed(result.content),
                )
            }
        }
        step.results
            .filter { result -> step.calls.none { it.id.isNotEmpty() && it.id == result.toolCallId } }
            .forEach { orphan ->
                nodes += ExecutionNode.ToolResult(
                    nodeId = "res-${orphan.id}",
                    createdAt = orphan.createdAt,
                    name = "工具",
                    content = orphan.content,
                    failed = toolResultFailed(orphan.content),
                )
            }
    }
    turn.answer?.let { answer ->
        nodes += ExecutionNode.Answer(
            nodeId = "ans-${answer.id}",
            createdAt = answer.createdAt,
            text = answer.content,
            reasoning = answer.reasoning,
            message = answer,
        )
    }
    return nodes
}
