package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import cn.luoluna.sweven.ai.ui.common.maskKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一次余额请求的状态：按密钥 id（厂商级查询用 null）分别记录 */
private sealed interface BalanceState {
    data object Loading : BalanceState
    data class Ok(val value: String) : BalanceState
    data class Err(val message: String) : BalanceState
}

/** 输入框改动到落库的防抖间隔（每敲一个字就写库会让数据库反复回推、光标抖动） */
private const val CONFIG_SAVE_DEBOUNCE_MS = 400L

/**
 * 账户余额：厂商级的余额查询配置与结果。
 * 配置项（URL / Header / 结果 JSON 键）持久化在 [ProviderEntity] 上，
 * 支持 {{baseUrl}} 与 {{apiKey}} 变量；多密钥独立查询时每把密钥各查一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()

    val provider by remember(id) { app.database.providerDao().observeById(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val keys by remember(id) { app.database.apiKeyDao().observeForProvider(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val p = provider ?: run {
        Scaffold(topBar = { TopAppBar(title = { Text("账户余额") }) }) { pad ->
            Column(Modifier.padding(pad).padding(32.dp)) { Text("加载中…") }
        }
        return
    }

    var results by remember(id) { mutableStateOf<Map<Long?, BalanceState>>(emptyMap()) }
    // 编辑中的配置：本地状态为主，防抖后落库（避免逐字写库导致输入回弹）
    var urlText by remember(id) { mutableStateOf(p.balanceUrl) }
    var headers by remember(id) { mutableStateOf(parseBalanceHeaders(p.balanceHeaders)) }
    var jsonKeyText by remember(id) { mutableStateOf(p.balanceJsonKey) }

    LaunchedEffect(urlText) {
        delay(CONFIG_SAVE_DEBOUNCE_MS)
        app.database.providerDao().setBalanceUrl(id, urlText)
    }
    LaunchedEffect(headers) {
        delay(CONFIG_SAVE_DEBOUNCE_MS)
        app.database.providerDao().setBalanceHeaders(id, encodeBalanceHeaders(headers))
    }
    LaunchedEffect(jsonKeyText) {
        delay(CONFIG_SAVE_DEBOUNCE_MS)
        app.database.providerDao().setBalanceJsonKey(id, jsonKeyText)
    }

    // 单值模式使用的密钥：当前启用的，否则第一把
    val singleKey = keys.firstOrNull { it.id == p.activeKeyId } ?: keys.firstOrNull()

    fun query(target: ApiKeyEntity?) {
        val slot: Long? = if (p.balancePerKey) target?.id else null
        val headerText = encodeBalanceHeaders(headers)
        results = results + (slot to BalanceState.Loading)
        scope.launch {
            // 先落库编辑中的配置，再按同一份配置发请求，避免刷新用到旧值
            app.database.providerDao().setBalanceUrl(id, urlText)
            app.database.providerDao().setBalanceHeaders(id, headerText)
            app.database.providerDao().setBalanceJsonKey(id, jsonKeyText)
            app.providerClient.fetchBalance(
                baseUrl = p.baseUrl,
                apiKey = target?.key,
                urlTemplate = urlText,
                headersTemplate = headerText,
                jsonKey = jsonKeyText,
            ).onSuccess { value ->
                results = results + (slot to BalanceState.Ok(value))
            }.onFailure { e ->
                results = results + (slot to BalanceState.Err(e.message ?: "查询失败"))
            }
        }
    }

    fun refreshAll() {
        if (p.balancePerKey) {
            if (keys.isEmpty()) query(null) else keys.forEach { query(it) }
        } else {
            query(singleKey)
        }
    }

    FullPageScaffold(title = "账户余额", onBack = onBack) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "switches") {
                SectionCard(title = "查询设置") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("获取账户余额", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭后不展示查询结果，仅保留本页配置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = p.balanceEnabled,
                            onCheckedChange = { on ->
                                scope.launch { app.database.providerDao().update(p.copy(balanceEnabled = on)) }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("多密钥独立查询", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (p.balancePerKey)
                                    "每把密钥各自查询，结果分别展示"
                                else "只查询一次（使用当前默认密钥）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = p.balancePerKey,
                            enabled = p.balanceEnabled,
                            onCheckedChange = { on ->
                                results = emptyMap()
                                scope.launch { app.database.providerDao().update(p.copy(balancePerKey = on)) }
                            },
                        )
                    }
                }
            }

            item(key = "config") {
                SectionCard(title = "接口配置") {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "可用变量：{{baseUrl}}、{{apiKey}}；不以 http 开头时自动拼接 Base URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    HeaderEditor(headers = headers, onChange = { headers = it })
                    OutlinedTextField(
                        value = jsonKeyText,
                        onValueChange = { jsonKeyText = it },
                        label = { Text("结果 JSON 键") },
                        placeholder = { Text("如 data.total_usage") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Text(
                        "点分路径，支持数组下标（如 data[0].balance）；留空则直接显示响应文本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            urlText = ProviderEntity.DEFAULT_BALANCE_URL
                            headers = parseBalanceHeaders(ProviderEntity.DEFAULT_BALANCE_HEADERS)
                            jsonKeyText = ProviderEntity.DEFAULT_BALANCE_JSON_KEY
                            results = emptyMap()
                        }) { Text("重置为默认") }
                    }
                }
            }

            item(key = "results") {
                SectionCard(
                    title = "查询结果",
                    actions = {
                        TextButton(onClick = { refreshAll() }, enabled = p.balanceEnabled) { Text("全部刷新") }
                    },
                ) {
                    if (!p.balanceEnabled) {
                        Text(
                            "「获取账户余额」已关闭，开启后可查询",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (keys.isEmpty()) {
                        Text(
                            "尚未配置 API Key，无法查询余额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (p.balancePerKey) {
                        keys.forEachIndexed { index, k ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            BalanceResultRow(
                                title = k.alias,
                                subtitle = maskKey(k.key),
                                state = results[k.id],
                                onRefresh = { query(k) },
                            )
                        }
                    } else {
                        BalanceResultRow(
                            title = "当前余额",
                            subtitle = singleKey?.let { "使用密钥：${it.alias}" } ?: "使用默认密钥",
                            state = results[null],
                            onRefresh = { query(singleKey) },
                        )
                    }
                }
            }
        }
    }
}

/** 单条余额结果行：标题 / 副标题 + 值 + 刷新按钮 */
@Composable
private fun BalanceResultRow(
    title: String,
    subtitle: String,
    state: BalanceState?,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                null -> Text(
                    "尚未查询",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BalanceState.Loading -> Text(
                    "查询中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is BalanceState.Ok -> Text(
                    state.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                is BalanceState.Err -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state is BalanceState.Loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新余额")
            }
        }
    }
}

