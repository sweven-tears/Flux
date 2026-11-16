package cn.luoluna.sweven.ai.ui.tools

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.tools.ScriptToolDev
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.tools.net.SearchChain
import cn.luoluna.sweven.ai.tools.net.SearchOptions
import cn.luoluna.sweven.ai.tools.net.SearchProviderKind
import cn.luoluna.sweven.ai.tools.net.SearchProviders
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold
import cn.luoluna.sweven.ai.ui.common.InfoIconWithPopup
import cn.luoluna.sweven.ai.ui.common.ModalityTag
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * 工具中心：管理本地工具。
 *
 * - 内置工具：开关即全局默认（`AppSettings.defaultToolIds`），新建会话默认勾选
 * - 自定义工具：启用开关写 `custom_tools.enabled`（与全局默认解耦）——启用即注册进注册表并默认勾选进新会话；
 *   禁用后从注册表移除（工具中心仍可编辑/试运行）。列表直接读 DAO，所以禁用项也能看到
 * - 单个会话的勾选在聊天输入栏的扳手按钮里调整
 */
@Composable
fun ToolsCenterScreen(
    onBack: () -> Unit,
    onEditTool: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val dao = app.database.customToolDao()
    val defaultIdsJson by app.settings.defaultToolIds.collectAsStateWithLifecycle()
    val enabled: Set<String> = defaultIdsJson
        ?.let { raw -> runCatching { ChatJson.json.decodeFromString<List<String>>(raw).toSet() }.getOrNull() }
        ?: emptySet()
    val builtIns = ToolRegistry.builtIns()
    val customTools by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingDelete by remember { mutableStateOf<CustomToolEntity?>(null) }
    // 脚本开发工具：写操作确认策略（false = create/update/delete 弹窗确认）
    val scriptAutoApprove by app.settings.scriptToolAutoApprove.collectAsStateWithLifecycle()
    val scriptDevEnabled = ScriptToolDev.NAME in enabled

    // 联网搜索配置（工具中心 → 联网搜索）：改配置即时生效，web_search 每次执行时现读
    val searchKind by app.settings.searchProvider.collectAsStateWithLifecycle()
    val searchEndpoint by app.settings.searchEndpoint.collectAsStateWithLifecycle()
    val searchCount by app.settings.searchCount.collectAsStateWithLifecycle()
    var endpointText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf(searchCount.toString()) }
    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(searchEndpoint) { endpointText = searchEndpoint.orEmpty() }
    LaunchedEffect(searchCount) { countText = searchCount.toString() }

    fun setEnabled(name: String, on: Boolean) {
        val next = if (on) enabled + name else enabled - name
        app.settings.setDefaultToolIds(ChatJson.json.encodeToString(next.sorted()))
    }

    FullPageScaffold(title = "工具中心", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "内置工具") {
                Text(
                    "开关即全局默认：新建会话默认勾选这些工具；单个会话可在聊天输入栏的扳手按钮里单独调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (builtIns.isEmpty()) {
                    Text(
                        "暂无已注册工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                builtIns.forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tool.displayName, style = MaterialTheme.typography.bodyLarge)
                                InfoIconWithPopup(
                                    title = tool.displayName,
                                    text = buildString {
                                        append(tool.description)
                                        append("\n\n函数名：")
                                        append(tool.name)
                                        if (tool.needsApproval) append("\n该工具每次调用前都会弹窗确认")
                                    },
                                )
                            }
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tool.name in enabled,
                            onCheckedChange = { setEnabled(tool.name, it) },
                        )
                    }
                }
            }

            SectionCard(
                title = "脚本开发工具",
                actions = {
                    InfoIconWithPopup(
                        title = "脚本开发工具",
                        text = "让模型在聊天里自己造工具：你说「帮我做个 XX 工具」，它就写脚本 → 校验 → 试运行 → 建好，" +
                            "全程不用离开聊天页。建出来的工具与手动新建的完全一样（同一张表、同一套执行器）。\n\n" +
                            "动作与确认策略：\n" +
                            "• create / update / delete：默认弹窗让你确认\n" +
                            "• list / get / validate：直接执行（只读）\n" +
                            "• test：在 QuickJS 沙箱里试跑一次\n" +
                            "• enable / disable：直接生效\n\n" +
                            "注意：test 会执行模型生成的代码，但沙箱无网络/无 DOM/无文件，10 秒超时；" +
                            "HTTP 类型的 test 会真实发出请求（可在「设置 → 网络日志」查看）。",
                    )
                },
            ) {
                Text(
                    "在上面「内置工具」里开启「脚本开发工具」后，就能在聊天里直接说「帮我做个查天气的工具」，" +
                        "模型会写脚本 → 校验 → 沙箱试运行 → 建好；建出来的工具会出现在下面的自定义工具列表里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    if (scriptDevEnabled) "当前状态：已开启" else "当前状态：未开启（去上面打开开关才能用）",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (scriptDevEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("写操作免确认", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后 create/update/delete 不再弹窗（仅在完全信任当前模型时建议开启）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = scriptAutoApprove,
                        onCheckedChange = { app.settings.setScriptToolAutoApprove(it) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前策略：" + if (scriptAutoApprove) {
                        "写操作直接落库，无确认弹窗"
                    } else {
                        "新建/修改/删除工具时会弹出确认框；只读动作与开关直接执行"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "联网搜索",
                actions = {
                    InfoIconWithPopup(
                        title = "联网搜索",
                        text = "免密钥方案：默认用 Bing 网页抓取，失败自动回退 DuckDuckGo；" +
                            "自建 SearXNG 实例最稳定（需在实例 settings 里开启 json 格式，公共实例常被限流）。\n\n" +
                            "只在模型勾选「联网搜索」工具时才会用到；这里改配置立即生效、无需重启。\n" +
                            "抓取类源可能被风控或改版，失败时工具会返回每个源各自的原因，便于判断是网络问题还是配置问题。\n\n" +
                            "搜索请求可在「设置 → 网络日志」里查看。",
                    )
                },
            ) {
                Text(
                    "搜索源",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    SearchProviders.all().forEach { provider ->
                        FilterChip(
                            selected = (searchKind ?: SearchProviderKind.BING) == provider.kind,
                            onClick = { app.settings.setSearchProvider(provider.kind) },
                            label = { Text(provider.displayName) },
                        )
                    }
                }
                if ((searchKind ?: SearchProviderKind.BING) == SearchProviderKind.SEARXNG) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = endpointText,
                        onValueChange = {
                            endpointText = it
                            app.settings.setSearchEndpoint(it)
                        },
                        label = { Text("SearXNG 实例地址") },
                        singleLine = true,
                        supportingText = { Text("例：https://searx.example.com（需开启 json 输出格式）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = countText,
                    onValueChange = {
                        countText = it
                        it.trim().toIntOrNull()?.let { n -> app.settings.setSearchCount(n) }
                    },
                    label = { Text("默认返回条数（1-10）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testQuery,
                    onValueChange = { testQuery = it },
                    label = { Text("测试关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !testing && testQuery.isNotBlank(),
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val options = SearchOptions(
                                    kind = searchKind ?: SearchProviderKind.BING,
                                    endpoint = searchEndpoint,
                                    apiKey = app.settings.searchApiKey.value,
                                )
                                testResult = try {
                                    val result = SearchChain.search(options, testQuery, searchCount, null, null)
                                    buildString {
                                        append("命中源：${result.provider}（${result.items.size} 条）\n")
                                        result.items.take(3).forEach { item ->
                                            append("${item.index}. ${item.title}\n   ${item.url}\n")
                                        }
                                    }
                                } catch (e: Exception) {
                                    "失败：${e.message?.take(300).orEmpty()}"
                                }
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "搜索中…" else "测试搜索") }
                    Spacer(Modifier.height(0.dp))
                }
                testResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            SectionCard(
                title = "自定义工具",
                actions = {
                    TextButton(onClick = { onEditTool(NEW_TOOL_ID) }) { Text("新建工具") }
                },
            ) {
                Text(
                    "自建 HTTP 请求工具（对接任意 REST API）或 JS 脚本工具（沙箱内跑代码）。" +
                        "启用后即注册进工具列表并默认勾选进新会话；点条目可编辑，编辑页里可「试运行」验证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (customTools.isEmpty()) {
                    Text(
                        "还没有自定义工具，点右上角「新建工具」开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                customTools.forEach { entity ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditTool(entity.id) }
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entity.displayName, style = MaterialTheme.typography.bodyLarge)
                                ModalityTag(if (entity.kind == CustomToolKind.HTTP) "HTTP" else "JS")
                                InfoIconWithPopup(
                                    title = entity.displayName,
                                    text = buildString {
                                        if (entity.description.isNotBlank()) {
                                            append(entity.description)
                                            append("\n\n")
                                        }
                                        append("函数名：")
                                        append(entity.name)
                                        append("\n执行方式：")
                                        append(if (entity.kind == CustomToolKind.HTTP) "HTTP 请求" else "JS 脚本")
                                        if (entity.needsApproval) append("\n该工具每次调用前都会弹窗确认")
                                    },
                                )
                            }
                            Text(
                                entity.name + if (entity.enabled) "" else "（已禁用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = entity.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    dao.setEnabled(entity.id, on, System.currentTimeMillis())
                                }
                            },
                        )
                        IconButton(onClick = { pendingDelete = entity }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除 ${entity.displayName}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "说明") {
                Text(
                    buildString {
                        append("1. 工具调用仅对支持「工具调用」能力的模型生效（模型编辑器里勾选），且厂商协议为 OpenAI 兼容。\n")
                        append("2. 勾选工具后，模型会在需要时自动调用，整轮调用链会折叠显示在回复里。\n")
                        append("3. JS 执行器为沙箱执行（无网络、无文件、无 DOM），单次执行最长 10 秒。\n")
                        append("4. 自定义 HTTP 工具的真实请求会出现在「设置 → 网络日志」里，便于排查。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingDelete?.let { entity ->
        ConfirmDialog(
            title = "删除工具",
            text = "删除「${entity.displayName}」后，它会从工具列表移除；历史会话里的调用记录仍保留。确定删除？",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch { dao.deleteById(entity.id) }
            },
        )
    }
}

/** 新建工具的路由参数（编辑页用它区分新建/编辑） */
const val NEW_TOOL_ID = -1L
