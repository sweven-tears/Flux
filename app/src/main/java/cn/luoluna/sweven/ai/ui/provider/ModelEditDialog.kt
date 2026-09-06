package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.ModelEntity

/**
 * 模型新增/编辑对话框：模型名 + 输入/输出类型（文本/图像/视频）+ 高级参数
 */
@Composable
fun ModelEditDialog(
    providerId: Long,
    existing: ModelEntity?,
    onDismiss: () -> Unit,
    onSave: (ModelEntity) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var inText by remember { mutableStateOf(existing?.inputText ?: true) }
    var inImage by remember { mutableStateOf(existing?.inputImage ?: false) }
    var inVideo by remember { mutableStateOf(existing?.inputVideo ?: false) }
    var outText by remember { mutableStateOf(existing?.outputText ?: true) }
    var outImage by remember { mutableStateOf(existing?.outputImage ?: false) }
    var outVideo by remember { mutableStateOf(existing?.outputVideo ?: false) }

    var temperature by remember { mutableStateOf(existing?.temperature?.toString() ?: "") }
    var topP by remember { mutableStateOf(existing?.topP?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(existing?.maxTokens?.toString() ?: "") }
    var presence by remember { mutableStateOf(existing?.presencePenalty?.toString() ?: "") }
    var frequency by remember { mutableStateOf(existing?.frequencyPenalty?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun parseNum(s: String): Double? = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.trim()
                    if (finalName.isEmpty()) {
                        error = "请填写模型名"
                        return@TextButton
                    }
                    val temp = parseNum(temperature)
                    val top = parseNum(topP)
                    val pres = parseNum(presence)
                    val freq = parseNum(frequency)
                    val maxTok = maxTokens.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                    if (temperature.isNotBlank() && temp == null ||
                        topP.isNotBlank() && top == null ||
                        presence.isNotBlank() && pres == null ||
                        frequency.isNotBlank() && freq == null ||
                        maxTokens.isNotBlank() && maxTok == null
                    ) {
                        error = "高级参数必须是数字（留空表示使用 API 默认值）"
                        return@TextButton
                    }
                    onSave(
                        (existing ?: ModelEntity(providerId = providerId, name = finalName)).copy(
                            providerId = providerId,
                            name = finalName,
                            inputText = inText,
                            inputImage = inImage,
                            inputVideo = inVideo,
                            outputText = outText,
                            outputImage = outImage,
                            outputVideo = outVideo,
                            temperature = temp,
                            topP = top,
                            maxTokens = maxTok,
                            presencePenalty = pres,
                            frequencyPenalty = freq,
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (existing == null) "添加模型" else "编辑模型") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模型名 *") },
                    placeholder = { Text("如 deepseek-chat / gpt-4o") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("输入类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = inText, onClick = { inText = !inText }, label = { Text("文本") })
                    FilterChip(selected = inImage, onClick = { inImage = !inImage }, label = { Text("图像") })
                    FilterChip(selected = inVideo, onClick = { inVideo = !inVideo }, label = { Text("视频") })
                }
                Text("输出类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = outText, onClick = { outText = !outText }, label = { Text("文本") })
                    FilterChip(selected = outImage, onClick = { outImage = !outImage }, label = { Text("图像") })
                    FilterChip(selected = outVideo, onClick = { outVideo = !outVideo }, label = { Text("视频") })
                }
                Text("高级参数（留空 = 使用 API 默认值）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = temperature, onValueChange = { temperature = it },
                        label = { Text("temperature") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = topP, onValueChange = { topP = it },
                        label = { Text("top_p") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxTokens, onValueChange = { maxTokens = it },
                        label = { Text("max_tokens") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = presence, onValueChange = { presence = it },
                        label = { Text("presence_penalty") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                OutlinedTextField(
                    value = frequency, onValueChange = { frequency = it },
                    label = { Text("frequency_penalty") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
