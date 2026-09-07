package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteEntity
import cn.luoluna.sweven.ai.ui.common.ReorderState
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.rememberReorderState
import cn.luoluna.sweven.ai.ui.common.reorderDragged
import cn.luoluna.sweven.ai.ui.common.reorderHandle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 操作按钮宽度：两个按钮，共 2 × 64dp */
private val SwipeActionWidth = 128.dp
private val SwipeActionEach = 64.dp

/**
 * 智能路由配置：把所有厂商的模型整合成聚合模型 smartAi，
 * 编辑回退优先级（长按拖动排序），侧滑可禁用/移除候选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRouteScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val entries by remember { app.database.smartRouteDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val providers by remember { app.database.providerDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val models by remember { app.database.modelDao().observeEnabled() }
        .collectAsStateWithLifecycle(emptyList())
    val timeoutSeconds by remember { app.settings.smartFirstTokenTimeoutSeconds }
        .collectAsStateWithLifecycle(30)
    val maxRounds by remember { app.settings.smartRouteMaxRounds }
        .collectAsStateWithLifecycle(3)

    var showTimeoutEdit by remember { mutableStateOf(false) }
    var showRoundsEdit by remember { mutableStateOf(false) }
    var showAddModels by remember { mutableStateOf(false) }

    // 拖拽换位后的本地顺序，落库完成前临时覆盖 Flow 数据
    var localOrder by remember { mutableStateOf<List<SmartRouteEntity>?>(null) }
    val display = localOrder ?: entries
    val listState = rememberLazyListState()
    val reorderState = rememberReorderState(listState) { fromKey, toKey ->
        val from = display.indexOfFirst { it.id == fromKey }
        val to = display.indexOfFirst { it.id == toKey }
        if (from < 0 || to < 0 || from == to) return@rememberReorderState
        val reordered = display.toMutableList().apply { add(to, removeAt(from)) }
            .mapIndexed { index, e -> e.copy(sortOrder = index) }
        localOrder = reordered
        scope.launch {
            app.database.smartRouteDao().updateAll(reordered)
            localOrder = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能路由") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddModels = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加候选模型")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("聚合模型 smartAi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "把候选模型整合成一个 smartAi。聊天时按下方顺序依次尝试：某模型失败" +
                                "或 ${timeoutSeconds}s 内无输出，自动切换下一个；已开始输出后失败则不再切换。" +
                                "整条候选链轮巡 $maxRounds 轮仍全部失败，才提示全部候选不可用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("首字超时", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showTimeoutEdit = true }) { Text("$timeoutSeconds 秒  修改") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("轮巡次数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showRoundsEdit = true }) { Text("$maxRounds 轮  修改") }
                        }
                    }
                }
            }

            if (display.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有候选模型", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "添加模型后即可在聊天中选择 smartAi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        Button(onClick = { showAddModels = true }) { Text("添加候选模型") }
                    }
                }
            } else {
                item(key = "list_header") {
                    Text(
                        "回退顺序（长按拖动排序，左滑条目可禁用/移除）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(display, key = { it.id }) { entry ->
                    SmartRouteRow(
                        entry = entry,
                        providers = providers,
                        models = models,
                        reorderState = reorderState,
                        modifier = Modifier.animateItem(),
                        onToggle = { nv ->
                            scope.launch { app.database.smartRouteDao().update(entry.copy(enabled = nv)) }
                        },
                        onRemove = {
                            scope.launch { app.database.smartRouteDao().delete(entry) }
                        },
                    )
                }
            }
            item(key = "footer") { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showTimeoutEdit) {
        TextFieldDialog(
            title = "首字超时（秒）",
            initial = timeoutSeconds.toString(),
            onDismiss = { showTimeoutEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 5 || n > 300 -> "范围 5 ~ 300 秒"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartFirstTokenTimeout(it) }
                showTimeoutEdit = false
            },
        )
    }
    if (showRoundsEdit) {
        TextFieldDialog(
            title = "轮巡次数",
            initial = maxRounds.toString(),
            onDismiss = { showRoundsEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 1 || n > 5 -> "范围 1 ~ 5 轮"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartRouteMaxRounds(it) }
                showRoundsEdit = false
            },
        )
    }
    if (showAddModels) {
        AddCandidatesDialog(
            candidates = availableCandidates(providers, models, display),
            onDismiss = { showAddModels = false },
            onConfirm = { selected ->
                scope.launch {
                    val base = (app.database.smartRouteDao().maxSortOrder() ?: -1) + 1
                    app.database.smartRouteDao().insertAll(
                        selected.mapIndexed { i, m ->
                            SmartRouteEntity(
                                providerId = m.providerId,
                                modelName = m.name,
                                sortOrder = base + i,
                            )
                        }
                    )
                    showAddModels = false
                }
            },
        )
    }
}

/** 可加入智能路由的候选：启用中的 chat 模型或具备图像输出能力的模型（如 Gemini 图像生成），且尚未在列表里 */
private fun availableCandidates(
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    entries: List<SmartRouteEntity>,
): List<ModelEntity> {
    val existing = entries.map { it.providerId to it.modelName }.toSet()
    return models.filter { m ->
        (m.type == ModelType.CHAT || m.outputImage) && (m.providerId to m.name) !in existing
    }
}

