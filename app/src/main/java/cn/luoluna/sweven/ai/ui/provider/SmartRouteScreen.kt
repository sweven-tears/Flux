package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteEntity
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.ModeSelectDialog
import cn.luoluna.sweven.ai.ui.common.ReorderState
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.normalizeModelId
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
    val cooldownThreshold by remember { app.settings.smartCooldownThreshold }
        .collectAsStateWithLifecycle(2)
    val cooldownSeconds by remember { app.settings.smartCooldownSeconds }
        .collectAsStateWithLifecycle(60)
    val responseMode by remember { app.settings.smartResponseMode }
        .collectAsStateWithLifecycle(SmartModes.CHAT)
    val fastMs by remember { app.settings.smartFastFirstTokenMs }
        .collectAsStateWithLifecycle(2000)
    val leisureSeconds by remember { app.settings.smartLeisureFirstTokenSeconds }
        .collectAsStateWithLifecycle(120)

    var showTimeoutEdit by remember { mutableStateOf(false) }
    var showRoundsEdit by remember { mutableStateOf(false) }
    var showCooldownThresholdEdit by remember { mutableStateOf(false) }
    var showCooldownSecondsEdit by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showFastEdit by remember { mutableStateOf(false) }
    var showLeisureEdit by remember { mutableStateOf(false) }
    var showAddModels by remember { mutableStateOf(false) }
    var showCategoryView by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showCategoryView = true }) {
                        Icon(Icons.Filled.Category, contentDescription = "分类查看")
                    }
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
                            "把候选模型整合成一个 smartAi。按下方顺序依次尝试：某模型失败或超时无输出，" +
                                "自动切换下一个；已开始输出后失败则不再切换。整条候选链轮巡 $maxRounds 轮仍全部失败，" +
                                "才提示全部候选不可用。极速模式首轮只把请求发给实测速度达标的候选（超时候选只记「慢」），" +
                                "一轮无成功再放宽到聊天档；连续失败的候选进入冷却垫底。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("响应模式（本机聊天）", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "局域网设备的模式在「局域网」页按设备单独设置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showModeDialog = true }) {
                                Text("${SmartModes.labelOf(responseMode)}  修改")
                            }
                        }
                        if (responseMode == SmartModes.FAST) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("极速首字超时", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "首轮门槛，超时候选只记「慢」，下轮放宽后仍会尝试",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { showFastEdit = true }) { Text("${fastMs}ms  修改") }
                            }
                        }
                        if (responseMode == SmartModes.LEISURE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("随缘首字超时", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showLeisureEdit = true }) { Text("$leisureSeconds 秒  修改") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("聊天档超时", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showTimeoutEdit = true }) { Text("$timeoutSeconds 秒  修改") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("轮巡次数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showRoundsEdit = true }) { Text("$maxRounds 轮  修改") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("冷却阈值", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "候选连续失败达到次数后进入冷却，排序时垫底兜底",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showCooldownThresholdEdit = true }) { Text("$cooldownThreshold 次  修改") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("冷却时长", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showCooldownSecondsEdit = true }) { Text("$cooldownSeconds 秒  修改") }
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
    if (showModeDialog) {
        ModeSelectDialog(
            title = "响应模式（本机聊天）",
            current = responseMode,
            onDismiss = { showModeDialog = false },
            onSelect = {
                app.settings.setSmartResponseMode(it)
                showModeDialog = false
            },
        )
    }
    if (showFastEdit) {
        TextFieldDialog(
            title = "极速首字超时（毫秒）",
            initial = fastMs.toString(),
            onDismiss = { showFastEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 500 || n > 5000 -> "范围 500 ~ 5000 毫秒"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartFastFirstToken(it) }
                showFastEdit = false
            },
        )
    }
    if (showLeisureEdit) {
        TextFieldDialog(
            title = "随缘首字超时（秒）",
            initial = leisureSeconds.toString(),
            onDismiss = { showLeisureEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 30 || n > 600 -> "范围 30 ~ 600 秒"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartLeisureFirstToken(it) }
                showLeisureEdit = false
            },
        )
    }
    if (showCooldownThresholdEdit) {
        TextFieldDialog(
            title = "冷却阈值（连续失败次数）",
            initial = cooldownThreshold.toString(),
            onDismiss = { showCooldownThresholdEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 1 || n > 5 -> "范围 1 ~ 5 次"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartCooldownThreshold(it) }
                showCooldownThresholdEdit = false
            },
        )
    }
    if (showCooldownSecondsEdit) {
        TextFieldDialog(
            title = "冷却时长（秒）",
            initial = cooldownSeconds.toString(),
            onDismiss = { showCooldownSecondsEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 10 || n > 600 -> "范围 10 ~ 600 秒"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { app.settings.setSmartCooldownSeconds(it) }
                showCooldownSecondsEdit = false
            },
        )
    }
    if (showCategoryView) {
        CategoryViewSheet(
            entries = display,
            providers = providers,
            models = models,
            onDismiss = { showCategoryView = false },
        )
    }
    if (showAddModels) {
        AddCandidatesSheet(
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
): List<Pair<ProviderEntity, ModelEntity>> {
    val existing = entries.map { it.providerId to it.modelName }.toSet()
    return models.filter { m ->
        (m.type == ModelType.CHAT || m.outputImage) && (m.providerId to m.name) !in existing
    }.mapNotNull { m -> providers.firstOrNull { it.id == m.providerId }?.let { it to m } }
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
    // 进程内健康度快照（静态读取，进页面时可见，不实时刷新）
    val health = SmartHealth.info(entry.providerId, entry.modelName)

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
                        if (entry.enabled && health.cooling) {
                            Text(
                                "  冷却中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (entry.enabled && !health.cooling && health.consecutiveFails > 0) {
                            Text(
                                "  连续失败 ${health.consecutiveFails} 次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
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
                    if (health.firstTokenEwmaMs != null) {
                        Text(
                            "首字 ~${health.firstTokenEwmaMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

private data class CandidateRow(
    val provider: ProviderEntity,
    val model: ModelEntity,
    val showHeader: Boolean,
)

/** 添加候选模型：搜索 + 厂商过滤 + 分组列表，多选后追加到回退链末尾 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCandidatesSheet(
    candidates: List<Pair<ProviderEntity, ModelEntity>>,
    onDismiss: () -> Unit,
    onConfirm: (List<ModelEntity>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(emptySet<Pair<Long, String>>()) }

    val providerAliases = remember(candidates) { candidates.map { it.first.alias }.distinct() }
    val q = normalizeModelId(query)
    val filtered = candidates.filter { (p, m) ->
        (providerFilter == null || p.alias == providerFilter) &&
            (q.isEmpty() || normalizeModelId("${m.name} ${m.alias.orEmpty()}").contains(q))
    }
    val rows = remember(filtered) {
        buildList {
            var lastAlias: String? = null
            filtered.forEach { (p, m) ->
                add(CandidateRow(p, m, p.alias != lastAlias))
                lastAlias = p.alias
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("添加候选模型", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索模型") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            if (providerAliases.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    FilterChip(
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                        label = { Text("全部厂商") },
                    )
                    providerAliases.forEach { alias ->
                        FilterChip(
                            selected = providerFilter == alias,
                            onClick = { providerFilter = if (providerFilter == alias) null else alias },
                            label = { Text(alias, maxLines = 1) },
                        )
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (candidates.isEmpty()) "没有可添加的模型：候选需为启用中的 chat 或图像生成模型"
                    else "没有匹配的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(rows, key = { "${it.provider.id}_${it.model.name}" }) { row ->
                    if (row.showHeader) {
                        Text(
                            row.provider.alias,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    val key = row.model.providerId to row.model.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Checkbox(
                            checked = key in checked,
                            onCheckedChange = { v -> checked = if (v) checked + key else checked - key },
                        )
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                row.model.alias?.takeIf { it.isNotBlank() }
                                    ?.let { "${it}（${row.model.name}）" } ?: row.model.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            ModelBadgesRow(row.model, Modifier.padding(top = 2.dp))
                        }
                    }
                }
                item(key = "footer") { Spacer(Modifier.height(8.dp)) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onConfirm(
                        filtered.filter { (it.first.id to it.second.name) in checked }.map { it.second },
                    )
                },
                enabled = checked.isNotEmpty(),
            ) {
                Text(if (checked.isEmpty()) "添加所选" else "添加所选（${checked.size}）")
            }
        }
    }
}

private const val CAT_VISION = "视觉理解（文图→文）"
private const val CAT_IMAGE_GEN = "图像生成（文→图）"
private const val CAT_TEXT = "纯文本对话（文→文）"
private const val CAT_OTHER = "其他 / 未标记"

/** 候选按模态类别归类：视觉理解 > 图像生成 > 纯文本 > 其他（未标记或模型行已删除） */
private fun categoryOf(m: ModelEntity?): String = when {
    m == null -> CAT_OTHER
    m.inputImage || m.inputVideo -> CAT_VISION
    m.outputImage || m.outputVideo || m.type == ModelType.IMAGE -> CAT_IMAGE_GEN
    m.inputText || m.outputText -> CAT_TEXT
    else -> CAT_OTHER
}

private data class CategoryEntry(
    /** 在回退列表中的序号（含禁用项，与主列表一致） */
    val index: Int,
    val entry: SmartRouteEntity,
    val provider: ProviderEntity?,
    val model: ModelEntity?,
)

/** 候选分类查看：按模态类别分组只读展示，不影响主列表拖拽排序 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryViewSheet(
    entries: List<SmartRouteEntity>,
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    onDismiss: () -> Unit,
) {
    val groups = remember(entries, providers, models) {
        val all = entries.mapIndexed { i, e ->
            CategoryEntry(
                index = i,
                entry = e,
                provider = providers.firstOrNull { it.id == e.providerId },
                model = models.firstOrNull { it.providerId == e.providerId && it.name == e.modelName },
            )
        }
        listOf(CAT_VISION, CAT_IMAGE_GEN, CAT_TEXT, CAT_OTHER)
            .map { cat -> cat to all.filter { categoryOf(it.model) == cat } }
            .filter { it.second.isNotEmpty() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "候选分类",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
            groups.forEach { (title, list) ->
                item(key = "header_$title") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${list.size} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(list, key = { it.entry.id }) { e ->
                    val displayName =
                        e.model?.alias?.takeIf { it.isNotBlank() } ?: e.entry.modelName
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${e.index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${e.provider?.alias ?: "?"} / $displayName",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                e.entry.modelName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            e.model?.let { ModelBadgesRow(it, Modifier.padding(top = 2.dp)) }
                        }
                        if (!e.entry.enabled) {
                            Text(
                                "已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
