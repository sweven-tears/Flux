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

/** 轨迹摘要行内容 */
data class TimelineSummary(
    val headline: String,
    val callCount: Int,
    val stepCount: Int,
    val durationMs: Long?,
    val hasFailure: Boolean,
)

/** 实际渲染内容：摘要行文案 + 可见节点 + 被折叠的节点数 */
data class TimelineView(
    val headline: String,
    val visibleNodes: List<ExecutionNode>,
    val hiddenCount: Int,
    val showAnswer: Boolean,
)

/**
 * 该轮是否正在执行：正在流式输出**且**还没有最终答复。
 * 达到轮数上限/中断的历史轮次（无答复但不在流式中）不算执行中——这是"历史轮次别显示成运行中"的关键。
 */
fun isTurnRunning(turn: ChatItem.ToolTurn, streaming: Boolean): Boolean =
    streaming && turn.answer == null

/**
 * 把"正在执行的工具"合成为 RUNNING 节点（排在最后）。
 * 只在 [running] 且 [liveToolCalls] 非空时合成；id 带序号，避免同名工具 id 重复。
 */
fun withLiveNodes(
    nodes: List<ExecutionNode>,
    liveToolCalls: List<String>,
    running: Boolean,
): List<ExecutionNode> {
    if (!running || liveToolCalls.isEmpty()) return nodes
    return nodes + liveToolCalls.mapIndexed { index, name ->
        ExecutionNode.ToolCall(
            nodeId = "live-$index-$name",
            createdAt = Long.MAX_VALUE,
            name = name,
            arguments = "",
            state = ToolState.RUNNING,
        )
    }
}

/**
 * 摘要文案：
 * - 执行中：`已完成 N 步 · 正在调用 web_search…`
 * - 已结束：`已深度思考，调用 3 个工具，耗时 8.4s`（有失败追加 `· 有失败`）
 * 耗时 = 末节点 createdAt − 首节点 createdAt（历史数据可用；中间轮没有单独耗时字段）。
 */
fun summarizeTimeline(nodes: List<ExecutionNode>, turnRunning: Boolean): TimelineSummary {
    val calls = nodes.filterIsInstance<ExecutionNode.ToolCall>()
    val hasFailure = calls.any { it.state == ToolState.FAILED }
    val duration = if (nodes.size >= 2) {
        (nodes.last().createdAt - nodes.first().createdAt).takeIf { it > 0 }
    } else {
        null
    }
    val headline = if (turnRunning) {
        buildString {
            append("已完成 ")
            append(nodes.size)
            append(" 步")
            calls.lastOrNull()?.let { append(" · 正在调用 ${it.name}…") }
        }
    } else {
        buildString {
            if (nodes.any { it is ExecutionNode.Thinking && it.text.isNotBlank() }) append("已深度思考")
            if (calls.isNotEmpty()) {
                if (isNotEmpty()) append("，")
                append("调用 ${calls.size} 个工具")
            }
            duration?.let {
                if (isNotEmpty()) append("，")
                append("耗时 ")
                append(String.format(java.util.Locale.US, "%.1fs", it / 1000.0))
            }
            if (hasFailure) append(" · 有失败")
            if (isEmpty()) append("执行过程")
        }
    }
    return TimelineSummary(
        headline = headline,
        callCount = calls.size,
        stepCount = nodes.size,
        durationMs = duration,
        hasFailure = hasFailure,
    )
}

/**
 * 折叠决策（与 UI 无关，可单测）：
 * - 展开：全显示；
 * - 执行中且未展开：只显示最新一个节点（其余计入 hiddenCount）；
 * - 已结束且未展开：中间过程全折，只显示最终答复（答复永远展开，不参与折叠）。
 */
fun resolveTimelineView(
    nodes: List<ExecutionNode>,
    expanded: Boolean,
    turnRunning: Boolean,
): TimelineView {
    val summary = summarizeTimeline(nodes, turnRunning)
    if (expanded) {
        return TimelineView(summary.headline, nodes, 0, showAnswer = true)
    }
    if (turnRunning) {
        val latest = nodes.lastOrNull()
        return TimelineView(
            headline = summary.headline,
            visibleNodes = listOfNotNull(latest),
            hiddenCount = (nodes.size - 1).coerceAtLeast(0),
            showAnswer = latest is ExecutionNode.Answer,
        )
    }
    val answers = nodes.filterIsInstance<ExecutionNode.Answer>()
    return TimelineView(
        headline = summary.headline,
        visibleNodes = answers,
        hiddenCount = (nodes.size - answers.size).coerceAtLeast(0),
        showAnswer = answers.isNotEmpty(),
    )
}
