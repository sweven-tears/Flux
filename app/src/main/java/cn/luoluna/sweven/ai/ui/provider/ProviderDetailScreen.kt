package cn.luoluna.sweven.ai.ui.provider

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Science
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.net.RemoteModel
import cn.luoluna.sweven.ai.net.TestResult
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.ModalityTag
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.ReorderState
import cn.luoluna.sweven.ai.ui.common.SectionCard
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.maskKey
import cn.luoluna.sweven.ai.ui.common.normalizeModelId
import cn.luoluna.sweven.ai.ui.common.paramsSummary
import cn.luoluna.sweven.ai.ui.common.rememberReorderState
import cn.luoluna.sweven.ai.ui.common.reorderDragged
import cn.luoluna.sweven.ai.ui.common.reorderHandle
import cn.luoluna.sweven.ai.ui.common.typeLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()

    val provider by remember(id) { app.database.providerDao().observeById(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val keys by remember(id) { app.database.apiKeyDao().observeForProvider(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val models by remember(id) { app.database.modelDao().observeForProvider(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showBaseEdit by remember { mutableStateOf(false) }
    var showAliasEdit by remember { mutableStateOf(false) }
    var showRemarkEdit by remember { mutableStateOf(false) }
    var showAddKey by remember { mutableStateOf(false) }
    var deleteKeyTarget by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var editModel by remember { mutableStateOf<ModelEntity?>(null) }
    var showNewModel by remember { mutableStateOf(false) }
    var showDeleteProvider by remember { mutableStateOf(false) }
    var showModelTest by remember { mutableStateOf(false) }

    // 获取模型列表
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<RemoteModel>?>(null) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

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
                    IconButton(onClick = { showModelTest = true }) {
                        Icon(Icons.Filled.Science, contentDescription = "模型测试")
                    }
                    IconButton(onClick = { showAliasEdit = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "修改厂商名称")
                    }
                    IconButton(onClick = { showDeleteProvider = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除厂商")
                    }
                },
            )
        },
    ) { padding ->
        // 拖拽换位后的本地顺序，落库完成前临时覆盖 Flow 数据
        var localModelOrder by remember { mutableStateOf<List<ModelEntity>?>(null) }
        val displayModels = localModelOrder ?: models
        val reorderState = rememberReorderState(listState) { fromKey, toKey ->
            val from = displayModels.indexOfFirst { it.id == fromKey }
            val to = displayModels.indexOfFirst { it.id == toKey }
            if (from < 0 || to < 0 || from == to) return@rememberReorderState
            val reordered = displayModels.toMutableList().apply { add(to, removeAt(from)) }
                .mapIndexed { index, m -> m.copy(sortOrder = index) }
            localModelOrder = reordered
            scope.launch {
                app.database.modelDao().updateAll(reordered)
                localModelOrder = null
            }
        }
        // 侧滑删除：同一时间至多一行滑开，滚动列表时收回
        var openSwipeModelId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) openSwipeModelId = null
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 基本信息
            item(key = "info") {
                SectionCard(title = "基本信息") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Base URL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.baseUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = MonoFont)
                        }
                        IconButton(onClick = { showBaseEdit = true }) { Icon(Icons.Filled.Edit, contentDescription = "修改 Base URL") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("备注", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                p.remark?.takeIf { it.isNotBlank() } ?: "未填写",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (p.remark?.isNotBlank() == true) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showRemarkEdit = true }) { Icon(Icons.Filled.Edit, contentDescription = "修改备注") }
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
            }

            // API Keys
            item(key = "keys") {
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
                            IconButton(onClick = { copyApiKey(context, k.key) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "复制 Key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { deleteKeyTarget = k }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除 Key", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 模型（模型行为独立条目，支持长按拖动排序）
            item(key = "models_header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "模型（${models.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
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
                    }
                    fetchError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (displayModels.isEmpty()) {
                item(key = "models_empty") {
                    Text(
                        "尚未添加模型：可通过「获取列表」拉取 /models，或手动添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(displayModels, key = { it.id }) { m ->
                ModelRowCard(
                    model = m,
                    reorderState = reorderState,
                    modifier = Modifier.animateItem(),
                    isOpen = openSwipeModelId == m.id,
                    onOpenChange = { opened -> openSwipeModelId = if (opened) m.id else null },
                    onToggle = { nv ->
                        scope.launch { app.database.modelDao().update(m.copy(enabled = nv)) }
                    },
                    onEdit = { editModel = m },
                    onDelete = { scope.launch { app.database.modelDao().delete(m) } },
                )
            }
        }
    }

    // ---- 对话框 ----
    if (showModelTest) {
        ModelTestDialog(
            provider = p,
            models = models,
            onDismiss = { showModelTest = false },
        )
    }
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
            title = "修改厂商名称",
            initial = p.alias,
            onDismiss = { showAliasEdit = false },
            onValidate = { v ->
                val name = v.trim()
                when {
                    name.isEmpty() -> "名称不能为空"
                    app.database.providerDao().countAliasExcluding(name, p.id) > 0 ->
                        "别名「$name」已存在，请换一个（局域网路由用别名区分厂商）"
                    else -> null
                }
            },
            onConfirm = { v ->
                scope.launch {
                    app.database.providerDao().update(p.copy(alias = v.trim()))
                    showAliasEdit = false
                }
            },
        )
    }
    if (showRemarkEdit) {
        TextFieldDialog(
            title = "修改备注",
            initial = p.remark.orEmpty(),
            singleLine = false,
            onDismiss = { showRemarkEdit = false },
            onConfirm = { v ->
                scope.launch {
                    app.database.providerDao().update(p.copy(remark = v.trim().ifBlank { null }))
                    showRemarkEdit = false
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
        ModelEditorSheet(
            providerId = p.id,
            isGeminiPreset = p.presetId == "gemini",
            existing = null,
            onDismiss = { showNewModel = false },
            onSave = { m ->
                scope.launch {
                    val next = (app.database.modelDao().forProvider(p.id).maxOfOrNull { it.sortOrder } ?: -1) + 1
                    app.database.modelDao().insert(m.copy(sortOrder = next))
                    showNewModel = false
                }
            },
        )
    }
    editModel?.let { m ->
        ModelEditorSheet(
            providerId = p.id,
            isGeminiPreset = p.presetId == "gemini",
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
                    val base = (app.database.modelDao().forProvider(p.id).maxOfOrNull { it.sortOrder } ?: -1) + 1
                    app.database.modelDao().insertAll(
                        selected.mapIndexed { i, rm ->
                            ModelEntity(
                                providerId = p.id,
                                name = rm.id,
                                alias = rm.displayName,
                                type = rm.type,
                                inputText = rm.inputText,
                                inputImage = rm.inputImage,
                                inputVideo = rm.inputVideo,
                                outputText = rm.outputText,
                                outputImage = rm.outputImage,
                                outputVideo = rm.outputVideo,
                                capabilityTools = rm.tools,
                                capabilityReasoning = rm.reasoning,
                                contextWindow = rm.contextLength,
                                sortOrder = base + i,
                            )
                        }
                    )
                    fetchedModels = null
                }
            },
        )
    }
}

private fun copyApiKey(context: Context, key: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Flux", key))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

/**
 * 模型行卡片：向左滑动露出红色删除按钮，点按钮直接删除（无确认弹窗）。
 * 滑过露出宽度一半松手即停住；点卡片空白处或滚动列表时收回；isOpen 供外部
 * 保证同一时间至多一行滑开。
 */
@Composable
private fun ModelRowCard(
    model: ModelEntity,
    reorderState: ReorderState,
    modifier: Modifier = Modifier,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val revealWidth = 84.dp
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(isOpen) {
        if (!dragging) offsetX.animateTo(if (isOpen) -revealPx else 0f, tween(200))
    }

    Box(modifier.reorderDragged(reorderState, model.id)) {
        // 背景：滑开后露出的删除按钮
        Box(
            Modifier
                .matchParentSize()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { onDelete() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.width(revealWidth),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除模型",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        // 前景卡片：跟手横移
        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = isOpen,
                ) { onOpenChange(false) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + amount).coerceIn(-revealPx, 0f))
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val open = offsetX.value <= -revealPx / 2f
                            scope.launch {
                                offsetX.animateTo(if (open) -revealPx else 0f, tween(200))
                            }
                            onOpenChange(open)
                        },
                        onDragCancel = {
                            dragging = false
                            val open = offsetX.value <= -revealPx / 2f
                            scope.launch {
                                offsetX.animateTo(if (open) -revealPx else 0f, tween(200))
                            }
                            onOpenChange(open)
                        },
                    )
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.alias?.takeIf { it.isNotBlank() }?.let { alias -> "$alias（${model.name}）" }
                            ?: model.name + if (!model.enabled) "（已停用）" else "",
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
                // 长按拖动排序手柄
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.reorderHandle(reorderState, model.id),
                )
            }
        }
    }
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
    fetched: List<RemoteModel>,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<RemoteModel>) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var checked by remember {
        mutableStateOf(
            fetched.associate { it.id to (it.id !in existingNames) }
        )
    }
    val shown = if (filter.isBlank()) fetched
    else fetched.filter { normalizeModelId(it.id).contains(normalizeModelId(filter)) }
    val selectable = shown.filter { it.id !in existingNames }
    val allNewSelected = selectable.isNotEmpty() && selectable.all { checked[it.id] == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(shown.filter { checked[it.id] == true })
            }) { Text("添加所选") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    checked = checked + selectable.associate { it.id to !allNewSelected }
                }) { Text(if (allNewSelected) "全不选新模型" else "全选新模型") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text("选择模型（${fetched.size}）") },
        text = {
            Column {
                Text(
                    "已按名称自动匹配模型类型与模态，添加后可再编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown, key = { it.id }) { rm ->
                        val isExisting = rm.id in existingNames
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isExisting || (checked[rm.id] ?: false),
                                onCheckedChange = { if (!isExisting) checked = checked + (rm.id to it) },
                                enabled = !isExisting,
                            )
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(
                                    rm.id + if (isExisting) "（已添加）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    ModalityTag(typeLabel(rm.type), color = MaterialTheme.colorScheme.primaryContainer)
                                    if (rm.tools) ModalityTag("工具", color = MaterialTheme.colorScheme.tertiaryContainer)
                                    if (rm.reasoning) ModalityTag("推理", color = MaterialTheme.colorScheme.tertiaryContainer)
                                    val ins = buildList {
                                        if (rm.inputText) add("文")
                                        if (rm.inputImage) add("图")
                                        if (rm.inputVideo) add("视")
                                    }
                                    val outs = buildList {
                                        if (rm.outputText) add("文")
                                        if (rm.outputImage) add("图")
                                        if (rm.outputVideo) add("视")
                                    }
                                    ModalityTag("入${ins.joinToString("")}")
                                    ModalityTag("出${outs.joinToString("")}")
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