@Composable
private fun SmartRouteRow(
    entry: SmartRouteEntity,
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    reorderState: ReorderState,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val provider = providers.firstOrNull { it.id == entry.providerId }
    val model = models.firstOrNull { it.providerId == entry.providerId && it.name == entry.modelName }
    val displayName = model?.alias?.takeIf { it.isNotBlank() } ?: entry.modelName

    SwipeRevealActions(
        modifier = modifier,
        actions = {
            SwipeActionButton(
                text = if (entry.enabled) "禁用" else "启用",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            ) { onToggle(!entry.enabled) }
            SwipeActionButton(
                text = "移除",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            ) { onRemove() }
        },
    ) {
        Card(modifier = Modifier.reorderDragged(reorderState, entry.id)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${provider?.alias ?: "?"} / $displayName",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!entry.enabled) {
                            Text(
                                "  已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        entry.modelName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.reorderHandle(reorderState, entry.id),
                )
            }
        }
    }
}

/** 左滑拉出操作按钮的容器 */
@Composable
private fun SwipeRevealActions(
    modifier: Modifier = Modifier,
    actionWidth: Dp = SwipeActionWidth,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionPx = with(density) { actionWidth.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val animatable = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun settle() {
        val target = if (offset < -actionPx / 2f) -actionPx else 0f
        scope.launch {
            animatable.snapTo(offset)
            animatable.animateTo(target) { offset = value }
        }
    }

    Box(modifier.fillMaxWidth()) {
        // 背后的操作按钮（右对齐）
        Row(
            Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
        // 前层内容：跟手横向位移
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(actionPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offset = (offset + dragAmount).coerceIn(-actionPx, 0f)
                        },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun RowScope.SwipeActionButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        onClick = onClick,
        modifier = Modifier
            .width(SwipeActionEach)
            .fillMaxHeight(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 20.dp),
        )
    }
}

@Composable
private fun AddCandidatesDialog(
    candidates: List<ModelEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<ModelEntity>) -> Unit,
) {
    val checked = remember(candidates) {
        mutableStateOf(candidates.associate { (it.providerId to it.name) to false })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(candidates.filter { checked.value[it.providerId to it.name] == true })
                },
                enabled = candidates.isNotEmpty(),
            ) { Text("添加所选") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加候选模型") },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    "没有可添加的模型：候选需为启用中的 chat 或图像生成模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    candidates.forEach { m ->
                        val key = m.providerId to m.name
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked.value[key] == true,
                                onCheckedChange = { v -> checked.value = checked.value + (key to v) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.alias?.takeIf { it.isNotBlank() }?.let { "$it（${m.name}）" } ?: m.name,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
