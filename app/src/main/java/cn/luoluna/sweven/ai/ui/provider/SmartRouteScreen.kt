package cn.luoluna.sweven.ai.ui.provider

import cn.luoluna.sweven.ai.data.db.ref

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteMemberEntity
import cn.luoluna.sweven.ai.data.repo.CapabilityGap
import cn.luoluna.sweven.ai.data.repo.ModelIdGrouper
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.data.repo.capabilityGaps
import cn.luoluna.sweven.ai.data.repo.intersectCapability
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.ModeSelectDialog
import cn.luoluna.sweven.ai.ui.common.ReorderState
import cn.luoluna.sweven.ai.ui.common.SwipeAction
import cn.luoluna.sweven.ai.ui.common.SwipeRevealActions
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.normalizeModelId
import cn.luoluna.sweven.ai.ui.common.rememberReorderState
import cn.luoluna.sweven.ai.ui.common.reorderDragged
import cn.luoluna.sweven.ai.ui.common.reorderHandle
import kotlinx.coroutines.launch

/**
 * 智能路由配置：路由参数（响应模式/各档首字超时/轮巡/冷却）+ 路由分组
 * （flux-xxx 组，组内自动切换；长按拖动排序，侧滑可禁用/删除）。
 * flux-auto 聚合路由不再是这里配置的候选链——它固定聚合全部启用模型（厂商顺序×模型顺序），
 * 因此本页不再有它的开关与说明。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SmartRouteScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val providers by remember { app.database.providerDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val models by remember { app.database.modelDao().observeEnabled() }
        .collectAsStateWithLifecycle(emptyList())
    // 路由分组：组 + 全量成员（UI 侧按组过滤/排序）
    val groups by remember { app.database.smartRouteGroupDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val members by remember { app.database.smartRouteMemberDao().observeAll() }
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
    var showAddGroup by remember { mutableStateOf(false) }
    var showParamsSheet by remember { mutableStateOf(false) }
    // 分组添加成员半窗的目标分组
    var addMembersGroup by remember { mutableStateOf<SmartRouteGroupEntity?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<SmartRouteGroupEntity?>(null) }
    // 短板明细半窗目标分组；「调整能力」打开模型编辑器的目标模型
    var gapSheetGroup by remember { mutableStateOf<SmartRouteGroupEntity?>(null) }
    var editGapModel by remember { mutableStateOf<ModelEntity?>(null) }
    // 路由分组：手风琴展开状态（一次最多展开一个，默认收起）
    var expandedGroupId by remember { mutableStateOf<Long?>(null) }

    val listState = rememberLazyListState()
    // 侧滑操作：同一时间至多一行滑开（分组卡 g<id> / 成员 m<id> 共用），滚动列表时收回
    var openSwipeKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) openSwipeKey = null
    }
    // 单列表单拖序状态：key 两类——分组卡 g<id>、分组成员 m<id>，换位时按类型分发
    val reorderState = rememberReorderState(
        listState,
        onDragStart = { key ->
            // 拖动分组卡片：若处于展开态先折叠——成员行是独立 item，不折叠会与拖动中的卡片错位
            if (key is String && key.startsWith("g")) {
                key.removePrefix("g").toLongOrNull()?.let { id ->
                    if (id == expandedGroupId) expandedGroupId = null
                }
            }
        },
    ) { fromKey, toKey ->
        if (fromKey is String && toKey is String) {
            // 分组卡片换位（key 形如 g<id>），sortOrder 重编号落库
            if (fromKey.startsWith("g") && toKey.startsWith("g")) {
                val fromId = fromKey.removePrefix("g").toLongOrNull() ?: return@rememberReorderState
                val toId = toKey.removePrefix("g").toLongOrNull() ?: return@rememberReorderState
                if (fromId == toId) return@rememberReorderState
                val fi = groups.indexOfFirst { it.id == fromId }
                val ti = groups.indexOfFirst { it.id == toId }
                if (fi < 0 || ti < 0) return@rememberReorderState
                val reordered = groups.toMutableList().apply { add(ti, removeAt(fi)) }
                    .mapIndexed { i, g -> g.copy(sortOrder = i) }
                scope.launch { app.database.smartRouteGroupDao().updateAll(reordered) }
                return@rememberReorderState
            }
            // 分组成员换位（仅同组内；key 形如 m<id>）
            if (fromKey.startsWith("m") && toKey.startsWith("m")) {
                val fromId = fromKey.removePrefix("m").toLongOrNull() ?: return@rememberReorderState
                val toId = toKey.removePrefix("m").toLongOrNull() ?: return@rememberReorderState
                val fromMember = members.firstOrNull { it.id == fromId } ?: return@rememberReorderState
                val toMember = members.firstOrNull { it.id == toId } ?: return@rememberReorderState
                if (fromMember.groupId != toMember.groupId || fromId == toId) return@rememberReorderState
                val groupMembers = members.filter { it.groupId == fromMember.groupId }.toMutableList()
                val fi = groupMembers.indexOfFirst { it.id == fromId }
                val ti = groupMembers.indexOfFirst { it.id == toId }
                if (fi < 0 || ti < 0) return@rememberReorderState
                groupMembers.add(ti, groupMembers.removeAt(fi))
                val reordered = groupMembers.mapIndexed { i, m -> m.copy(sortOrder = i) }
                scope.launch { app.database.smartRouteMemberDao().updateAll(reordered) }
            }
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
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 全局路由参数（响应模式/超时/冷却/轮巡，对聚合模型与路由分组同时生效）
            RouteParamsSummaryCard(
                summary = "${SmartModes.labelOf(responseMode)} · 首字${timeoutSeconds}s · " +
                    "轮巡${maxRounds}轮 · 冷却${cooldownThreshold}次/${cooldownSeconds}s",
                onEdit = { showParamsSheet = true },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------- 路由分组 ----------------
                item(key = "groups_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("路由分组", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "同族模型（跨厂商/版本号）聚为一组，对外暴露为 flux-模型名，组内自动切换；" +
                                    "也可自定义组名建组后自由添加成员",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showAddGroup = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建路由分组")
                        }
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "groups_empty") {
                        Text(
                            "还没有路由分组。点上方 + 搜索模型 id 快捷归组，或输入自定义组名建组后自由添加成员。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
                    groups.forEach { group ->
                        val groupMembers = members.filter { it.groupId == group.id }
                        // 短板口径只统计「启用且厂商未停用」的成员——被禁成员不参与交集，也不拖低短板
                        val activePairs = groupMembers
                            .filter { it.enabled && it.providerId in enabledProviderIds }
                            .mapNotNull { m ->
                                models.firstOrNull { it.providerId == m.providerId && it.ref == m.modelName }
                                    ?.let { m to it }
                            }
                        val gaps = capabilityGaps(activePairs.map { it.second })
                        val expandedNow = group.id == expandedGroupId
                        val card: @Composable LazyItemScope.() -> Unit = {
                            RouteGroupCard(
                                group = group,
                                memberCount = groupMembers.size,
                                memberModels = activePairs.map { it.second },
                                gapText = gaps.takeIf { it.isNotEmpty() }?.let { gapLine(it, providers) },
                                onShowGaps = { gapSheetGroup = group },
                                expanded = expandedNow,
                                onToggleExpand = { expandedGroupId = if (expandedNow) null else group.id },
                                isOpen = openSwipeKey == "g${group.id}",
                                onOpenChange = { open -> openSwipeKey = if (open) "g${group.id}" else null },
                                onToggle = { nv ->
                                    scope.launch { app.database.smartRouteGroupDao().update(group.copy(enabled = nv)) }
                                },
                                onDelete = { deleteGroupTarget = group },
                                reorderState = reorderState,
                                modifier = Modifier.animateItem(),
                            )
                        }
                        // 展开中的分组卡吸顶：成员行从其下方穿过，翻阅组内成员时组名常驻
                        if (expandedNow) {
                            stickyHeader(key = "g${group.id}", content = card)
                        } else {
                            item(key = "g${group.id}", content = card)
                        }
                        // 成员行作为独立 LazyColumn item：拖拽排序与侧滑互斥才能命中可见项
                        if (expandedNow) {
                            items(groupMembers, key = { "m${it.id}" }) { member ->
                                RouteMemberRow(
                                    member = member,
                                    providers = providers,
                                    models = models,
                                    gaps = gaps,
                                    reorderState = reorderState,
                                    isOpen = openSwipeKey == "m${member.id}",
                                    onOpenChange = { open -> openSwipeKey = if (open) "m${member.id}" else null },
                                    onToggle = { nv ->
                                        scope.launch {
                                            app.database.smartRouteMemberDao().update(member.copy(enabled = nv))
                                        }
                                    },
                                    onRemove = {
                                        scope.launch { app.database.smartRouteMemberDao().delete(member) }
                                    },
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .animateItem(),
                                )
                            }
                            item(key = "m_add_${group.id}") {
                                AddMemberRow(
                                    onClick = { addMembersGroup = group },
                                    modifier = Modifier
                                        .padding(start = 24.dp)
                                        .animateItem(),
                                )
                            }
                        }
                    }
                }
                item(key = "footer") { Spacer(Modifier.height(12.dp)) }
            }
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
    if (showParamsSheet) {
        RouteParamsSheet(
            responseMode = responseMode,
            fastMs = fastMs,
            leisureSeconds = leisureSeconds,
            timeoutSeconds = timeoutSeconds,
            maxRounds = maxRounds,
            cooldownThreshold = cooldownThreshold,
            cooldownSeconds = cooldownSeconds,
            onDismiss = { showParamsSheet = false },
            onEditMode = { showModeDialog = true },
            onEditFast = { showFastEdit = true },
            onEditLeisure = { showLeisureEdit = true },
            onEditTimeout = { showTimeoutEdit = true },
            onEditRounds = { showRoundsEdit = true },
            onEditCooldownThreshold = { showCooldownThresholdEdit = true },
            onEditCooldownSeconds = { showCooldownSecondsEdit = true },
        )
    }
    if (showAddGroup) {
        AddGroupSheet(
            providers = providers,
            models = models,
            existingKeys = groups.map { it.groupKey }.toSet(),
            onDismiss = { showAddGroup = false },
            onCreate = { key, selected ->
                scope.launch {
                    val gid = app.database.smartRouteGroupDao().insert(
                        SmartRouteGroupEntity(groupKey = key, sortOrder = groups.size)
                    )
                    app.database.smartRouteMemberDao().insertAll(
                        selected.mapIndexed { i, m ->
                            SmartRouteMemberEntity(
                                groupId = gid,
                                providerId = m.providerId,
                                modelName = m.ref,
                                sortOrder = i,
                            )
                        }
                    )
                    showAddGroup = false
                }
            },
        )
    }
    deleteGroupTarget?.let { group ->
        ConfirmDialog(
            title = "删除路由分组",
            text = "删除分组「${group.routeName}」及其全部成员？模型与厂商配置不受影响。",
            onDismiss = { deleteGroupTarget = null },
            onConfirm = {
                scope.launch { app.database.smartRouteGroupDao().delete(group) }
                deleteGroupTarget = null
            },
        )
    }

    // 分组添加成员半窗：同族匹配预选 + 全量可勾，确认后追加到组内末尾
    addMembersGroup?.let { g ->
        AddGroupMembersSheet(
            group = g,
            providers = providers,
            models = models,
            existingMembers = members.filter { it.groupId == g.id },
            onDismiss = { addMembersGroup = null },
            onConfirm = { selected ->
                scope.launch {
                    val base = members.filter { it.groupId == g.id }.maxOfOrNull { it.sortOrder } ?: -1
                    app.database.smartRouteMemberDao().insertAll(
                        selected.mapIndexed { i, m ->
                            SmartRouteMemberEntity(
                                groupId = g.id,
                                providerId = m.providerId,
                                modelName = m.ref,
                                sortOrder = base + 1 + i,
                            )
                        }
                    )
                    addMembersGroup = null
                }
            },
        )
    }

    // 分组短板明细半窗（禁用成员后 gaps 随 Flow 即时重算）
    gapSheetGroup?.let { g ->
        val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
        val groupMembers = members.filter { it.groupId == g.id }
        val activePairs = groupMembers
            .filter { it.enabled && it.providerId in enabledProviderIds }
            .mapNotNull { m ->
                models.firstOrNull { it.providerId == m.providerId && it.ref == m.modelName }
                    ?.let { m to it }
            }
        GroupGapSheet(
            group = g,
            gaps = capabilityGaps(activePairs.map { it.second }),
            members = groupMembers,
            providers = providers,
            onDismiss = { gapSheetGroup = null },
            onEditModel = { m ->
                editGapModel = m
                gapSheetGroup = null
            },
            onDisableMember = { m ->
                scope.launch { app.database.smartRouteMemberDao().update(m.copy(enabled = false)) }
            },
        )
    }

    // 短板成员「调整能力」：复用模型编辑器改能力标记
    editGapModel?.let { m ->
        ModelEditorSheet(
            providerId = m.providerId,
            isGeminiPreset = providers.firstOrNull { it.id == m.providerId }?.presetId == "gemini",
            existing = m,
            onDismiss = { editGapModel = null },
            onSave = { nm ->
                scope.launch {
                    app.database.modelDao().update(nm)
                    editGapModel = null
                }
            },
        )
    }
}

@Composable
private fun RouteParamsSummaryCard(
    summary: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("路由参数", style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("修改") }
        }
    }
}

/** 路由参数半窗：响应模式/各档首字超时/轮巡/冷却集中编辑（对本机聊天与局域网的分组、聚合模型同时生效） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteParamsSheet(
    responseMode: String,
    fastMs: Int,
    leisureSeconds: Int,
    timeoutSeconds: Int,
    maxRounds: Int,
    cooldownThreshold: Int,
    cooldownSeconds: Int,
    onDismiss: () -> Unit,
    onEditMode: () -> Unit,
    onEditFast: () -> Unit,
    onEditLeisure: () -> Unit,
    onEditTimeout: () -> Unit,
    onEditRounds: () -> Unit,
    onEditCooldownThreshold: () -> Unit,
    onEditCooldownSeconds: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("路由参数", style = MaterialTheme.typography.titleMedium)
            Text(
                "对本机聊天与局域网网关的路由分组、聚合模型同时生效；" +
                    "局域网设备的响应模式在「局域网 → 已授权设备」页按设备单独设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("响应模式（本机聊天）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "极速/聊天/随缘三档，决定等待首个字输出多久就切换候选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEditMode) {
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
                    TextButton(onClick = onEditFast) { Text("${fastMs}ms  修改") }
                }
            }
            if (responseMode == SmartModes.LEISURE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("随缘首字超时", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onEditLeisure) { Text("$leisureSeconds 秒  修改") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("聊天档超时", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditTimeout) { Text("$timeoutSeconds 秒  修改") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("轮巡次数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditRounds) { Text("$maxRounds 轮  修改") }
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
                TextButton(onClick = onEditCooldownThreshold) { Text("$cooldownThreshold 次  修改") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("冷却时长", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditCooldownSeconds) { Text("$cooldownSeconds 秒  修改") }
            }
        }
    }
}

/** 路由分组卡片：flux-xxx 组名 + 成员数与能力摘要 + 展开箭头；侧滑 禁用/启用、删除（删除有确认）；拖动手柄排序 */
@Composable
private fun RouteGroupCard(
    group: SmartRouteGroupEntity,
    memberCount: Int,
    memberModels: List<ModelEntity>,
    gapText: String?,
    onShowGaps: () -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    reorderState: ReorderState,
    modifier: Modifier = Modifier,
) {
    SwipeRevealActions(
        modifier = modifier.reorderDragged(reorderState, "g${group.id}"),
        actions = listOf(
            SwipeAction(
                label = if (group.enabled) "禁用" else "启用",
                icon = if (group.enabled) Icons.Filled.Block else Icons.Filled.PlayArrow,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onToggle(!group.enabled) },
            ),
            SwipeAction(
                label = "删除",
                icon = Icons.Filled.Delete,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onDelete,
            ),
        ),
        isOpen = isOpen,
        onOpenChange = onOpenChange,
    ) {
        Card {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Filled.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.routeName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (!group.enabled) {
                            Text(
                                "  已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        groupSummary(memberCount, memberModels),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (gapText != null) {
                        Text(
                            "短板：$gapText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable(onClick = onShowGaps)
                                .padding(top = 2.dp),
                        )
                    }
                }
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .reorderHandle(reorderState, "g${group.id}")
                        .padding(end = 4.dp),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起成员" else "展开成员",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 分组摘要：N 个成员 · 文图→文图（启用成员能力交集，短板口径） */
private fun groupSummary(memberCount: Int, memberModels: List<ModelEntity>): String {
    val cap = intersectCapability(memberModels)
    fun mods(text: Boolean, image: Boolean, video: Boolean) = buildList {
        if (text) add("文")
        if (image) add("图")
        if (video) add("视")
    }.joinToString("").ifEmpty { "—" }
    return "$memberCount 个成员 · " +
        "${mods(cap.inputText, cap.inputImage, cap.inputVideo)}→" +
        mods(cap.outputText, cap.outputImage, cap.outputVideo)
}

/** 卡片短板一行：图像输入←硅基/v3 · 工具调用←OpenAI/ds */
private fun gapLine(gaps: List<CapabilityGap>, providers: List<ProviderEntity>): String =
    gaps.joinToString(" · ") { gap ->
        gap.label + "←" + gap.lacking.joinToString("、") { m ->
            val p = providers.firstOrNull { it.id == m.providerId }
            "${p?.alias ?: "?"}/${m.alias?.takeIf { it.isNotBlank() } ?: m.ref}"
        }
    }

/** 分组短板明细半窗：逐项能力列出拖后腿的成员，可直接禁用成员或打开模型编辑器补齐能力 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupGapSheet(
    group: SmartRouteGroupEntity,
    gaps: List<CapabilityGap>,
    members: List<SmartRouteMemberEntity>,
    providers: List<ProviderEntity>,
    onDismiss: () -> Unit,
    onEditModel: (ModelEntity) -> Unit,
    onDisableMember: (SmartRouteMemberEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("短板明细 · ${group.routeName}", style = MaterialTheme.typography.titleMedium)
            Text(
                "对外能力取启用成员的交集：某项只有全员支持才会声明。" +
                    "禁用短板成员或点「调整能力」补齐后，交集自动回升。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (gaps.isEmpty()) {
                Text(
                    "没有短板：启用成员能力一致，对外按成员能力声明",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            gaps.forEach { gap ->
                Text(
                    gap.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                gap.lacking.forEach { m ->
                    val p = providers.firstOrNull { it.id == m.providerId }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${p?.alias ?: "?"} / ${m.alias?.takeIf { it.isNotBlank() } ?: m.ref}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                m.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                        }
                        TextButton(onClick = { onEditModel(m) }) { Text("调整能力") }
                        TextButton(
                            onClick = {
                                members.firstOrNull {
                                    it.providerId == m.providerId && it.modelName == m.ref
                                }?.let(onDisableMember)
                            },
                        ) { Text("禁用", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

/** 分组成员行：侧滑 禁用/启用、移除；长按手柄组内拖序（仅同组换位）；属于短板时标注缺项 */
@Composable
private fun RouteMemberRow(
    member: SmartRouteMemberEntity,
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    gaps: List<CapabilityGap>,
    reorderState: ReorderState,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = providers.firstOrNull { it.id == member.providerId }
    val model = models.firstOrNull { it.providerId == member.providerId && it.ref == member.modelName }
    val displayName = model?.alias?.takeIf { it.isNotBlank() } ?: member.modelName
    val health = SmartHealth.info(member.providerId, member.modelName)
    // 该成员拖低了哪些能力项（仅启用成员参与短板计算）
    val gapLabels = if (member.enabled && model != null) {
        gaps.filter { model in it.lacking }.map { it.label }
    } else {
        emptyList()
    }

    SwipeRevealActions(
        modifier = modifier.reorderDragged(reorderState, "m${member.id}"),
        actions = listOf(
            SwipeAction(
                label = if (member.enabled) "禁用" else "启用",
                icon = if (member.enabled) Icons.Filled.Block else Icons.Filled.PlayArrow,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onToggle(!member.enabled) },
            ),
            SwipeAction(
                label = "移除",
                icon = Icons.Filled.Delete,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onRemove,
            ),
        ),
        isOpen = isOpen,
        onOpenChange = onOpenChange,
    ) {
        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${provider?.alias ?: "?"} / $displayName",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .basicMarquee(),
                        )
                        if (!member.enabled) {
                            Text(
                                "  已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (member.enabled && health.cooling) {
                            Text(
                                "  冷却中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (gapLabels.isNotEmpty()) {
                            Text(
                                "  短板·缺${gapLabels.joinToString("、")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        member.modelName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    if (model != null) {
                        ModelBadgesRow(model, Modifier.padding(top = 2.dp))
                    }
                }
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "长按拖动排序",
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.reorderHandle(reorderState, "m${member.id}"),
                )
            }
        }
    }
}

