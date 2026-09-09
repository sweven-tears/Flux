package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType

private val EDITOR_TABS = listOf("基本信息", "高级设置", "内置工具")

/**
 * 模型编辑器：上滑弹窗（默认 80% 屏高）+ 三个 Tab（基本信息 / 高级设置 / 内置工具）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorSheet(
    providerId: Long,
    isGeminiPreset: Boolean,
    existing: ModelEntity?,
    initialName: String? = null,
    onDismiss: () -> Unit,
    onSave: (ModelEntity) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    // 基本信息
    var name by remember { mutableStateOf(existing?.name ?: initialName.orEmpty()) }
    var alias by remember { mutableStateOf(existing?.alias.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: ModelType.CHAT) }
    var inText by remember { mutableStateOf(existing?.inputText ?: true) }
    var inImage by remember { mutableStateOf(existing?.inputImage ?: false) }
    var inVideo by remember { mutableStateOf(existing?.inputVideo ?: false) }
    var outText by remember { mutableStateOf(existing?.outputText ?: true) }
    var outImage by remember { mutableStateOf(existing?.outputImage ?: false) }
    var outVideo by remember { mutableStateOf(existing?.outputVideo ?: false) }
    var tools by remember { mutableStateOf(existing?.capabilityTools ?: true) }
    var reasoning by remember { mutableStateOf(existing?.capabilityReasoning ?: false) }

    // 高级设置
    var temperature by remember { mutableStateOf(existing?.temperature?.toString() ?: "") }
    var topP by remember { mutableStateOf(existing?.topP?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(existing?.maxTokens?.toString() ?: "") }
    var presence by remember { mutableStateOf(existing?.presencePenalty?.toString() ?: "") }
    var frequency by remember { mutableStateOf(existing?.frequencyPenalty?.toString() ?: "") }
    var contextWindow by remember { mutableStateOf(existing?.contextWindow?.toString() ?: "") }
    var customHeaders by remember { mutableStateOf(existing?.customHeaders.orEmpty()) }
    var customBody by remember { mutableStateOf(existing?.customBody.orEmpty()) }

    // 内置工具
    var googleSearch by remember { mutableStateOf(existing?.builtinGoogleSearch ?: false) }
    var urlContext by remember { mutableStateOf(existing?.builtinUrlContext ?: false) }
    var imageGen by remember { mutableStateOf(existing?.builtinImageGen ?: false) }

    var error by remember { mutableStateOf<String?>(null) }

    fun parseNum(s: String): Double? = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun save() {
        val finalName = name.trim()
        if (finalName.isEmpty()) {
            error = "请填写模型 id"
            tab = 0
            return
        }
        val temp = parseNum(temperature)
        val top = parseNum(topP)
        val pres = parseNum(presence)
        val freq = parseNum(frequency)
        val maxTok = maxTokens.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val ctxWin = contextWindow.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if ((temperature.isNotBlank() && temp == null) ||
            (topP.isNotBlank() && top == null) ||
            (presence.isNotBlank() && pres == null) ||
            (frequency.isNotBlank() && freq == null) ||
            (maxTokens.isNotBlank() && maxTok == null)
        ) {
            error = "高级参数必须是数字（留空表示使用 API 默认值）"
            tab = 1
            return
        }
        if (contextWindow.isNotBlank() && (ctxWin == null || ctxWin <= 0)) {
            error = "上下文窗口必须是正整数（tokens）"
            tab = 1
            return
        }
        if (customBody.isNotBlank()) {
            try {
                cn.luoluna.sweven.ai.net.ChatJson.json.parseToJsonElement(customBody)
            } catch (_: Exception) {
                error = "自定义 body 不是合法 JSON"
                tab = 1
                return
            }
        }
        onSave(
            (existing ?: ModelEntity(providerId = providerId, name = finalName)).copy(
                providerId = providerId,
                name = finalName,
                alias = alias.trim().takeIf { it.isNotEmpty() },
                type = type,
                inputText = inText,
                inputImage = inImage,
                inputVideo = inVideo,
                outputText = outText,
                outputImage = outImage,
                outputVideo = outVideo,
                capabilityTools = tools,
                capabilityReasoning = reasoning,
                builtinGoogleSearch = googleSearch,
                builtinUrlContext = urlContext,
                builtinImageGen = imageGen,
                customHeaders = customHeaders.trim().takeIf { it.isNotEmpty() },
                customBody = customBody.trim().takeIf { it.isNotEmpty() },
                temperature = temp,
                topP = top,
                maxTokens = maxTok,
                presencePenalty = pres,
                frequencyPenalty = freq,
                contextWindow = ctxWin,
            )
        )
    }

    // ModalBottomSheet 内部是无界高度约束，fillMaxHeight(比例) 不生效，需按屏高显式计算；
    // 默认的半屏（PartiallyExpanded）锚点会截断高度，需 skipPartiallyExpanded 直接全开；
    // 拖拽把手块（约 57dp，实测）在内容之外，扣除后弹窗总高正好 80% 屏高
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f - 57.dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding()
        ) {
            TabRow(selectedTabIndex = tab) {
                EDITOR_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> BasicInfoTab(
                        name, { name = it }, alias, { alias = it }, type,
                        inText, { inText = it }, inImage, { inImage = it }, inVideo, { inVideo = it },
                        outText, { outText = it }, outImage, { outImage = it }, outVideo, { outVideo = it },
                        tools, { tools = it }, reasoning, { reasoning = it },
                        onTypeChange = { newType ->
                            type = newType
                            when (newType) {
                                ModelType.EMBEDDING -> {
                                    inText = true; inImage = false; inVideo = false
                                    outText = false; outImage = false; outVideo = false
                                    tools = false; reasoning = false
                                }
                                ModelType.IMAGE -> {
                                    inText = true; outText = false; outImage = true; outVideo = false
                                }
                                else -> {
                                    outText = true; outImage = false; outVideo = false
                                }
                            }
                        },
                    )
                    1 -> AdvancedTab(
                        temperature, { temperature = it },
                        topP, { topP = it },
                        maxTokens, { maxTokens = it },
                        presence, { presence = it },
                        frequency, { frequency = it },
                        contextWindow, { contextWindow = it },
                        customHeaders, { customHeaders = it },
                        customBody, { customBody = it },
                    )
                    else -> ToolsTab(
                        isGeminiPreset,
                        googleSearch, { googleSearch = it },
                        urlContext, { urlContext = it },
                        imageGen, { imageGen = it },
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { save() }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun BasicInfoTab(
    name: String, onName: (String) -> Unit,
    alias: String, onAlias: (String) -> Unit,
    type: String,
    inText: Boolean, onInText: (Boolean) -> Unit,
    inImage: Boolean, onInImage: (Boolean) -> Unit,
    inVideo: Boolean, onInVideo: (Boolean) -> Unit,
    outText: Boolean, onOutText: (Boolean) -> Unit,
    outImage: Boolean, onOutImage: (Boolean) -> Unit,
    outVideo: Boolean, onOutVideo: (Boolean) -> Unit,
    tools: Boolean, onTools: (Boolean) -> Unit,
    reasoning: Boolean, onReasoning: (Boolean) -> Unit,
    onTypeChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("模型 id *") },
        placeholder = { Text("API 使用的模型名，如 glm-5") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = alias,
        onValueChange = onAlias,
        label = { Text("模型名称（别名，可选）") },
        supportingText = { Text("展示用名称，为空时显示模型 id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("模型类型", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TypeChip("聊天", type == ModelType.CHAT) { onTypeChange(ModelType.CHAT) }
        TypeChip("图像", type == ModelType.IMAGE) { onTypeChange(ModelType.IMAGE) }
        TypeChip("嵌入", type == ModelType.EMBEDDING) { onTypeChange(ModelType.EMBEDDING) }
    }
    Text("输入模态", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModalityChip("文本", inText, onInText)
        ModalityChip("图片", inImage, onInImage)
        ModalityChip("视频", inVideo, onInVideo)
    }
    Text("输出模态", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModalityChip("文本", outText, onOutText)
        ModalityChip("图片", outImage, onOutImage)
        ModalityChip("视频", outVideo, onOutVideo)
    }
    Text("能力", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModalityChip("工具", tools, onTools)
        ModalityChip("推理", reasoning, onReasoning)
    }
}

@Composable
private fun AdvancedTab(
    temperature: String, onTemperature: (String) -> Unit,
    topP: String, onTopP: (String) -> Unit,
    maxTokens: String, onMaxTokens: (String) -> Unit,
    presence: String, onPresence: (String) -> Unit,
    frequency: String, onFrequency: (String) -> Unit,
    contextWindow: String, onContextWindow: (String) -> Unit,
    customHeaders: String, onCustomHeaders: (String) -> Unit,
    customBody: String, onCustomBody: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = temperature, onValueChange = onTemperature,
            label = { Text("temperature") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = topP, onValueChange = onTopP,
            label = { Text("top_p") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = maxTokens, onValueChange = onMaxTokens,
            label = { Text("max_tokens") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = presence, onValueChange = onPresence,
            label = { Text("presence_penalty") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    OutlinedTextField(
        value = frequency, onValueChange = onFrequency,
        label = { Text("frequency_penalty") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = contextWindow, onValueChange = onContextWindow,
        label = { Text("上下文窗口（tokens，可选）") },
        supportingText = { Text("用于聊天页用量显示与超长自动截断；拉取模型时若厂商提供会自动带入") },
        placeholder = { Text("128000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = customHeaders, onValueChange = onCustomHeaders,
        label = { Text("自定义 Headers（可选）") },
        supportingText = { Text("每行一条，格式：Name: Value") },
        placeholder = { Text("X-Title: Flux") },
        minLines = 2, maxLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = customBody, onValueChange = onCustomBody,
        label = { Text("自定义 Body（可选）") },
        supportingText = { Text("JSON 对象，键值将合入请求体（可覆盖默认参数）") },
        placeholder = { Text("{ \"top_k\": 50 }") },
        minLines = 2, maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToolsTab(
    isGeminiPreset: Boolean,
    googleSearch: Boolean, onGoogleSearch: (Boolean) -> Unit,
    urlContext: Boolean, onUrlContext: (Boolean) -> Unit,
    imageGen: Boolean, onImageGen: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(
                "这些工具由 API 内置，无需 App 提供执行环境。当前仅 Gemini 官方 API 生效，其他厂商开启后不会注入请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isGeminiPreset) {
                Text(
                    "当前厂商不是 Gemini 官方预设，以下开关不会被应用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    ToolSwitchRow("Google 搜索继承", "google_search", googleSearch, onGoogleSearch)
    ToolSwitchRow("URL 上下文处理", "url_context", urlContext, onUrlContext)
    ToolSwitchRow("图像生成功能", "modalities: image", imageGen, onImageGen)
}

@Composable
private fun ToolSwitchRow(
    title: String,
    apiName: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                apiName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(0.dp)) }
        } else null,
    )
}

@Composable
private fun ModalityChip(label: String, selected: Boolean, onClick: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onClick(!selected) },
        label = { Text(label) },
    )
}
