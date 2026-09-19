package cn.luoluna.sweven.ai.ui.lan

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.ui.common.ModeSelectDialog
import cn.luoluna.sweven.ai.ui.common.MonoFont
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备管理页（局域网页「设备管理」卡片进入）：
 * 卡片只读展示设备信息，长按弹半窗选择操作（响应模式 / 备注 / 拉黑 / 撤销授权）；
 * 顶栏的请求日志入口也收在这一页（局域网页首页只留一张入口卡）。
 */
class AuthDevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val authDao = (app as App).database.authDeviceDao()

    val devices: StateFlow<List<AuthDeviceEntity>> =
        authDao.observeByStatus(AuthDeviceEntity.STATUS_AUTHORIZED)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 撤销授权：直接删除设备记录，设备再访问会重新走待授权申请 */
    fun revoke(deviceId: String) = viewModelScope.launch {
        authDao.delete(deviceId)
    }

    fun block(deviceId: String) = viewModelScope.launch {
        authDao.byId(deviceId)?.let {
            authDao.upsert(it.copy(status = AuthDeviceEntity.STATUS_BLOCKED))
        }
    }

    fun setLabel(deviceId: String, label: String) = viewModelScope.launch {
        authDao.setLabel(deviceId, label.trim().takeIf { it.isNotEmpty() })
    }

    fun setMode(deviceId: String, mode: String) = viewModelScope.launch {
        if (!SmartModes.isKnown(mode)) return@launch
        authDao.setResponseMode(deviceId, mode)
    }
}

private val authTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun fmtAuthTime(ts: Long?): String =
    if (ts == null) "-" else authTimeFmt.format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthDevicesScreen(
    onBack: () -> Unit,
    onRequestLog: () -> Unit = {},
    vm: AuthDevicesViewModel = viewModel(),
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    var sheetTarget by remember { mutableStateOf<AuthDeviceEntity?>(null) }
    var renameTarget by remember { mutableStateOf<AuthDeviceEntity?>(null) }
    var modeTarget by remember { mutableStateOf<AuthDeviceEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 请求日志入口随设备管理一并收在这一页（局域网首页只留「设备管理」一张卡）
                    IconButton(onClick = onRequestLog) {
                        Icon(Icons.Filled.ReceiptLong, contentDescription = "请求日志")
                    }
                },
            )
        },
    ) { padding ->
        if (devices.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无已授权设备", style = MaterialTheme.typography.titleMedium)
                Text(
                    "新设备首次访问网关会产生待授权申请，\n在「局域网」页批准后出现在这里",
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "hint") {
                    Text(
                        "长按设备卡片：响应模式 / 备注 / 拉黑 / 撤销授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(devices, key = { it.deviceId }) { d ->
                    AuthDeviceCard(device = d, onLongClick = { sheetTarget = d })
                }
            }
        }
    }

    sheetTarget?.let { d ->
        DeviceActionSheet(
            device = d,
            onDismiss = { sheetTarget = null },
            onMode = { modeTarget = d },
            onRename = { renameTarget = d },
            onBlock = { vm.block(d.deviceId) },
            onRevoke = { vm.revoke(d.deviceId) },
        )
    }

    renameTarget?.let { device ->
        TextFieldDialog(
            title = "设备备注",
            initial = device.label.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.setLabel(device.deviceId, name)
                renameTarget = null
            },
        )
    }

    modeTarget?.let { device ->
        ModeSelectDialog(
            title = "响应模式（${device.label ?: device.deviceId}）",
            current = device.responseMode,
            onDismiss = { modeTarget = null },
            onSelect = { mode ->
                vm.setMode(device.deviceId, mode)
                modeTarget = null
            },
        )
    }
}

/** 已授权设备卡片：只读信息，长按弹操作半窗 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuthDeviceCard(device: AuthDeviceEntity, onLongClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                device.label ?: device.deviceId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFont,
                maxLines = 1,
            )
            Text(
                "IP ${device.lastIp} · 首次 ${fmtAuthTime(device.firstSeenAt)} · 最近 ${fmtAuthTime(device.lastSeenAt)} · ${device.requestCount} 次 · 模式${SmartModes.labelOf(device.responseMode)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 长按设备卡片弹出的操作半窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceActionSheet(
    device: AuthDeviceEntity,
    onDismiss: () -> Unit,
    onMode: () -> Unit,
    onRename: () -> Unit,
    onBlock: () -> Unit,
    onRevoke: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                device.label ?: device.deviceId,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            DeviceActionRow(
                Icons.Filled.Speed,
                "响应模式（当前：${SmartModes.labelOf(device.responseMode)}）",
            ) {
                onDismiss(); onMode()
            }
            DeviceActionRow(Icons.Filled.Edit, "设备备注") {
                onDismiss(); onRename()
            }
            DeviceActionRow(Icons.Filled.Block, "拉黑（拒绝其后续访问）") {
                onDismiss(); onBlock()
            }
            DeviceActionRow(Icons.Filled.LinkOff, "撤销授权（删除设备记录）") {
                onDismiss(); onRevoke()
            }
        }
    }
}

@Composable
private fun DeviceActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
