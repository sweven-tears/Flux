package cn.luoluna.sweven.ai.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.BackupManager
import cn.luoluna.sweven.ai.ui.common.SectionCard
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val backupTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCreations: () -> Unit,
    onOpenNetworkLog: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as App).settings }
    val backup = remember { BackupManager(context.applicationContext as App) }
    val scope = rememberCoroutineScope()
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    val logRequestsEnabled by settings.logRequestsEnabled.collectAsStateWithLifecycle()
    val networkLogEnabled by settings.networkLogEnabled.collectAsStateWithLifecycle()
    val networkLogKeep by settings.networkLogKeep.collectAsStateWithLifecycle()
    val imageOpenExternal by settings.imageOpenExternal.collectAsStateWithLifecycle()
    val videoOpenExternal by settings.videoOpenExternal.collectAsStateWithLifecycle()
    val toolMaxRounds by settings.toolMaxRounds.collectAsStateWithLifecycle()

    // 导出：先弹配置弹窗（是否含 Key），再走系统保存文件
    var showExportDialog by remember { mutableStateOf(false) }
    var exportIncludeKeys by remember { mutableStateOf(true) }
    // 导入：选文件解析校验后暂存文本，确认弹窗展示摘要再落库
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    // 网络日志保留条数编辑
    var showLogKeepEdit by remember { mutableStateOf(false) }
    // 工具调用轮数上限编辑
    var showToolRoundsEdit by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val content = backup.export(exportIncludeKeys)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入所选位置")
                    }
                }.onSuccess {
                    Toast.makeText(context, "配置已导出", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取所选文件")
                    }
                    backup.parse(text)
                    text
                }.onSuccess { pendingImportText = it }
                    .onFailure {
                        Toast.makeText(context, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "局域网") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记录请求日志", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "记录每次局域网请求的设备、模型、耗时与用量，供「局域网 → 请求日志」页查看",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = logRequestsEnabled,
                        onCheckedChange = { settings.setLogRequestsEnabled(it) },
                    )
                }
            }
            SectionCard(title = "网络日志") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记录全局网络日志", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "记录 App 发出的每条网络请求（聊天/模型列表/生图/视频任务等）的" +
                                "地址、请求头、请求体与响应；流式响应记录帧原文前缀",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = networkLogEnabled,
                        onCheckedChange = { settings.setNetworkLogEnabled(it) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNetworkLog),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("查看网络日志", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "按时间倒序查看每条请求详情，JSON 可视化展开折叠",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("保留条数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showLogKeepEdit = true }) { Text("$networkLogKeep 条  修改") }
                }
            }
            SectionCard(title = "工具调用") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("单次提问最多调用轮数", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "指一次提问内模型连续发起工具调用的轮数上限；达到上限只停止继续调用，" +
                                "已产生的调用与结果仍完整保留。0 = 不限，由模型自行收敛" +
                                "（想强制收口时再设个小值）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showToolRoundsEdit = true }) {
                        Text(if (toolMaxRounds <= 0) "不限  修改" else "$toolMaxRounds 轮  修改")
                    }
                }
            }
            SectionCard(title = "数据") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showExportDialog = true }) { Text("导出配置") }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    }) { Text("导入配置") }
                }
                Text(
                    "备份厂商、API Key、模型、智能路由、分组预设与路由参数到文件；" +
                        "导入时全量替换当前配置。不包含聊天记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(title = "文件打开方式") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("图片用系统应用打开", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭时点击图片用内置预览查看；开启后点击即交给系统图片查看器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = imageOpenExternal,
                            onCheckedChange = { settings.setImageOpenExternal(it) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("视频用系统应用打开", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "关闭时点击视频用内置播放器播放；开启后点击即交给系统视频播放器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = videoOpenExternal,
                            onCheckedChange = { settings.setVideoOpenExternal(it) },
                        )
                    }
                }
            }
            SectionCard(title = "我的创作") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenCreations),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("图像与视频", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "集中查看、保存到相册或清理每次 AI 创作；清理后聊天原位显示「资源已被清理」占位",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionCard(title = "关于") {
                Text("Flux", style = MaterialTheme.typography.titleMedium)
                Text(
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "局域网聚合路由网关 + AI 聊天客户端：把手机上配置的模型厂商聚合为 " +
                        "OpenAI 兼容接口，供局域网内其他设备调用，也可在本机直接对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出配置") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { exportIncludeKeys = !exportIncludeKeys },
                ) {
                    Checkbox(checked = exportIncludeKeys, onCheckedChange = { exportIncludeKeys = it })
                    Column(Modifier.weight(1f)) {
                        Text("包含 API Key", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "勾选后换机恢复免重填；含 Key 的备份文件请勿分享给他人",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                    exportLauncher.launch("Flux-backup-$stamp.json")
                }) { Text("导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } },
        )
    }

    if (showLogKeepEdit) {
        TextFieldDialog(
            title = "网络日志保留条数",
            initial = networkLogKeep.toString(),
            onDismiss = { showLogKeepEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n < 50 || n > 2000 -> "范围 50 ~ 2000 条"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { settings.setNetworkLogKeep(it) }
                showLogKeepEdit = false
            },
        )
    }

    if (showToolRoundsEdit) {
        TextFieldDialog(
            title = "单次提问最多调用轮数",
            initial = toolMaxRounds.toString(),
            onDismiss = { showToolRoundsEdit = false },
            onValidate = { v ->
                val n = v.trim().toIntOrNull()
                when {
                    n == null -> "请输入数字"
                    n == 0 -> null
                    n < 0 || n > AppSettings.MAX_TOOL_ROUNDS_LIMIT ->
                        "0 = 不限，或填 1 ~ ${AppSettings.MAX_TOOL_ROUNDS_LIMIT}"
                    else -> null
                }
            },
            onConfirm = { v ->
                v.trim().toIntOrNull()?.let { settings.setToolMaxRounds(it) }
                showToolRoundsEdit = false
            },
        )
    }

    pendingImportText?.let { text ->
        val parsed = remember(text) { runCatching { backup.parse(text) }.getOrNull() }
        if (parsed == null) {
            LaunchedEffect(text) {
                Toast.makeText(context, "备份文件无法识别", Toast.LENGTH_LONG).show()
                pendingImportText = null
            }
        } else {
            val summary = remember(text) { backup.summaryOf(parsed) }
            AlertDialog(
                onDismissRequest = { pendingImportText = null },
                title = { Text("导入配置") },
                text = {
                    Text(
                        buildString {
                            summary.exportedAt?.let {
                                append("备份导出于 ${backupTimeFmt.format(Date(it))}。\n\n")
                            }
                            append(
                                "包含厂商 ${summary.providers} 家、API Key ${summary.apiKeys} 个、" +
                                    "模型 ${summary.models} 个、智能路由候选 ${summary.smartRoutes} 个、" +
                                    "路由分组 ${summary.smartRouteGroups} 个、" +
                                    "分组 ${summary.folders} 个、预设 ${summary.presets} 个、" +
                                    "自定义工具 ${summary.customTools} 个、" +
                                    "已授权设备 ${summary.authDevices} 台。\n\n"
                            )
                            append("导入将替换当前全部配置（不影响聊天记录），且无法撤销。确定导入？")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val dto = parsed
                        pendingImportText = null
                        scope.launch {
                            runCatching { backup.import(dto) }
                                .onSuccess {
                                    Toast.makeText(context, "配置已导入", Toast.LENGTH_LONG).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }) { Text("导入") }
                },
                dismissButton = { TextButton(onClick = { pendingImportText = null }) { Text("取消") } },
            )
        }
    }
}
