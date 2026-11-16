package cn.luoluna.sweven.ai.ui.tools

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.tools.custom.CustomToolLoader
import cn.luoluna.sweven.ai.tools.custom.CustomToolSpec
import cn.luoluna.sweven.ai.tools.custom.HttpToolConfig
import cn.luoluna.sweven.ai.tools.custom.JsToolConfig
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold
import cn.luoluna.sweven.ai.ui.common.InfoIconWithPopup
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * 自定义工具编辑页（toolId <= 0 = 新建）。
 *
 * - 校验规则与装载器共用 [CustomToolSpec] / [HttpToolConfig] / [JsToolConfig]，保存即「编辑页通过 → 装载器能注册」
 * - 「试运行」用当前表单（**含未保存的改动**）构造临时工具真跑一次，便于调 URL/占位符/结果路径
 * - 保存后由 [CustomToolLoader] 自动同步进 ToolRegistry（注册表是 mutableStateMap，UI 即时刷新）
 */
@Composable
fun CustomToolEditorScreen(toolId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val dao = app.database.customToolDao()
    val isNew = toolId <= 0

    var loaded by remember { mutableStateOf(false) }
    var originalName by remember { mutableStateOf("") }
    var originalCreatedAt by remember { mutableStateOf(System.currentTimeMillis()) }

    var displayName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var noParameters by remember { mutableStateOf(true) }
    var parameters by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CustomToolKind.HTTP) }
    var needsApproval by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }

    // HTTP 配置
    var method by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    var bodyTemplate by remember { mutableStateOf("") }
    var timeoutSeconds by remember { mutableStateOf("15") }
    var maxChars by remember { mutableStateOf("8000") }
    var resultPath by remember { mutableStateOf("") }

    // JS 配置
    var code by remember { mutableStateOf("") }

    var error by remember { mutableStateOf<String?>(null) }
    var testArgs by remember { mutableStateOf("{\n  \"query\": \"测试\"\n}") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(toolId) {
        if (!isNew) {
            dao.byId(toolId)?.let { e ->
                originalName = e.name
                originalCreatedAt = e.createdAt
                displayName = e.displayName
                name = e.name
                description = e.description
                kind = e.kind
                needsApproval = e.needsApproval
                enabled = e.enabled
                noParameters = e.parametersJson.isNullOrBlank()
                parameters = e.parametersJson.orEmpty()
                when (e.kind) {
                    CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(e.configJson) }.onSuccess { c ->
                        method = c.method
                        url = c.url
                        headers = c.headersJson.orEmpty()
                        bodyTemplate = c.bodyTemplate.orEmpty()
                        timeoutSeconds = (c.timeoutMs / 1000).toString()
                        maxChars = c.maxChars.toString()
                        resultPath = c.resultPath.orEmpty()
                    }

                    else -> runCatching { JsToolConfig.parse(e.configJson) }.onSuccess { c ->
                        code = c.code
                    }
                }
            }
        }
        loaded = true
    }

    fun configJson(): String = if (kind == CustomToolKind.HTTP) {
        HttpToolConfig(
            method = method,
            url = url.trim(),
            headersJson = headers.takeIf { it.isNotBlank() },
            bodyTemplate = bodyTemplate.takeIf { it.isNotBlank() },
            timeoutMs = (timeoutSeconds.trim().toLongOrNull() ?: 15L) * 1000L,
            maxChars = maxChars.trim().toIntOrNull() ?: 8000,
            resultPath = resultPath.takeIf { it.isNotBlank() },
        ).toJson()
    } else {
        JsToolConfig(code).toJson()
    }

    /** 表单 → 实体；[validate] 时校验并写 [error]（保存路径），试运行路径只校验配置本身 */
    fun fail(message: String): CustomToolEntity? {
        error = message
        return null
    }

    fun buildEntity(validate: Boolean): CustomToolEntity? {
        val taken = ToolRegistry.takenNames().filterNot { it == originalName }
        val parametersJson = if (noParameters) null else parameters
        if (validate) {
            if (displayName.isBlank()) return fail("展示名不能为空")
            CustomToolSpec.nameError(name, taken)?.let { return fail(it) }
            CustomToolSpec.parametersError(parametersJson)?.let { return fail(it) }
        }
        val config = configJson()
        val configError = when (kind) {
            CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(config) }.exceptionOrNull()?.message
            else -> runCatching { JsToolConfig.parse(config) }.exceptionOrNull()?.message
        }
        if (configError != null) return fail(configError)
        error = null
        return CustomToolEntity(
            id = if (isNew) 0 else toolId,
            name = name.ifBlank { "preview_tool" },
            displayName = displayName.ifBlank { name.ifBlank { "未命名工具" } },
            description = description,
            parametersJson = parametersJson?.takeIf { it.isNotBlank() },
            kind = kind,
            configJson = config,
            needsApproval = needsApproval,
            enabled = enabled,
            createdAt = originalCreatedAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    FullPageScaffold(
        title = if (isNew) "新建工具" else "编辑工具",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                val entity = buildEntity(validate = true) ?: return@TextButton
                scope.launch {
                    if (isNew) dao.insert(entity) else dao.update(entity)
                    Toast.makeText(app, "已保存", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }) { Text("保存") }
        },
    ) { padding ->
        if (!loaded) return@FullPageScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            SectionCard(title = "基本信息") {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; error = null },
                    label = { Text("展示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("函数名", style = MaterialTheme.typography.bodyMedium)
                    InfoIconWithPopup(
                        title = "函数名",
                        text = "暴露给模型的函数名（OpenAI tools[].function.name）：只能字母、数字、下划线，" +
                            "长度 1-64；不能与内置工具或其它自定义工具重名。保存后不建议再改（历史会话里的调用记录会失配）。",
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("函数名") },
                    singleLine = true,
                    enabled = isNew,
                    supportingText = {
                        Text(if (isNew) "例如 get_weather、my_api_query" else "函数名保存后不可修改")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明（给模型看的）") },
                    minLines = 2,
                    supportingText = { Text("写清「什么时候该调用它」，模型据此决定是否调用") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "参数") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("无参数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = noParameters, onCheckedChange = { noParameters = it })
                }
                if (!noParameters) {
                    OutlinedTextField(
                        value = parameters,
                        onValueChange = { parameters = it; error = null },
                        label = { Text("参数 JSON Schema") },
                        minLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { parameters = CustomToolSpec.SAMPLE_PARAMETERS }) {
                        Text("插入示例")
                    }
                } else {
                    Text(
                        "无参数工具不带 parameters 字段，模型直接调用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = "执行方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == CustomToolKind.HTTP,
                        onClick = { kind = CustomToolKind.HTTP },
                        label = { Text("HTTP 请求") },
                    )
                    FilterChip(
                        selected = kind == CustomToolKind.JS,
                        onClick = { kind = CustomToolKind.JS },
                        label = { Text("JS 脚本") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (kind == CustomToolKind.HTTP) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("URL（支持 {参数名} 占位）") },
                        singleLine = true,
                        supportingText = { Text("例：https://api.example.com/search?q={query}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = method,
                        onValueChange = { method = it.uppercase(); error = null },
                        label = { Text("方法（GET/POST/PUT/PATCH/DELETE/HEAD）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it; error = null },
                        label = { Text("请求头（JSON 对象，或每行 Key: Value）") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        supportingText = { Text("例：{\"Authorization\":\"Bearer {token}\"}；值里的 {参数名} 会被替换") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bodyTemplate,
                        onValueChange = { bodyTemplate = it; error = null },
                        label = { Text("请求体模板（可空）") },
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        supportingText = { Text("留空时：带 body 的方法直接把模型给的参数对象作为 JSON 发出") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resultPath,
                        onValueChange = { resultPath = it; error = null },
                        label = { Text("结果路径（可空）") },
                        singleLine = true,
                        supportingText = { Text("例：data.items[0].text；留空返回整段响应") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeoutSeconds,
                            onValueChange = { timeoutSeconds = it },
                            label = { Text("超时（秒）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxChars,
                            onValueChange = { maxChars = it },
                            label = { Text("结果上限（字符）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text("JavaScript 代码") },
                        minLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                        supportingText = {
                            Text("参数以 args 注入；脚本内用 return 返回结果（例：return args.a + args.b）。沙箱无网络、无 DOM，超时 10 秒")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionCard(title = "选项") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    "启用后：注册进工具列表，并默认勾选进新建会话（单个会话可在扳手按钮里单独调整）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("执行前需确认", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = needsApproval, onCheckedChange = { needsApproval = it })
                }
                Text(
                    "开启后模型每次调用该工具都会先弹窗让你确认（参数与代码摘要可见）；" +
                        "涉及隐私或设备状态变更的工具建议开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "试运行") {
                Text(
                    "用当前表单（含未保存的改动）真跑一次，参数按下面的 JSON 传入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testArgs,
                    onValueChange = { testArgs = it },
                    label = { Text("参数 JSON") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !testing,
                        onClick = {
                            val entity = buildEntity(validate = false) ?: return@Button
                            val tool = CustomToolLoader.buildTool(entity)
                            if (tool == null) {
                                error = "配置不完整，无法运行"
                                return@Button
                            }
                            val args = runCatching {
                                ChatJson.json.parseToJsonElement(testArgs).let { it as? JsonObject ?: JsonObject(emptyMap()) }
                            }.getOrElse {
                                error = "参数不是合法 JSON 对象：${it.message?.take(120).orEmpty()}"
                                return@Button
                            }
                            error = null
                            testing = true
                            testResult = null
                            scope.launch {
                                val started = System.currentTimeMillis()
                                val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { tool.execute(args) }
                                    ?: "{\"error\":\"执行超时（${TEST_TIMEOUT_MS / 1000}s）\"}"
                                testResult = "耗时 ${System.currentTimeMillis() - started} ms\n$result"
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "运行中…" else "运行测试") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "运行不会保存改动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                testResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            if (!isNew) {
                HorizontalDivider()
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除该工具", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除工具",
            text = "删除后该工具会从工具列表移除，历史会话里的调用记录仍保留。确定删除？",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    dao.deleteById(toolId)
                    Toast.makeText(app, "已删除", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            },
        )
    }
}

private const val TEST_TIMEOUT_MS = 60_000L
