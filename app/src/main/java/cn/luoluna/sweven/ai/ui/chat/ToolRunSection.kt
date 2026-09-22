package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import cn.luoluna.sweven.ai.ui.common.MonoFont

/**
 * 工具调用链折叠段（与 [ReasoningSection] 同构）：
 *
 * - 折叠态一行：`⚙ 已调用 2 次工具 · web_search、get_time_info`
 * - 展开态：按轮次列出「该轮说明 → 每个调用的参数 → 结果」（结果默认 3 行截断，点击展开全文，长按看详情）
 * - 一次提问内的多轮工具调用因此只占一个折叠段，与该轮的最终答复同处一个回复里
 */
@Composable
fun ToolRunSection(
    steps: List<ToolStep>,
    onShowDetail: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val names = steps.flatMap { step -> step.calls.map { it.name } }
    val callCount = steps.sumOf { it.calls.size }
    val title = buildString {
        append("已调用 ")
        append(if (callCount > 0) "$callCount 次工具" else "${steps.size} 轮工具")
        if (names.isNotEmpty()) {
            append(" · ")
            append(names.distinct().take(4).joinToString("、"))
            if (names.distinct().size > 4) append(" 等")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Icon(
                Icons.Outlined.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(14.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起工具调用" else "展开工具调用",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp),
            )
        }
        if (expanded) {
            steps.forEachIndexed { stepIndex, step ->
                if (stepIndex > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (step.assistant.content.isNotBlank()) {
                    Text(
                        step.assistant.content.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (step.calls.isEmpty()) {
                    Text(
                        "（这一轮的工具调用记录无法解析）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                step.calls.forEach { call ->
                    Text(
                        call.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (call.arguments.isNotBlank()) {
                        Text(
                            call.arguments,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        )
                    }
                    val matched = step.results.filter { call.id.isNotEmpty() && it.toolCallId == call.id }
                    if (matched.isEmpty()) {
                        Text(
                            "（没有结果记录）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    matched.forEach { result ->
                        ToolResultBlock(result, onShowDetail)
                    }
                }
                // 没配上任何调用的结果（老数据/异常形态）也要能看到
                val unmatched = step.results.filter { result ->
                    step.calls.none { it.id.isNotEmpty() && it.id == result.toolCallId }
                }
                unmatched.forEach { result -> ToolResultBlock(result, onShowDetail) }
            }
            HorizontalDivider(
                Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** 单条工具结果：3 行截断，点击展开全文，长按查看详情（复用宿主的详情弹层） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolResultBlock(message: MessageEntity, onShowDetail: (MessageEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { onShowDetail(message) },
            )
            .padding(top = 4.dp),
    ) {
        Text(
            message.content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 流式期间的「正在调用工具」行：与折叠段头部同构，带转圈 */
@Composable
fun ToolProgressRow(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
