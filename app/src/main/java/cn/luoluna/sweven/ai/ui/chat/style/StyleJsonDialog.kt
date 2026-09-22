package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 自定义样式 JSON 编辑框：保存前先校验（[ChatStyleCodec.decode] 返回非 null 才算合法），
 * 非法时行内红字提示且不允许保存 —— 避免用户存进去一份坏 JSON 后界面静默回落、找不到原因。
 *
 * 只写想改的字段即可（部分覆盖）：未写的字段继承气泡预设里对应角色的样式。
 */
@Composable
fun StyleJsonDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    val template = remember { ChatStyleCodec.encode(ChatStylePreset.FLAT.config()) }
    var text by remember { mutableStateOf(initial.ifBlank { template }) }
    val valid = remember(text) { ChatStyleCodec.decode(text) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("自定义样式 JSON") },
        text = {
            Column {
                Text(
                    "只写想改的字段即可，未写的继承默认；写错枚举名会回落默认值。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    isError = !valid,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(min = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (!valid) {
                    Text(
                        "JSON 格式非法，无法保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = { text = template }) { Text("填入默认模板") }
                TextButton(onClick = onReset) { Text("清除自定义") }
            }
        },
    )
}