/**
 * 新建路由分组：搜索模型 id → 一次匹配搜索汇总各厂商同族模型（键更长者优先）→ 预览成员与能力一致性 → 确认成组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGroupSheet(
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (groupKey: String, members: List<ModelEntity>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // 自定义组名建组：无需同名模型，允许 0 成员创建后自由添加
    var customMode by remember { mutableStateOf(false) }
    val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
    // 可入组模型：启用厂商下的 chat 模型或图像生成模型
    val eligible = models.filter {
        (it.type == ModelType.CHAT || it.outputImage) && it.providerId in enabledProviderIds
    }
    val suggestions = ModelIdGrouper.suggestKeys(query, eligible, existingKeys).take(8)
    val groupMembers = selectedKey?.let { ModelIdGrouper.membersOf(it, eligible) } ?: emptyList()
    // 输入词清洗为合法组名：无同族匹配时也可据此建自定义组
    val customKey = sanitizeGroupKey(query)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("新建路由分组", style = MaterialTheme.typography.titleMedium)
            Text(
                "搜索模型 id 快捷归组（同族自动聚并），或输入自定义组名建组后自由添加成员；" +
                    "对外暴露为 flux-组名，组内按成员顺序自动切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 内容区占满搜索框之上的空间，搜索框固定贴底（键盘弹出不遮挡结果）
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            ) {
                if (selectedKey == null) {
                suggestions.forEach { key ->
                    val groupModels = ModelIdGrouper.membersOf(key, eligible)
                    // 是否有成员靠备注（别名）归组：别名基名命中而 id 基名不命中
                    val byRemark = groupModels.any { m ->
                        val aliasKey = m.alias?.takeIf { it.isNotBlank() }?.let { ModelIdGrouper.baseKey(it) }
                        aliasKey == key && ModelIdGrouper.baseKey(m.name) != key
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = key }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "flux-$key",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                "${groupModels.size} 个同族模型" + if (byRemark) " · 含备注匹配" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (query.isNotBlank() && suggestions.isEmpty() &&
                    (customKey.isEmpty() || customKey in existingKeys)
                ) {
                    Text(
                        "没有匹配的同族模型（请确认模型已在厂商页添加并启用，或改用自定义组名）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                // 自定义组名建组：无同族匹配也能建（允许 0 成员，创建后自由添加）
                if (customKey.isNotEmpty() && customKey !in existingKeys) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                customMode = true
                                selectedKey = customKey
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "flux-$customKey",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                "自定义组名 · 无需同名模型，创建后自由添加成员",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            } else {
                Text(
                    "flux-$selectedKey" + if (customMode) "（自定义组）" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .basicMarquee(),
                )
                Text(
                    if (customMode && groupMembers.isEmpty()) {
                        "0 个成员：先创建空组，之后在分组卡片中「添加成员」自由补齐"
                    } else {
                        "${groupMembers.size} 个成员，按下方顺序作为组内切换优先级"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val previewGaps = capabilityGaps(groupMembers)
                if (previewGaps.isNotEmpty()) {
                    Text(
                        "能力不一致（交集口径下该项不对外声明）：" + previewGaps.joinToString("；") { gap ->
                            gap.label + "：" + gap.lacking.joinToString("、") { m ->
                                val p = providers.firstOrNull { it.id == m.providerId }
                                "${p?.alias ?: "?"}/${m.alias?.takeIf { it.isNotBlank() } ?: m.ref}"
                            } + " 不支持"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                groupMembers.forEach { m ->
                    val p = providers.firstOrNull { it.id == m.providerId }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${p?.alias ?: "?"} / ${m.alias?.takeIf { it.isNotBlank() } ?: m.ref}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                m.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            ModelBadgesRow(m, Modifier.padding(top = 2.dp))
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    TextButton(onClick = {
                        selectedKey = null
                        customMode = false
                    }) { Text("重选") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = customMode || groupMembers.isNotEmpty(),
                        onClick = { onCreate(selectedKey ?: return@Button, groupMembers) },
                    ) { Text("创建分组") }
                }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    selectedKey = null
                    customMode = false
                },
                label = { Text("模型 id，如 deepseek-v4-flash；输入自定义组名可直接建组") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 自定义组名清洗：小写、空白转 -、剔除非法字符；空结果视为无效 */
