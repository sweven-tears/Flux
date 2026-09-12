package cn.luoluna.sweven.ai.ui.lan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun fmtTime(ts: Long?): String =
    if (ts == null) "-" else timeFmt.format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(
    onSmartRoute: () -> Unit = {},
    onRequestLog: () -> Unit = {},
    onAuthDevices: () -> Unit = {},
    vm: LanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lanEnabled by vm.lanEnabled.collectAsStateWithLifecycle()
    val lanPort by vm.lanPort.collectAsStateWithLifecycle()
    val smartAiEnabled by vm.lanSmartAiEnabled.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val serverError by vm.serverError.collectAsStateWithLifecycle()
    val pending by vm.pendingDevices.collectAsStateWithLifecycle()
    val authorized by vm.authorizedDevices.collectAsStateWithLifecycle()
    val blocked by vm.blockedDevices.collectAsStateWithLifecycle()

    val ips = remember { vm.lanIps() }
    var portDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AuthDeviceEntity?>(null) }

    // 电池优化豁免：ON_RESUME 时刷新，从系统弹窗/设置页返回后立即更新
    var batteryIgnored by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Android 13+ 通知权限：开启服务时申请
    var pendingEnable by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.setEnabled(pendingEnable); pendingEnable = false }

    fun toggle(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 && !hasNotifPermission(context)) {
            pendingEnable = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setEnabled(on)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("局域网路由") },
                actions = {
                    IconButton(onClick = onRequestLog) {
                        Icon(Icons.Filled.ReceiptLong, contentDescription = "请求日志")
                    }
                    IconButton(onClick = onSmartRoute) {
                        Icon(Icons.Filled.AltRoute, contentDescription = "智能路由")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 服务开关
            SectionCard(title = "聚合路由服务") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "运行中（端口 $lanPort）" else "已停止",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (running) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "开启后局域网内设备可通过 OpenAI 兼容接口使用聚合模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = lanEnabled, onCheckedChange = { toggle(it) })
                }
                if (running) {
                    ips.forEach { ip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "http://$ip:$lanPort/v1",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = MonoFont,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { copyText(context, "http://$ip:$lanPort/v1") }) {
                                Text("复制")
                            }
                        }
                    }
                    if (ips.isEmpty()) {
                        Text(
                            "未获取到局域网 IP，请确认已连接 Wi-Fi",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                serverError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("服务端口", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { portDialog = true }) { Text("$lanPort  修改") }
                }
                // smartAi 聚合模型对局域网客户端的开放开关（即时生效）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开放 ${SmartAi.MODEL_NAME} 聚合模型", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "客户端请求 model=${SmartAi.MODEL_NAME} 时按智能路由候选顺序自动回退",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = smartAiEnabled, onCheckedChange = { vm.setSmartAiEnabled(it) })
                }
            }

            // 后台稳定运行：退后台被电池策略限流是其他应用接入慢/超时的主因
            if (!batteryIgnored) {
                SectionCard(title = "后台稳定运行") {
                    Text(
                        "应用退到后台后，系统电池策略可能限制网络，导致网关响应慢、客户端超时。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    }) { Text("申请忽略电池优化") }
                    Text(
                        "部分系统还需在系统设置中为本应用允许「自启动 / 后台联网 / 电池不限」，无统一入口需手动设置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 待授权申请
            if (pending.isNotEmpty()) {
                SectionCard(title = "待授权申请（${pending.size}）") {
                    pending.forEachIndexed { index, d ->
                        if (index > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DeviceRow(
                            device = d,
                            onRename = { renameTarget = d },
                            primaryAction = "批准" to { vm.approve(d.deviceId) },
                            secondaryAction = "拒绝" to { vm.deny(d.deviceId) },
                        )
                    }
                }
            }

            // 已授权设备：迁至独立页面，长按设备卡片管理（撤销/拉黑/响应模式/备注）
            SectionCard(title = "已授权设备") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAuthDevices),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (authorized.isEmpty()) "暂无已授权设备" else "共 ${authorized.size} 台",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "进入后长按设备卡片：撤销授权 / 拉黑 / 响应模式 / 备注",
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

            // 已拉黑
            if (blocked.isNotEmpty()) {
                SectionCard(title = "已拒绝设备（${blocked.size}）") {
                    blocked.forEachIndexed { index, d ->
                        if (index > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DeviceRow(
                            device = d,
                            onRename = { renameTarget = d },
                            primaryAction = "解除" to { vm.unblock(d.deviceId) },
                            secondaryAction = null,
                        )
                    }
                }
            }

            // 使用说明
            SectionCard(title = "使用说明") {
                Text(
                    buildString {
                        append("1. 手机与客户端设备连同一个 Wi-Fi\n")
                        append("2. 客户端 Base URL 填上方地址（如 http://192.168.1.5:$lanPort/v1）\n")
                        append("3. API Key 随意填写（如 sk-phone），真正的鉴权是设备授权\n")
                        append("4. 模型名称格式：厂商别名/模型名，完整列表见 GET /v1/models\n")
                        append("5. 新设备首次访问会产生「待授权申请」，在上方批准后即可使用\n")
                        append("6. 客户端可携带请求头 X-Device-Id 自定义设备标识\n")
                        append("7. 进入「已授权设备」页长按设备卡片，可撤销/拉黑或单独设置响应模式，${SmartAi.MODEL_NAME} 请求按设备生效")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.SansSerif,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                )
            }
        }
    }

    if (portDialog) {
        var portText by remember { mutableStateOf(lanPort.toString()) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { portDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val p = portText.toIntOrNull()
                    if (p == null || p < 1024 || p > 65535) err = "端口需在 1024 - 65535 之间"
                    else {
                        vm.setPort(p)
                        portDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { portDialog = false }) { Text("取消") } },
            title = { Text("修改服务端口") },
            text = {
                Column {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        singleLine = true,
                    )
                    err?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }

    renameTarget?.let { device ->
        cn.luoluna.sweven.ai.ui.common.TextFieldDialog(
            title = "设备备注",
            initial = device.label.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.setDeviceLabel(device.deviceId, name)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: AuthDeviceEntity,
    onRename: () -> Unit,
    primaryAction: Pair<String, () -> Unit>,
    secondaryAction: Pair<String, (() -> Unit)>?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                device.label ?: device.deviceId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFont,
                maxLines = 1,
            )
            Text(
                "IP ${device.lastIp} · 首次 ${fmtTime(device.firstSeenAt)} · 最近 ${fmtTime(device.lastSeenAt)} · ${device.requestCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secondaryAction != null) {
            TextButton(onClick = secondaryAction.second) {
                Text(secondaryAction.first, style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(onClick = primaryAction.second) {
            Text(primaryAction.first, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onRename) {
            Text("备注", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun hasNotifPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", text))
}
