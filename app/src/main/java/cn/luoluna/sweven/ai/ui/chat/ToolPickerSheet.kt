package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.tools.ToolRegistry

/**
 * 会话工具选择半窗：勾选本次会话允许模型调用的工具（即时生效并随会话持久化）。
 * 右上角「管理」跳转工具中心（全局默认开关）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tools = ToolRegistry.all()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // ModalBottomSheet 内部为无界高度约束，按屏高显式计算半窗高度（扣除拖拽把手）
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f - 57.dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选择工具", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅本次会话生效；模型支持工具调用时才会实际调用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onManage) { Text("管理") }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            if (tools.isEmpty()) {
                Text(
                    "暂无已注册工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(tools, key = { it.name }) { tool ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(tool.name, tool.name !in selected) }
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = tool.name in selected,
                                onCheckedChange = { onToggle(tool.name, it) },
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(tool.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    tool.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    item(key = "bottom-space") {
                        Column(Modifier.height(16.dp)) {}
                    }
                }
            }
        }
    }
}
