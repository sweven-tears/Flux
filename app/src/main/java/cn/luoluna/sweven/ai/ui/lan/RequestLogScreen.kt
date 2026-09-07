package cn.luoluna.sweven.ai.ui.lan

import android.app.Application
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.DeviceUsageStat
import cn.luoluna.sweven.ai.data.db.LogStats
import cn.luoluna.sweven.ai.data.db.RequestLogEntity
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import cn.luoluna.sweven.ai.ui.common.formatTokens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 请求日志页：今日整体统计 + 按设备用量 + 最近 200 条请求明细。
 * 数据来自局域网网关的打点（request_logs），用于接入排查与用量感知。
 */
class RequestLogViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).database

    /** 「今日」起点（本地时区零点） */
    private val todayStart: Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val stats: StateFlow<LogStats> =
        db.requestLogDao().observeStatsSince(todayStart)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogStats(0, 0, 0, 0))

    val usageByDevice: StateFlow<List<DeviceUsageStat>> =
        db.requestLogDao().observeUsageByDeviceSince(todayStart)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<RequestLogEntity>> =
        db.requestLogDao().observeRecent(200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 设备备注名，展示时优先于设备 ID */
    val deviceLabels: StateFlow<Map<String, String>> =
        db.authDeviceDao().observeAll()
            .map { list -> list.mapNotNull { d -> d.label?.takeIf { it.isNotBlank() }?.let { d.deviceId to it } }.toMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun clear() = viewModelScope.launch { db.requestLogDao().clear() }
}

private val logTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestLogScreen(
    onBack: () -> Unit,
    vm: RequestLogViewModel = viewModel(),
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val usageByDevice by vm.usageByDevice.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val deviceLabels by vm.deviceLabels.collectAsStateWithLifecycle()

    var clearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("请求日志") },
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
                Text(
                    "还没有请求记录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "局域网设备接入后，每次 /v1/chat/completions 请求\n（含失败）都会记录在这里",
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "stats") {
                    SectionCard(title = "今日统计") {
                        Text(
                            buildString {
                                append("请求 ${stats.total} 次 · 成功 ${stats.okCount} · 失败 ${stats.total - stats.okCount}")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            buildString {
                                append("Token 输入 ${formatTokens(stats.promptTokens)} · 输出 ${formatTokens(stats.completionTokens)}")
                                append("（以客户端返回的 usage 为准，上游未返回时不计）")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (usageByDevice.isNotEmpty()) {
                            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            usageByDevice.forEach { d ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                ) {
                                    Text(
                                        deviceLabels[d.deviceId] ?: d.deviceId,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = MonoFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${d.requests} 次 · ${formatTokens(d.promptTokens)}+${formatTokens(d.completionTokens)} tok",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                items(logs, key = { it.id }) { log ->
                    LogRow(log = log, deviceLabel = deviceLabels[log.deviceId])
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
            title = { Text("清空请求日志") },
            text = { Text("全部请求记录（含历史）将被删除，无法恢复。确定清空？") },
        )
    }
}

/** 单条请求日志：状态点 + 时间 + 模型 + 设备/命中/耗时/token，点击展开错误详情 */
@Composable
private fun LogRow(log: RequestLogEntity, deviceLabel: String?) {
    var expanded by remember { mutableStateOf(false) }
    val fail = !log.ok
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = if (fail) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
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
                    log.model,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    if (log.stream) "流式" else "非流式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(deviceLabel ?: log.deviceId)
                    log.via?.let { append(" · $it") }
                    if (log.stream) {
                        log.ttftMs?.let { append(" · 首字 ${fmtMs(it)}") }
                    }
                    log.totalMs?.let { append(" · 共 ${fmtMs(it)}") }
                    if (log.promptTokens != null || log.completionTokens != null) {
                        append(" · ${formatTokens(log.promptTokens ?: 0)}+${formatTokens(log.completionTokens ?: 0)} tok")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
            if (expanded && (log.error != null || log.ip.isNotBlank())) {
                Text(
                    buildString {
                        append("IP ${log.ip}")
                        append(" · 设备 ${log.deviceId}")
                        log.error?.let { append("\n$it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFont,
                    color = if (fail) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                )
            }
        }
    }
}

/** 耗时显示：850ms / 12s / 1m05s */
private fun fmtMs(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m${java.util.Locale.ROOT.let { "%02d".format(it, (ms % 60_000) / 1000) }}s"
    ms >= 10_000 -> "${ms / 1000}s"
    else -> "${ms}ms"
}
