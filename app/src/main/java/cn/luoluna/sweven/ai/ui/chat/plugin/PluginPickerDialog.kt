package cn.luoluna.sweven.ai.ui.chat.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 「界面风格」选择对话框：单选即时生效，选择由宿主持久化到 AppSettings */
@Composable
fun PluginPickerDialog(
    currentId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val plugins = ChatUiRegistry.all()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("界面风格") },
        text = {
            Column {
                plugins.forEach { plugin ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(plugin.id) }
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = currentId == plugin.id,
                            onClick = { onSelect(plugin.id) },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(plugin.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                plugin.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}
