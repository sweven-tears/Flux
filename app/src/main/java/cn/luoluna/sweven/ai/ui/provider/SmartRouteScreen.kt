package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Category
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteMemberEntity
import cn.luoluna.sweven.ai.data.repo.CapabilityGap
import cn.luoluna.sweven.ai.data.repo.ModelIdGrouper
import cn.luoluna.sweven.ai.data.repo.SmartAi
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
 * 智能路由配置：分段切换「路由分组」（flux-xxx 组，组内自动切换）与「聚合模型」
 * （smartAi 回退链：长按拖动排序，侧滑可禁用/移除候选，右上角分类查看/添加候选）。
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
    var showAddModels by remember { mutableStateOf(false) }
    var showCategoryView by remember { mutableStateOf(false) }
    var showAddGroup by remember { mutableStateOf(false) }
    var showParamsSheet by remember { mutableStateOf(false) }
    var deleteGroupTarget by remember { mutableStateOf<SmartRouteGroupEntity?>(null) }
    // 短板明细半窗目标分组；「调整能力」打开模型编辑器的目标模型
    var gapSheetGroup by remember { mutableStateOf<SmartRouteGroupEntity?>(null) }
    var editGapModel by remember { mutableStateOf<ModelEntity?>(null) }
    // 路由分组：组卡片展开状态（默认收起）
    var expandedGroups by remember { mutableStateOf(setOf<Long>()) }

    // 拖拽换位后的本地顺序，落库完成前临时覆盖 Flow 数据
    var localOrder by remember { mutableStateOf<List<SmartRouteEntity>?>(null) }
    val display = localOrder ?: entries
    // 分段内容：0=路由分组 1=聚合模型
    val pagerState = rememberPagerState(pageCount = { 2 })
    // 两个分段各自的列表/拖序状态（key 体系不同：候选 Long、分组成员 m<id>，不能共用）
    val listStateGroups = rememberLazyListState()
    val listStateSmart = rememberLazyListState()
    // 侧滑操作：同一时间至多一行滑开（候选 c<id> / 分组 g<id> / 成员 m<id> 共用），滚动列表时收回
    var openSwipeKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listStateGroups.isScrollInProgress, listStateSmart.isScrollInProgress) {
        if (listStateGroups.isScrollInProgress || listStateSmart.isScrollInProgress) openSwipeKey = null
    }
    val reorderStateSmart = rememberReorderState(listStateSmart) { fromKey, toKey ->
        // smartAi 回退链候选换位
        if (fromKey is Long && toKey is Long) {
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
    }
    val reorderStateGroups = rememberReorderState(listStateGroups) { fromKey, toKey ->
        // 分组成员换位（仅同组内；key 形如 m<id>）
        if (fromKey is String && toKey is String &&
            fromKey.startsWith("m") && toKey.startsWith("m")
        ) {
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
                    // 分类查看/添加候选只服务聚合模型分段
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { showCategoryView = true }) {
                            Icon(Icons.Filled.Category, contentDescription = "分类查看")
                        }
                        IconButton(onClick = { showAddModels = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加候选模型")
                        }
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
            SlidingSegmentedControl(
                options = listOf("路由分组", "聚合模型"),
                pagerState = pagerState,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            // 两分段共用的通用参数（响应模式/超时/冷却/轮巡对路由分组与聚合模型同样生效）
            RouteParamsSummaryCard(
                summary = "${SmartModes.labelOf(responseMode)} · 首字${timeoutSeconds}s · " +
                    "轮巡${maxRounds}轮 · 冷却${cooldownThreshold}次/${cooldownSeconds}s",
                onEdit = { showParamsSheet = true },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                if (page == 0) {
                    // ---------------- 页 0：路由分组（按模型 id 归组，flux-xxx） ----------------
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        state = listStateGroups,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
            item(key = "groups_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("路由分组", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "同族模型（跨厂商/版本号）聚为一组，对外暴露为 flux-模型名，组内自动切换；" +
                                "有备注（别名）的模型优先按备注归组",
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
                        "还没有路由分组。点上方 + 搜索模型 id，一次匹配即可把各厂商的同族模型聚成一组。",
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
                            models.firstOrNull { it.providerId == m.providerId && it.name == m.modelName }
                                ?.let { m to it }
                        }
                    val gaps = capabilityGaps(activePairs.map { it.second })
                    item(key = "g${group.id}") {
                        RouteGroupCard(
                            group = group,
                            memberCount = groupMembers.size,
                            memberModels = activePairs.map { it.second },
                            gapText = gaps.takeIf { it.isNotEmpty() }?.let { gapLine(it, providers) },
                            onShowGaps = { gapSheetGroup = group },
                            expanded = group.id in expandedGroups,
                            onToggleExpand = {
                                expandedGroups = if (group.id in expandedGroups) {
                                    expandedGroups - group.id
                                } else {
                                    expandedGroups + group.id
                                }
                            },
                            isOpen = openSwipeKey == "g${group.id}",
                            onOpenChange = { open -> openSwipeKey = if (open) "g${group.id}" else null },
                            onToggle = { nv ->
                                scope.launch { app.database.smartRouteGroupDao().update(group.copy(enabled = nv)) }
                            },
                            onDelete = { deleteGroupTarget = group },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    // 成员行作为独立 LazyColumn item：拖拽排序与侧滑互斥才能命中可见项
                    if (group.id in expandedGroups) {
                        items(groupMembers, key = { "m${it.id}" }) { member ->
                            RouteMemberRow(
                                member = member,
                                providers = providers,
                                models = models,
                                gaps = gaps,
                                reorderState = reorderStateGroups,
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
                    }
                }
            }
            item(key = "groups_footer") { Spacer(Modifier.height(12.dp)) }
                    }
                } else {
                    // ---------------- 页 1：聚合模型 smartAi ----------------
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        state = listStateSmart,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
            item(key = "intro") {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("聚合模型 ${SmartAi.MODEL_NAME}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "把候选模型整合成一个 ${SmartAi.MODEL_NAME}。按下方顺序依次尝试：某模型失败或超时无输出，" +
                                "自动切换下一个；已开始输出后失败则不再切换。整条候选链轮巡 $maxRounds 轮仍全部失败，" +
                                "才提示全部候选不可用。极速模式首轮只把请求发给实测速度达标的候选（超时候选只记「慢」），" +
                                "一轮无成功再放宽到聊天档；连续失败的候选进入冷却垫底。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "响应模式、首字超时与冷却等参数对本页与「路由分组」同样生效，" +
                                "在顶部「路由参数」统一修改。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
                            "添加模型后即可在聊天中选择 ${SmartAi.MODEL_NAME}",
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
                        "${SmartAi.MODEL_NAME} 回退顺序（长按拖动排序，左滑条目可禁用/移除）",
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
                        reorderState = reorderStateSmart,
                        modifier = Modifier.animateItem(),
                        isOpen = openSwipeKey == "c${entry.id}",
                        onOpenChange = { open -> openSwipeKey = if (open) "c${entry.id}" else null },
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
    if (showCategoryView) {
        CategoryViewSheet(
            entries = display,
            providers = providers,
            models = models,
            onDismiss = { showCategoryView = false },
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
                                modelName = m.name,
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

    // 分组短板明细半窗（禁用成员后 gaps 随 Flow 即时重算）
    gapSheetGroup?.let { g ->
        val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
        val groupMembers = members.filter { it.groupId == g.id }
        val activePairs = groupMembers
            .filter { it.enabled && it.providerId in enabledProviderIds }
            .mapNotNull { m ->
                models.firstOrNull { it.providerId == m.providerId && it.name == m.modelName }
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
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val provider = providers.firstOrNull { it.id == entry.providerId }
    val model = models.firstOrNull { it.providerId == entry.providerId && it.name == entry.modelName }
    val displayName = model?.alias?.takeIf { it.isNotBlank() } ?: entry.modelName
    // 进程内健康度快照（静态读取，进页面时可见，不实时刷新）
    val health = SmartHealth.info(entry.providerId, entry.modelName)

    SwipeRevealActions(
        modifier = modifier.reorderDragged(reorderState, entry.id),
        actions = listOf(
            SwipeAction(
                label = if (entry.enabled) "禁用" else "启用",
                icon = if (entry.enabled) Icons.Filled.Block else Icons.Filled.PlayArrow,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { onToggle(!entry.enabled) },
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        modifier = Modifier.basicMarquee(),
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

/** 两分段共用的路由参数摘要卡：一行摘要 + 「修改」打开参数半窗 */
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

/**
 * 两段滑动分段控件：选中块位置由 PagerState 驱动（手势拖动内容时色块实时跟移），点击分段平滑切页
 */
@Composable
private fun SlidingSegmentedControl(
    options: List<String>,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 选中块连续位置：当前页 + 拖动进度（钳位到段数范围）
    val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (options.size - 1).toFloat())
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        val segWidth = maxWidth / options.size
        Box(
            Modifier
                .offset(x = segWidth * progress)
                .width(segWidth)
                .height(34.dp)
                .shadow(1.dp, RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9.dp)),
        )
        Row {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .width(segWidth)
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pagerState.currentPage == i) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 路由分组卡片：flux-xxx 组名 + 成员数与能力摘要 + 展开箭头；侧滑 禁用/启用、删除（删除有确认） */
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
    modifier: Modifier = Modifier,
) {
    SwipeRevealActions(
        modifier = modifier,
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
            "${p?.alias ?: "?"}/${m.alias?.takeIf { it.isNotBlank() } ?: m.name}"
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
                                "${p?.alias ?: "?"} / ${m.alias?.takeIf { it.isNotBlank() } ?: m.name}",
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
                                    it.providerId == m.providerId && it.modelName == m.name
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
    val model = models.firstOrNull { it.providerId == member.providerId && it.name == member.modelName }
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
    val enabledProviderIds = providers.filter { it.enabled }.map { it.id }.toSet()
    // 可入组模型：启用厂商下的 chat 模型或图像生成模型
    val eligible = models.filter {
        (it.type == ModelType.CHAT || it.outputImage) && it.providerId in enabledProviderIds
    }
    val suggestions = ModelIdGrouper.suggestKeys(query, eligible, existingKeys).take(8)
    val groupMembers = selectedKey?.let { ModelIdGrouper.membersOf(it, eligible) } ?: emptyList()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("新建路由分组", style = MaterialTheme.typography.titleMedium)
            Text(
                "搜索模型 id，把各厂商的同族模型聚为一组：版本号差异自动归并，" +
                    "对外暴露为 flux-模型名，组内按成员顺序自动切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    selectedKey = null
                },
                label = { Text("模型 id，如 deepseek-v4-flash") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

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
                if (query.isNotBlank() && suggestions.isEmpty()) {
                    Text(
                        "没有匹配的同族模型（请确认模型已在厂商页添加并启用）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                Text(
                    "flux-$selectedKey",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .basicMarquee(),
                )
                Text(
                    "${groupMembers.size} 个成员，按下方顺序作为组内切换优先级",
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
                                "${p?.alias ?: "?"}/${m.alias?.takeIf { it.isNotBlank() } ?: m.name}"
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
                                "${p?.alias ?: "?"} / ${m.alias?.takeIf { it.isNotBlank() } ?: m.name}",
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
                    TextButton(onClick = { selectedKey = null }) { Text("重选") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = groupMembers.isNotEmpty(),
                        onClick = { onCreate(selectedKey ?: return@Button, groupMembers) },
                    ) { Text("创建分组") }
                }
            }
        }
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
                                modifier = Modifier.basicMarquee(),
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
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Text(
                                e.entry.modelName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
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