/** Header 编辑器：名称 / 值两列 + 删除，底部「添加 Header」 */
@Composable
private fun HeaderEditor(
    headers: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        headers.forEachIndexed { index, (name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v ->
                        onChange(headers.toMutableList().also { it[index] = v to value })
                    },
                    label = { Text("Header 名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { v ->
                        onChange(headers.toMutableList().also { it[index] = name to v })
                    },
                    label = { Text("Header 值") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = {
                    onChange(headers.toMutableList().also { it.removeAt(index) })
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除 Header")
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            TextButton(onClick = { onChange(headers + ("" to "")) }) { Text("＋ 添加 Header") }
        }
        Text(
            "变量：{{baseUrl}}、{{apiKey}}。API 密钥将随上述请求头发送到此 URL。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Name: Value" 每行一条 → 行列表（空行忽略，值里允许出现冒号） */
internal fun parseBalanceHeaders(text: String?): List<Pair<String, String>> {
    if (text.isNullOrBlank()) return emptyList()
    return text.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val idx = line.indexOf(':')
        if (idx <= 0) line.trim() to "" else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }
}

internal fun encodeBalanceHeaders(list: List<Pair<String, String>>): String =
    list.filter { it.first.isNotBlank() }
        .joinToString("\n") { (name, value) -> "${name.trim()}: ${value.trim()}" }
