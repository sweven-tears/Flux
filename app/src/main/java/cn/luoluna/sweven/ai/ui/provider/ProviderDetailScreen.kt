package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.net.TestResult
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import cn.luoluna.sweven.ai.ui.common.maskKey
import cn.luoluna.sweven.ai.ui.common.paramsSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(id: Long, onBack: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()

    val provider by remember(id) { app.database.providerDao().observeById(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val keys by remember(id) { app.database.apiKeyDao().observeForProvider(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val models by remember(id) { app.database.modelDao().observeForProvider(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showBaseEdit by remember { mutableStateOf(false) }
    var showAliasEdit by remember { mutableStateOf(false) }
    var showAddKey by remember { mutableStateOf(false) }
    var deleteKeyTarget by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var deleteModelTarget by remember { mutableStateOf<ModelEntity?>(null) }
    var editModel by remember { mutableStateOf<ModelEntity?>(null) }
    var showNewModel by remember { mutableStateOf(false) }
    var showDeleteProvider by remember { mutableStateOf(false) }

    // 获取模型列表
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    val p = provider ?: run {
        Scaffold(topBar = { TopAppBar(title = { Text("厂商详情") }, navigationIcon = { BackButton(onBack) }) }) { _ ->
            Column(Modifier.padding(32.dp)) { Text("加载中…") }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.alias) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { showDeleteProvider = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除厂商")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 基本信息
            SectionCard(title = "基本信息") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Base URL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.baseUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = MonoFont)
                    }
                    IconButton(onClick = { showBaseEdit = true }) { Icon(Icons.Filled.Edit, contentDescription = "修改 Base URL") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    testResult?.let { r ->
                        Text(
                            (if (r.ok) "✓ " else "✗ ") + r.message,
                            color = if (r.ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    } ?: Text(
                        p.lastTestMessage ?: "尚未测试连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            testing = true
                            scope.launch {
                                val key = app.modelRouter.activeKey(p)
                                val r = app.providerClient.testConnection(p.baseUrl, key?.key)
                                testResult = r
                                testing = false
                                app.database.providerDao().updateTestResult(
                                    p.id, r.ok, r.latencyMs, r.message, System.currentTimeMillis()
                                )
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "测试中…" else "测试连接") }
                }
            }

            // API Keys
            SectionCard(
                title = "API Keys（${keys.size}）",
                actions = {
                    TextButton(onClick = { showAddKey = true }) { Text("添加 Key") }
                },
            ) {
                if (keys.isEmpty()) {
                    Text(
                        "尚未配置 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                keys.forEachIndexed { index, k ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = k.id == p.activeKeyId || (p.activeKeyId == null && index == 0),
                            onClick = {
                                scope.launch { app.database.providerDao().setActiveKey(p.id, k.id) }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(k.alias, style = MaterialTheme.typography.bodyMedium)
                                if (k.id == p.activeKeyId || (p.activeKeyId == null && index == 0)) {
                                    Text(
                                        "  当前使用",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                maskKey(k.key),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFont,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleteKeyTarget = k }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除 Key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 模型
            SectionCard(
                title = "模型（${models.size}）",
                actions = {
                    TextButton(
                        onClick = {
                            fetching = true
                            fetchError = null
                            scope.launch {
                                val key = app.modelRouter.activeKey(p)
                                app.providerClient.listModels(p.baseUrl, key?.key)
                                    .onSuccess { fetchedModels = it }
                                    .onFailure { fetchError = it.message ?: "获取失败" }
                                fetching = false
                            }
                        },
                        enabled = !fetching,
                    ) { Text(if (fetching) "获取中…" else "获取列表") }
                    TextButton(onClick = { showNewModel = true }) { Text("手动添加") }
                },
            ) {
                fetchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                if (models.isEmpty()) {
                    Text(
                        "尚未添加模型：可通过「获取列表」拉取 /models，或手动添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                models.forEach { m ->
                    ModelRow(
                        model = m,
                        onToggle = { nv ->
                            scope.launch { app.database.modelDao().update(m.copy(enabled = nv)) }
                        },
                        onEdit = { editModel = m },
                        onDelete = { deleteModelTarget = m },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 对话框 ----
    if (showBaseEdit) {
        TextFieldDialog(
            title = "修改 Base URL",
            initial = p.baseUrl,
            onDismiss = { showBaseEdit = false },
            onConfirm = { v ->
                scope.launch {
                    app.database.providerDao().update(p.copy(baseUrl = v.trim()))
                    showBaseEdit = false
                }
            },
        )
    }
    if (showAliasEdit) {
        TextFieldDialog(
            title = "修改别名",
            initial = p.alias,
            onDismiss = { showAliasEdit = false },
            onConfirm = { v ->
                scope.launch {
                    app.database.providerDao().update(p.copy(alias = v.trim()))
                    showAliasEdit = false
                }
            },
        )
    }
    if (showAddKey) {
        AddKeyDialog(
            onDismiss = { showAddKey = false },
            onConfirm = { alias, key ->
                scope.launch {
                    val keyId = app.database.apiKeyDao().insert(
                        ApiKeyEntity(providerId = p.id, alias = alias, key = key)
                    )
                    if (p.activeKeyId == null) {
                        app.database.providerDao().setActiveKey(p.id, keyId)
                    }
                    showAddKey = false
                }
            },
        )
    }
    deleteKeyTarget?.let { k ->
        ConfirmDialog(
            title = "删除 Key",
            text = "确定删除 API Key「${k.alias}」？",
            onDismiss = { deleteKeyTarget = null },
            onConfirm = {
                scope.launch {
                    app.database.apiKeyDao().delete(k)
                    if (p.activeKeyId == k.id) {
                        app.database.providerDao().setActiveKey(p.id, null)
                    }
                    deleteKeyTarget = null
                }
            },
        )
    }
    deleteModelTarget?.let { m ->
        ConfirmDialog(
            title = "删除模型",
            text = "确定删除模型「${m.name}」？局域网聚合列表将不再提供该模型。",
            onDismiss = { deleteModelTarget = null },
            onConfirm = {
                scope.launch {
                    app.database.modelDao().delete(m)
                    deleteModelTarget = null
                }
            },
        )
    }
    if (showDeleteProvider) {
        ConfirmDialog(
            title = "删除厂商",
            text = "确定删除厂商「${p.alias}」？其 API Key 与模型配置将一并删除。",
            onDismiss = { showDeleteProvider = false },
            onConfirm = {
                scope.launch {
                    app.database.providerDao().delete(p)
                    showDeleteProvider = false
                    onBack()
                }
            },
        )
    }
    if (showNewModel) {
        ModelEditDialog(
            providerId = p.id,
            existing = null,
            onDismiss = { showNewModel = false },
            onSave = { m ->
                scope.launch {
                    app.database.modelDao().insert(m)
                    showNewModel = false
                }
            },
        )
    }
    editModel?.let { m ->
        ModelEditDialog(
            providerId = p.id,
            existing = m,
            onDismiss = { editModel = null },
            onSave = { nm ->
                scope.launch {
                    app.database.modelDao().update(nm)
                    editModel = null
                }
            },
        )
    }
    fetchedModels?.let { fetched ->
        FetchModelsDialog(
            fetched = fetched,
            existingNames = models.map { it.name }.toSet(),
            onDismiss = { fetchedModels = null },
            onConfirm = { selected ->
                scope.launch {
                    app.database.modelDao().insertAll(
                        selected.map { name ->
                            ModelEntity(providerId = p.id, name = name)
                        }
                    )
                    fetchedModels = null
                }
            },
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

@Composable
private fun ModelRow(
    model: ModelEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name + if (!model.enabled) "（已停用）" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            ModelBadgesRow(model, Modifier.padding(top = 2.dp))
            paramsSummary(model)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = model.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑模型", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除模型", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TextFieldDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@Composable
private fun AddKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (alias: String, key: String) -> Unit,
) {
    var alias by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (alias.isBlank() || key.isBlank()) error = "别名和 Key 都不能为空"
                else onConfirm(alias.trim(), key.trim())
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加 API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Key 别名（如 主号 / 备用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

@Composable
private fun FetchModelsDialog(
    fetched: List<String>,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var checked by remember {
        mutableStateOf(
            fetched.associateWith { it !in existingNames }
        )
    }
    val shown = fetched.filter { it.contains(filter, ignoreCase = true) }
    val selectable = shown.filter { it !in existingNames }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(checked.filter { it.value }.keys.toList())
            }) { Text("添加所选") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    checked = checked.mapValues { selectable.contains(it.key) }
                }) { Text("全选新模型") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text("选择模型（${fetched.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown, key = { it }) { name ->
                        val isExisting = name in existingNames
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isExisting || (checked[name] ?: false),
                                onCheckedChange = { if (!isExisting) checked = checked + (name to it) },
                                enabled = !isExisting,
                            )
                            Text(
                                name + if (isExisting) "（已添加）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
    )
}
