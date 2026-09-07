package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.SmartModes

/**
 * 响应模式三选对话框（极速/聊天/随缘）：
 * 本机聊天在「智能路由」页选择，局域网设备在「局域网」页按设备选择
 */
@Composable
fun ModeSelectDialog(
    title: String,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            Column {
                SmartModes.ALL.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        Column(Modifier.weight(1f)) {
                            Text(SmartModes.labelOf(mode), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                modeDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun modeDescription(mode: String): String = when (mode) {
    SmartModes.FAST -> "首轮只发实测速度达标的候选，一轮无成功再放宽到聊天档"
    SmartModes.LEISURE -> "接受慢响应，长超时耐心等待"
    else -> "按常规首字超时依次回退"
}
