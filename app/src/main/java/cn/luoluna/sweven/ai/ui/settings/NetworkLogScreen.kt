package cn.luoluna.sweven.ai.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.NetworkLogEntity
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold
import cn.luoluna.sweven.ai.ui.common.JsonViewer
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.isJsonText
import cn.luoluna.sweven.ai.ui.common.prettyJson
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局网络日志页：App 发出的每条出站 HTTP 请求（开关在设置页打开后开始记录）。
 * 列表按时间倒序；点条目进请求详情页，请求/响应体 JSON 提供树形可视化。
 */
class NetworkLogViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).database

    val logs: StateFlow<List<NetworkLogEntity>> =
        db.networkLogDao().observeRecent(1000)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear() = viewModelScope.launch { db.networkLogDao().clear() }
}

private val logTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkLogScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    vm: NetworkLogViewModel = viewModel(),
) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    var clearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网络日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { clearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有网络请求记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "在设置页打开「记录全局网络日志」后，App 发出的每条请求\n（聊天 / 模型列表 / 生图 / 视频任务等）都会记录在这里",
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(logs, key = { it.id }) { log ->
                    NetworkLogRow(log = log, onClick = { onOpenDetail(log.id) })
                }
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.clear()
                    clearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
            title = { Text("清空网络日志") },
            text = { Text("全部网络请求记录将被删除，无法恢复。确定清空？") },
        )
    }
}

/** 单条请求行：状态点 + 时间 + 类别 + 主机/路径 + 方法；下行状态码/耗时/错误 */
@Composable
private fun NetworkLogRow(log: NetworkLogEntity, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = if (log.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        ),
                )
                Text(
                    logTimeFmt.format(Date(log.at)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(
                    log.url.substringAfter("://").substringBefore('?'),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    log.method,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                buildString {
                    append(log.tag)
                    append(" · ")
                    if (log.httpCode != null) {
                        append("HTTP ${log.httpCode}")
                    } else {
                        append("未收到响应")
                    }
                    log.durationMs?.let { append(" · ${fmtMs(it)}") }
                    log.error?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (log.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
        }
    }
}

/** 请求详情页：基本信息 + 请求头/体 + 响应头/体，JSON 提供树形/原文切换，各段可复制 */
@Composable
fun NetworkLogDetailScreen(id: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    var loaded by remember(id) { mutableStateOf(false) }
    val log by remember(id) {
        app.database.networkLogDao().observeById(id).onEach { loaded = true }
    }.collectAsStateWithLifecycle(initialValue = null)

    FullPageScaffold(title = "请求详情", onBack = onBack) { padding ->
        val l = log
        if (l == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (loaded) "该记录已被清除" else "加载中…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoSection(l)
                l.requestHeaders?.let { BodySection("请求头", it) }
                l.requestBody?.let { BodySection("请求体", it) }
                l.responseHeaders?.let { BodySection("响应头", it) }
                l.responseBody?.let { BodySection("响应体", it) }
            }
        }
    }
}

@Composable
private fun InfoSection(log: NetworkLogEntity) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("基本信息", style = MaterialTheme.typography.titleSmall)
        InfoLine("时间", logTimeFmt.format(Date(log.at)))
        InfoLine("类别", log.tag)
        InfoLine("状态", log.httpCode?.let { "HTTP $it" } ?: "未收到响应")
        log.durationMs?.let { InfoLine("耗时", fmtMs(it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "URL",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copyText(context, log.url) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制 URL",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        log.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFont)
    }
}

/**
 * 正文段（请求头/请求体/响应头/响应体）：JSON 提供「树形/原文」切换，
 * 树形可展开折叠，原文为美化后的等宽文本；标题行带一键复制。
 */
@Composable
private fun BodySection(title: String, text: String) {
    val context = LocalContext.current
    val isJson = remember(text) { isJsonText(text) }
    var showTree by rememberSaveable(title, isJson) { mutableStateOf(isJson) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (isJson) {
                TextButton(onClick = { showTree = !showTree }) {
                    Text(if (showTree) "原文" else "树形")
                }
            }
            IconButton(onClick = { copyText(context, prettyJson(text)) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "复制$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (isJson && showTree) {
            JsonViewer(text)
        } else {
            MonoTextBlock(prettyJson(text))
        }
    }
}

@Composable
private fun MonoTextBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFont,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Flux", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/** 耗时显示：850ms / 12s / 1m05s */
private fun fmtMs(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m${"%02d".format(Locale.ROOT, (ms % 60_000) / 1000)}s"
    ms >= 10_000 -> "${ms / 1000}s"
    else -> "${ms}ms"
}