private fun sanitizeGroupKey(raw: String): String =
    raw.trim().lowercase()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^a-z0-9._-]"), "")

/** 成员列表末尾的「添加成员」入口行 */
@Composable
private fun AddMemberRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "添加成员",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 分组添加成员：上半区「同族匹配」= 归组身份键命中的未入组模型（默认预选，一键补齐）；
 * 下半区「全部模型」= 可搜索/按厂商过滤的完整候选，手动勾选任意模型入组（假模型/特殊命名兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGroupMembersSheet(
    group: SmartRouteGroupEntity,
    providers: List<ProviderEntity>,
    models: List<ModelEntity>,
    existingMembers: List<SmartRouteMemberEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<ModelEntity>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }

    val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
    val eligible = models.filter {
        (it.type == ModelType.CHAT || it.outputImage) && it.providerId in enabledProviderIds
    }
    val existingKeys = existingMembers.map { it.providerId to it.modelName }.toSet()
    val candidates = eligible.filter { (it.providerId to it.ref) !in existingKeys }
    // 同族匹配（按归组身份键，含备注匹配）：默认预选
    val matched = ModelIdGrouper.membersOf(group.groupKey, candidates)
    val matchedKeys = matched.map { it.providerId to it.ref }.toSet()
    var checked by remember { mutableStateOf(matchedKeys) }

    val providerAliases = candidates.mapNotNull { m ->
        providers.firstOrNull { it.id == m.providerId }?.alias
    }.distinct()
    val q = normalizeModelId(query)
    fun pass(m: ModelEntity): Boolean {
        val alias = providers.firstOrNull { it.id == m.providerId }?.alias
        return (providerFilter == null || alias == providerFilter) &&
            (q.isEmpty() || normalizeModelId("${m.ref} ${m.name} ${m.alias.orEmpty()}").contains(q))
    }
    val matchedFiltered = matched.filter { pass(it) }
    val others = candidates
        .filter { (it.providerId to it.ref) !in matchedKeys }
        .filter { pass(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "添加成员 · ${group.routeName}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "同族匹配的模型已预选，确认即一并加入；也可在全部模型里手动勾选任意模型入组" +
                "（成员顺序即组内切换优先级，入组后可在成员列表长按拖动调整）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp),
        )
        if (providerAliases.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
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
        if (matchedFiltered.isEmpty() && others.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (candidates.isEmpty()) "没有可添加的模型：候选需为启用中的 chat 或图像生成模型，且尚未入组"
                    else "没有匹配的模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (matchedFiltered.isNotEmpty()) {
                    item(key = "sec_matched") {
                        Text(
                            "同族匹配（已预选 ${matched.size} 个）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(matchedFiltered, key = { "s_${it.providerId}_${it.ref}" }) { m ->
                        MemberCheckRow(
                            model = m,
                            providers = providers,
                            checked = checked,
                            onToggle = { v ->
                                val k = m.providerId to m.ref
                                checked = if (v) checked + k else checked - k
                            },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "sec_all") {
                        Text(
                            "全部模型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(others, key = { "a_${it.providerId}_${it.ref}" }) { m ->
                        MemberCheckRow(
                            model = m,
                            providers = providers,
                            checked = checked,
                            onToggle = { v ->
                                val k = m.providerId to m.ref
                                checked = if (v) checked + k else checked - k
                            },
                        )
                    }
                }
                item(key = "footer") { Spacer(Modifier.height(8.dp)) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索模型") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
                onClick = { onConfirm(candidates.filter { (it.providerId to it.ref) in checked }) },
                enabled = checked.isNotEmpty(),
            ) {
                Text(if (checked.isEmpty()) "添加所选" else "添加所选（${checked.size}）")
            }
        }
    }
}

@Composable
private fun MemberCheckRow(
    model: ModelEntity,
    providers: List<ProviderEntity>,
    checked: Set<Pair<Long, String>>,
    onToggle: (Boolean) -> Unit,
) {
    val p = providers.firstOrNull { it.id == model.providerId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Checkbox(
            checked = (model.providerId to model.ref) in checked,
            onCheckedChange = onToggle,
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                "${p?.alias ?: "?"} / ${model.alias?.takeIf { it.isNotBlank() } ?: model.ref}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                model.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            ModelBadgesRow(model, Modifier.padding(top = 2.dp))
        }
    }
}
