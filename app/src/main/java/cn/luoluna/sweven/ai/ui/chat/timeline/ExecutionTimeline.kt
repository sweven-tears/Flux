package cn.luoluna.sweven.ai.ui.chat.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.ui.chat.MarkdownText
import cn.luoluna.sweven.ai.ui.chat.ReasoningSection
import cn.luoluna.sweven.ai.ui.chat.style.BubbleStyle
import cn.luoluna.sweven.ai.ui.chat.style.ChatBubble
import cn.luoluna.sweven.ai.ui.chat.style.ChatStyleConfig
import cn.luoluna.sweven.ai.ui.common.MonoFont

/**
 * AI 执行轨迹视图（一个回复轮次内的链式调用）：
 * 摘要行（点击开合）→ 可见节点 → 最终答复。
 *
 * 节点级视觉差异全部走 [ChatStyleConfig]（ToolCall 用 `config.tool`、答复用 `config.ai`），
 * 本组件不硬编码颜色与圆角。展开状态由调用方持有（[expanded] / [onToggleExpanded]），
 * 便于放进 `rememberSaveable`（见 ChatUiDefaults.ToolTurnItem）。
 */
@Composable
fun ExecutionTimeline(
    nodes: List<ExecutionNode>,
    turnRunning: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    config: ChatStyleConfig,
    onShowDetail: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = resolveTimelineView(nodes, expanded, turnRunning)
    Column(modifier = modifier.fillMaxWidth()) {
        TimelineHeadline(
            text = view.headline,
            expanded = expanded,
            hiddenCount = view.hiddenCount,
            onToggle = onToggleExpanded,
        )
        view.visibleNodes.forEach { node ->
            when (node) {
                is ExecutionNode.Thinking -> ThinkingNodeRow(node)
                is ExecutionNode.ToolCall -> ToolCallNodeRow(node, config.tool)
                is ExecutionNode.ToolResult -> ToolResultNodeRow(node)
                is ExecutionNode.Answer -> AnswerNodeBlock(node, config, onShowDetail)
            }
        }
    }
}

/** 摘要行：一行文案 + 箭头；有折叠时显示「已折叠 N 步」 */
@Composable
private fun TimelineHeadline(
    text: String,
    expanded: Boolean,
    hiddenCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hiddenCount > 0) {
            Text(
                "（已折叠 $hiddenCount 步）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起执行过程" else "展开执行过程",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(16.dp),
        )
    }
}

/** 思考节点：灰色小字（沿用 ReasoningSection 的观感） */
@Composable
private fun ThinkingNodeRow(node: ExecutionNode.Thinking) {
    Text(
        node.text.trim(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** 工具调用节点：样式来自 `config.tool`；RUNNING 带转圈，FAILED 带告警徽标 */
@Composable
private fun ToolCallNodeRow(node: ExecutionNode.ToolCall, style: BubbleStyle) {
    Column(Modifier.padding(top = 4.dp)) {
        ChatBubble(style = style) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (node.state) {
                    ToolState.RUNNING -> CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )

                    ToolState.FAILED -> Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "调用失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )

                    else -> Icon(
                        Icons.Outlined.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    node.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (node.arguments.isNotBlank()) {
                Text(
                    node.arguments,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

/** 工具结果节点：等宽 + 3 行截断，点击展开全文（失败时红字） */
@Composable
private fun ToolResultNodeRow(node: ExecutionNode.ToolResult) {
    var expanded by remember(node.nodeId) { mutableStateOf(false) }
    Text(
        node.content,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
        color = if (node.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable { expanded = !expanded },
    )
    Text(
        if (expanded) "收起" else "展开",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** 最终答复节点：永不折叠（reasoning 折叠段 + Markdown 正文） */
@Composable
private fun AnswerNodeBlock(
    node: ExecutionNode.Answer,
    config: ChatStyleConfig,
    onShowDetail: (MessageEntity) -> Unit,
) {
    if (node.text.isBlank() && node.reasoning.isNullOrBlank()) return
    Column(Modifier.padding(top = 4.dp).fillMaxWidth()) {
        if (!node.reasoning.isNullOrBlank()) {
            ReasoningSection(node.reasoning, inProgress = false)
        }
        MarkdownText(
            node.text,
            style = if (config.ai.monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}
