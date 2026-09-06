package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.preset.Presets
import cn.luoluna.sweven.ai.net.TestResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddProviderScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var mode by rememberSaveable { mutableStateOf(0) } // 0 预设 / 1 自定义
    var selectedPresetId by rememberSaveable { mutableStateOf<String?>(null) }

    var alias by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var remark by rememberSaveable { mutableStateOf("") }
    var keyAlias by rememberSaveable { mutableStateOf("默认 Key") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun applyPreset(id: String) {
        val preset = Presets.all.firstOrNull { it.id == id } ?: return
        selectedPresetId = id
        if (alias.isBlank() || Presets.all.any { it.name == alias }) alias = preset.name
        baseUrl = preset.baseUrl
        testResult = null
        formError = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加厂商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    })
                }
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == 0,
                    onClick = { mode = 0; selectedPresetId = null },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("预设厂商") }
                SegmentedButton(
                    selected = mode == 1,
                    onClick = { mode = 1; selectedPresetId = null },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("自定义") }
            }

            if (mode == 0) {
                Text(
                    "选择预设厂商（Base URL 已预填，只需填 API Key）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Presets.all.forEach { p ->
                        FilterChip(
                            selected = selectedPresetId == p.id,
                            onClick = { applyPreset(p.id) },
                            label = { Text(p.name) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("厂商别名 *") },
                supportingText = { Text("聚合模型将显示为「别名/模型名」") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; testResult = null },
                label = { Text("Base URL *") },
                placeholder = { Text("https://api.example.com/v1") },
                supportingText = { Text("OpenAI 兼容地址，通常填写到 /v1（智谱为 /v4）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("备注") },
                supportingText = { Text("可选，记录计费方式、额度、注意事项等") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keyAlias,
                onValueChange = { keyAlias = it },
                label = { Text("API Key 别名") },
                supportingText = { Text("保存后可在详情页添加多个 Key 并随时切换") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; testResult = null },
                label = { Text("API Key") },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "隐藏" else "显示")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        val url = baseUrl
                        val key = apiKey.ifBlank { null }
                        scope.launch {
                            testResult = app.providerClient.testConnection(url, key)
                            testing = false
                        }
                    },
                    enabled = baseUrl.isNotBlank() && !testing,
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text("测试连接")
                }
                Button(
                    onClick = {
                        formError = null
                        val finalAlias = alias.trim()
                        val finalUrl = baseUrl.trim()
                        if (finalAlias.isEmpty() || finalUrl.isEmpty()) {
                            formError = "请填写厂商别名和 Base URL"
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            val dup = app.database.providerDao().countAliasExcluding(finalAlias, 0)
                            if (dup > 0) {
                                formError = "别名「$finalAlias」已存在，请换一个（局域网路由用别名区分厂商）"
                                saving = false
                                return@launch
                            }
                            val isPreset = mode == 0 && selectedPresetId != null
                            val nextSort = app.database.providerDao().all().maxOfOrNull { it.sortOrder } ?: -1
                            val providerId = app.database.providerDao().insert(
                                ProviderEntity(
                                    alias = finalAlias,
                                    baseUrl = finalUrl,
                                    remark = remark.trim().ifBlank { null },
                                    isPreset = isPreset,
                                    presetId = if (isPreset) selectedPresetId else null,
                                    sortOrder = nextSort + 1,
                                )
                            )
                            if (apiKey.isNotBlank()) {
                                val keyId = app.database.apiKeyDao().insert(
                                    ApiKeyEntity(
                                        providerId = providerId,
                                        alias = keyAlias.ifBlank { "默认 Key" },
                                        key = apiKey.trim(),
                                    )
                                )
                                app.database.providerDao().setActiveKey(providerId, keyId)
                            }
                            saving = false
                            onSaved(providerId)
                        }
                    },
                    enabled = !saving,
                ) { Text("保存") }
            }

            testResult?.let { r ->
                Text(
                    (if (r.ok) "✓ " else "✗ ") + r.message,
                    color = if (r.ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            formError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
