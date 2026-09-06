package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.buildMessagesJson
import cn.luoluna.sweven.ai.net.extractMessageContent
import cn.luoluna.sweven.ai.net.extractToolCalls
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.ui.chat.ChatViewModel
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.WaveIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 测试项类型 */
enum class TestKind(val label: String, val hint: String) {
    STREAM("流式请求", "SSE 流式输出"),
    NON_STREAM("非流式请求", "等待完整响应"),
    TOOL_CALL("工具调用", "get_weather 工具"),
}

/** 单项测试状态 */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val durationMs: Long, val summary: String) : TestState
    data class Failure(val durationMs: Long, val message: String) : TestState
}

private const val TEST_PROMPT = "你好，请用一句话介绍你自己。"
private const val TOOL_PROMPT = "帮我查一下北京今天的天气"

/** 工具调用测试的固定工具定义 */
private val TOOL_TEST_TOOLS = JsonArray(
    listOf(
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "get_weather")
                put("description", "查询指定城市当前的天气情况")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("city", buildJsonObject {
                            put("type", "string")
                            put("description", "城市名称，如 北京")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("city"))))
                })
            })
        }
    )
)

/**
 * 模型测试弹窗：顶部选择本厂商的模型，对流式 / 非流式 / 工具调用三项并行发起测试，
 * 进行中显示波浪动画，失败可点击查看完整错误日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelTestDialog(
    provider: ProviderEntity,
    models: List<ModelEntity>,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    var selected by remember(models) { mutableStateOf(models.firstOrNull()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val states = remember {
        mutableStateMapOf<TestKind, TestState>().apply {
            TestKind.entries.forEach { put(it, TestState.Idle) }
        }
    }
    val logs = remember { mutableStateMapOf<TestKind, String>() }
    val jobs = remember { mutableMapOf<TestKind, Job>() }
    var viewLogKind by remember { mutableStateOf<TestKind?>(null) }
    val running = states.values.any { it is TestState.Running }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        TestKind.entries.forEach { if (states[it] is TestState.Running) states[it] = TestState.Idle }
    }

    fun requestBody(model: ModelEntity, prompt: String, stream: Boolean): JsonObject {
        val messages = buildMessagesJson(listOf(Triple(Role.USER, prompt, emptyList<String>())))
        var body = buildChatBody(
            modelName = model.name,
            messages = messages,
            stream = stream,
            defaults = ChatViewModel.advancedParamsOf(model),
        )
        return mergeCustomBody(body, model.customBody)
    }

    fun startAll() {
        val model = selected ?: return
        scope.launch {
            val client = app.providerClient
            val key = app.modelRouter.activeKey(provider)
            if (key == null || key.key.isBlank()) {
                TestKind.entries.forEach {
                    states[it] = TestState.Failure(0, "厂商「${provider.alias}」未配置 API Key")
                }
                return@launch
            }
            val extraHeaders = parseCustomHeaders(model.customHeaders)
            val streamBody = requestBody(model, TEST_PROMPT, stream = true)
            val nonStreamBody = requestBody(model, TEST_PROMPT, stream = false)
            val toolBody = JsonObject(
                requestBody(model, TOOL_PROMPT, stream = false).toMutableMap().apply {
                    put("tools", TOOL_TEST_TOOLS)
                    put("tool_choice", JsonPrimitive("auto"))
                }
            )
            TestKind.entries.forEach { kind ->
                states[kind] = TestState.Running
                jobs[kind] = launch {
                    val startedAt = System.currentTimeMillis()
                    try {
                        val summary = when (kind) {
                            TestKind.STREAM -> runStreamTest(client, provider.baseUrl, key.key, streamBody, extraHeaders)
                            TestKind.NON_STREAM -> runNonStreamTest(client, provider.baseUrl, key.key, nonStreamBody, extraHeaders)
                            TestKind.TOOL_CALL -> runToolCallTest(client, provider.baseUrl, key.key, toolBody, extraHeaders)
                        }
                        states[kind] = TestState.Success(System.currentTimeMillis() - startedAt, summary)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        states[kind] = TestState.Failure(
                            System.currentTimeMillis() - startedAt,
                            e.message ?: e.javaClass.simpleName,
                        )
                        logs[kind] = buildTestLog(kind, provider, model, e)
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(screenHeight * 0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "模型测试",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (!running) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 顶部模型选择器（仅本厂商模型）
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selected?.let { m ->
                            m.alias?.takeIf { it.isNotBlank() }?.let { "${it}（${m.name}）" } ?: m.name
                        } ?: "无可用模型",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.alias?.takeIf { it.isNotBlank() } ?: m.name)
                                        Text(
                                            m.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selected = m
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TestKind.entries.forEach { kind ->
                        TestRow(kind, states[kind] ?: TestState.Idle) { viewLogKind = kind }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (running) {
                        Text(
                            "测试中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        OutlinedButton(onClick = ::stopAll) { Text("停止") }
                    } else {
                        Button(onClick = ::startAll, enabled = selected != null) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("开始测试", Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }

    viewLogKind?.let { kind ->
        TestLogDialog(kind, logs[kind].orEmpty()) { viewLogKind = null }
    }
}

@Composable
private fun TestRow(
    kind: TestKind,
    state: TestState,
    onClickLog: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        kind.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    is TestState.Idle -> Text(
                        "待测试",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    is TestState.Running -> WaveIndicator()
                    is TestState.Success -> Text(
                        "✓ ${state.durationMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF16A34A),
                    )
                    is TestState.Failure -> Text(
                        "✗ ${state.durationMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            when (state) {
                is TestState.Success -> Text(
                    state.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is TestState.Failure -> Column(Modifier.padding(top = 4.dp)) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "点击查看完整错误日志",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onClickLog),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun TestLogDialog(kind: TestKind, log: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("${kind.label} · 完整日志") },
        text = {
            Text(
                log.ifBlank { "（无日志）" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
    )
}

// ---- 测试执行 ----

private suspend fun runStreamTest(
    client: ProviderClient,
    baseUrl: String,
    apiKey: String,
    body: JsonObject,
    extraHeaders: Map<String, String>,
): String {
    val started = System.currentTimeMillis()
    var firstTokenAt = -1L
    val text = StringBuilder()
    client.chatDeltas(baseUrl, apiKey, body, extraHeaders).collect { ev ->
        when (ev) {
            is ChatEvent.Delta -> {
                if (firstTokenAt < 0) firstTokenAt = System.currentTimeMillis() - started
                text.append(ev.text)
            }
            is ChatEvent.ReasoningDelta -> {
                if (firstTokenAt < 0) firstTokenAt = System.currentTimeMillis() - started
            }
            is ChatEvent.Failed -> throw RuntimeException(ev.message)
            ChatEvent.Done -> Unit
        }
    }
    if (text.isBlank()) {
        throw RuntimeException(if (firstTokenAt >= 0) "流式响应无文本内容" else "未收到任何输出")
    }
    val ft = if (firstTokenAt < 0) "—" else "${firstTokenAt}ms"
    return "回复 ${text.length} 字 · 首字 $ft"
}

private suspend fun runNonStreamTest(
    client: ProviderClient,
    baseUrl: String,
    apiKey: String,
    body: JsonObject,
    extraHeaders: Map<String, String>,
): String {
    val resp = client.chatOnce(baseUrl, apiKey, body, extraHeaders).getOrThrow()
    val content = extractMessageContent(resp)
    if (content.isNullOrBlank()) {
        throw RuntimeException("响应无内容（choices[0].message.content 为空）")
    }
    return "回复 ${content.length} 字"
}

private suspend fun runToolCallTest(
    client: ProviderClient,
    baseUrl: String,
    apiKey: String,
    body: JsonObject,
    extraHeaders: Map<String, String>,
): String {
    val resp = client.chatOnce(baseUrl, apiKey, body, extraHeaders).getOrThrow()
    val calls = extractToolCalls(resp)
    if (calls.isEmpty()) {
        throw RuntimeException("模型未返回 tool_calls（可能不支持工具调用）")
    }
    return "返回工具调用：${calls.joinToString("、") { it.name }}"
}

private fun buildTestLog(
    kind: TestKind,
    provider: ProviderEntity,
    model: ModelEntity,
    e: Exception,
): String = buildString {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    appendLine("== ${kind.label} 测试失败 ==")
    appendLine("时间: $ts")
    appendLine("厂商: ${provider.alias}  (${provider.baseUrl})")
    appendLine("模型: ${model.name}")
    appendLine()
    appendLine("== 错误信息 ==")
    appendLine(e.message ?: e.javaClass.simpleName)
    (e as? HttpApiException)?.let {
        appendLine()
        appendLine("== HTTP ${it.httpCode} 响应体 ==")
        appendLine(it.rawBody.ifBlank { "（空）" })
    }
    appendLine()
    appendLine("== 堆栈 ==")
    appendLine(e.stackTraceToString())
}
