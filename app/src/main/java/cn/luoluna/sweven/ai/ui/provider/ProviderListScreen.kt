package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.ui.common.ModalityTag
import cn.luoluna.sweven.ai.ui.common.ReorderState
import cn.luoluna.sweven.ai.ui.common.rememberReorderState
import cn.luoluna.sweven.ai.ui.common.reorderDragged
import cn.luoluna.sweven.ai.ui.common.reorderHandle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onSmartRoute: () -> Unit = {},
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val providers by remember { app.database.providerDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val models by remember { app.database.modelDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    // 拖拽换位后的本地顺序，落库完成前临时覆盖 Flow 数据
    var localOrder by remember { mutableStateOf<List<ProviderEntity>?>(null) }
    val display = localOrder ?: providers
    val listState = rememberLazyListState()
    val reorderState = rememberReorderState(listState) { fromKey, toKey ->
        val from = display.indexOfFirst { it.id == fromKey }
        val to = display.indexOfFirst { it.id == toKey }
        if (from < 0 || to < 0 || from == to) return@rememberReorderState
        val reordered = display.toMutableList().apply { add(to, removeAt(from)) }
            .mapIndexed { index, p -> p.copy(sortOrder = index) }
        localOrder = reordered
        scope.launch {
            app.database.providerDao().updateAll(reordered)
            localOrder = null
        }
    }

    val modelCount = models.groupingBy { it.providerId }.eachCount()
    val enabledCount = models.filter { it.enabled }.groupingBy { it.providerId }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型厂商") },
                actions = {
                    // 右上角：页面级配置入口
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("智能路由") },
                            onClick = {
                                menuOpen = false
                                onSmartRoute()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加厂商")
            }
        },
    ) { padding ->
        if (display.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "还没有模型厂商",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "添加预设厂商（填 API Key 即用）或自定义厂商，\n随后即可拉取模型列表并开始对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(display, key = { it.id }) { p ->
                    ProviderCard(
                        p = p,
                        count = modelCount[p.id] ?: 0,
                        enabled = enabledCount[p.id] ?: 0,
                        reorderState = reorderState,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpen(p.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    p: ProviderEntity,
    count: Int,
    enabled: Int,
    reorderState: ReorderState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.reorderDragged(reorderState, p.id),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.alias, style = MaterialTheme.typography.titleMedium)
                    if (p.isPreset) {
                        ModalityTag(
                            "预设",
                            modifier = Modifier.padding(start = 6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
                Text(
                    p.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    "模型 $count 个（启用 $enabled）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TestStatusDot(ok = p.lastTestOk, latencyMs = p.lastTestLatencyMs)
            // 长按拖动排序手柄
            Icon(
                Icons.Filled.DragIndicator,
                contentDescription = "长按拖动排序",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .reorderHandle(reorderState, p.id),
            )
        }
    }
}

@Composable
fun TestStatusDot(ok: Boolean?, latencyMs: Long?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    color = when (ok) {
                        true -> androidx.compose.ui.graphics.Color(0xFF22C55E)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
        )
        if (ok == true && latencyMs != null) {
            Text(
                "${latencyMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
