package cn.luoluna.sweven.ai.ui.lan

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.AbnormalDecision
import cn.luoluna.sweven.ai.data.db.AbnormalStats
import cn.luoluna.sweven.ai.data.db.AbnormalVia
import cn.luoluna.sweven.ai.data.db.ModelAbnormalEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
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
 * 模型异常记录页：模型请求失败（连接失败 / 超时 / HTTP 错误 / 流中断）如实落库的列表，
 * 用户逐条标记「可重试」或「熔断」，结论写回厂商表（retryable / circuitOpen）并立即作用于智能路由。
 * 网关自判的校验失败不在此列——那些不是厂商的问题。
 */
class ModelAbnormalViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as App).database
    private val dao = db.modelAbnormalDao()
    private val providerDao = db.providerDao()

    /** 「今日」起点（本地时区零点） */
    private val todayStart: Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val stats: StateFlow<AbnormalStats> =
        dao.observeStats(todayStart, AbnormalDecision.PENDING, AbnormalDecision.BREAK)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AbnormalStats(0, 0, 0))

    val records: StateFlow<List<ModelAbnormalEntity>> =
        dao.observeRecent(300)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 厂商当前状态（别名、手动熔断标记）；记录对应厂商已被删除时不在表中 */
    val providers: StateFlow<Map<Long, ProviderEntity>> =
        providerDao.observeAll()
            .map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * 当前处于手动熔断状态的厂商。单独列出来是必要的：熔断标记存在厂商表上，
     * 记录清空后若这里不展示，用户就没有入口解除熔断。
     */
    val brokenProviders: StateFlow<List<ProviderEntity>> =
        providerDao.observeAll()
            .map { list -> list.filter { it.circuitOpen } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 标记可重试：解除该厂商的手动熔断并恢复其参与重试链 */
    fun markRetryable(id: Long, providerId: Long) = viewModelScope.launch {
        dao.markDecision(id, AbnormalDecision.RETRYABLE, System.currentTimeMillis())
        providerDao.setCircuitOpen(providerId, false, null)
        providerDao.setRetryable(providerId, true)
    }

    /** 标记熔断：该厂商的全部候选被智能路由直接跳过（同时不再参与重试链） */
    fun markBreak(id: Long, providerId: Long) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        dao.markDecision(id, AbnormalDecision.BREAK, now)
        providerDao.setCircuitOpen(providerId, true, now)
        providerDao.setRetryable(providerId, false)
    }

    /** 直接解除某厂商的熔断（已熔断厂商列表里的入口，不依赖具体某条记录） */
    fun releaseBreaker(providerId: Long) = viewModelScope.launch {
        providerDao.setCircuitOpen(providerId, false, null)
        providerDao.setRetryable(providerId, true)
    }

    fun clear() = viewModelScope.launch { dao.clear() }
}

private val abnormalTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

/** 状态码徽标配色：4xx 橙（多半是请求/凭证问题）、5xx 红（上游故障）、无状态码灰（连接失败/超时） */
private fun codeColor(code: Int): Color = when {
    code >= 500 -> Color(0xFFDC2626)
    code >= 400 -> Color(0xFFEA580C)
    else -> Color(0xFF6B7280)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelAbnormalScreen(
    onBack: () -> Unit,
    onOpenProvider: (Long) -> Unit = {},
    vm: ModelAbnormalViewModel = viewModel(),
) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val brokenProviders by vm.brokenProviders.collectAsStateWithLifecycle()
    var clearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型异常记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { clearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空记录")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "stats") {
                SectionCard(title = "统计") {
                    Text(
                        "今日异常 ${stats.today} 条 · 待决策 ${stats.pending} 条 · 已熔断 ${stats.broken} 条",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "「熔断」会立即让该厂商的所有候选退出智能路由；「可重试」解除熔断并恢复其参与重试链",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // 已熔断厂商：熔断标记在厂商表上，记录清空后这里是唯一的解除入口
                    if (brokenProviders.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(
                            "手动熔断中的厂商",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        brokenProviders.forEach { p ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    p.alias,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = MonoFont,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.releaseBreaker(p.id) }) {
                                    Text("解除熔断", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (records.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有异常记录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "模型请求失败（连接失败 / 超时 / HTTP 错误 / 流中断）时会如实记录在这里，\n" +
                                "App 内聊天与局域网网关的失败都会记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                items(records, key = { it.id }) { record ->
                    AbnormalRow(
                        record = record,
                        provider = providers[record.providerId],
                        onOpenProvider = { onOpenProvider(record.providerId) },
                        onRetryable = { vm.markRetryable(record.id, record.providerId) },
                        onBreak = { vm.markBreak(record.id, record.providerId) },
                    )
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
            title = { Text("清空模型异常记录") },
            text = { Text("全部异常记录（含历史）将被删除，无法恢复。厂商上的熔断 / 可重试标记不受影响。确定清空？") },
        )
    }
}

/** 单条异常记录：厂商/模型 + 链路与状态码徽标 + 错误摘要 + 两个决策按钮，整行点击进厂商详情 */
@Composable
private fun AbnormalRow(
    record: ModelAbnormalEntity,
    provider: ProviderEntity?,
    onOpenProvider: () -> Unit,
    onRetryable: () -> Unit,
    onBreak: () -> Unit,
) {
    val broken = record.decision == AbnormalDecision.BREAK
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = provider != null, onClick = onOpenProvider),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = when {
                                broken -> Color(0xFFDC2626)
                                record.decision == AbnormalDecision.RETRYABLE -> Color(0xFF16A34A)
                                else -> Color(0xFFF59E0B)
                            },
                            shape = CircleShape,
                        ),
                )
                Text(
                    "${record.providerAlias}/${record.modelName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                )
                if (record.count > 1) {
                    Text(
                        "×${record.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                record.httpCode?.let { code ->
                    Text(
                        "HTTP $code",
                        style = MaterialTheme.typography.labelSmall,
                        color = codeColor(code),
                        fontFamily = MonoFont,
                    )
                } ?: Text(
                    "无响应",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(abnormalTimeFmt.format(Date(record.at)))
                    append(" · ")
                    append(if (record.via == AbnormalVia.LAN) "局域网" else "App 内")
                    if (provider == null) {
                        append(" · 厂商已删除")
                    } else {
                        if (provider.circuitOpen) append(" · 已熔断")
                        if (!provider.retryable) append(" · 不参与重试")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
            record.error?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (record.decision) {
                        AbnormalDecision.RETRYABLE -> "已标记可重试"
                        AbnormalDecision.BREAK -> "已标记熔断"
                        else -> "待决策"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRetryable,
                    enabled = provider != null,
                ) {
                    Text(
                        "可重试",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (record.decision == AbnormalDecision.RETRYABLE) {
                            Color(0xFF16A34A)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                TextButton(
                    onClick = onBreak,
                    enabled = provider != null,
                ) {
                    Text(
                        "熔断",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (broken) Color(0xFFDC2626) else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
