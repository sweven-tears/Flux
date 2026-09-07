package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.ui.common.MonoFont

/**
 * 预设对话编辑：名称 + 系统提示词（人设）+ 指定模型（可空 = 使用上次模型）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetDialog(
    existing: PresetEntity?,
    models: List<PickerModel>,
    onDismiss: () -> Unit,
    onSave: (name: String, systemPrompt: String?, providerId: Long?, modelName: String?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var systemPrompt by remember { mutableStateOf(existing?.systemPrompt ?: "") }
    var selected by remember {
        mutableStateOf(
            existing?.providerId?.let { pid ->
                models.firstOrNull { it.provider.id == pid && it.model.name == existing?.modelName }
            }
        )
    }
    var showPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (showPicker) {
        ModelPickerSheet(
            onDismiss = { showPicker = false },
            models = models,
            selectedProviderId = selected?.provider?.id,
            selectedModelName = selected?.model?.name,
            onSelect = { selected = it; showPicker = false },
            onGoProviders = { showPicker = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    error = "请填写预设名称"
                } else {
                    onSave(name, systemPrompt, selected?.provider?.id, selected?.model?.name)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null && existing != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text(if (existing == null) "新建预设对话" else "编辑预设对话") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称 *") },
                    placeholder = { Text("如：周报助手 / 翻译助手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词（人设，可选）") },
                    placeholder = { Text("如：你是一名严谨的技术翻译，只输出译文…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { showPicker = true },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("指定模型（可选）", style = MaterialTheme.typography.labelMedium)
                        Text(
                            selected?.let { it.aggregateId + if (it.enabled) "" else "（已禁用）" }
                                ?: "未指定（使用上次选择的模型）",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFont,
                            color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = { showPicker = true }) { Text("选择") }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "点击抽屉中的预设即可用它开新对话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
