package cn.luoluna.sweven.ai.ui.lan

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.AbnormalDecision
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.server.LanService
import cn.luoluna.sweven.ai.server.NetUtil
import cn.luoluna.sweven.ai.server.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LanViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app as App
    private val settings = ctx.settings
    private val authDao = ctx.database.authDeviceDao()

    val lanEnabled: StateFlow<Boolean> = settings.lanEnabled
    val lanPort: StateFlow<Int> = settings.lanPort
    val lanSmartAiEnabled: StateFlow<Boolean> = settings.lanSmartAiEnabled
    val lanOpenAllModels: StateFlow<Boolean> = settings.lanOpenAllModels

    /** mDNS 主机名前缀（null = 默认 flux.local） */
    val lanHostnameLabel: StateFlow<String?> = settings.lanHostnameLabel

    /** mDNS 实际广播的主机名（冲突自动改名后以此为准） */
    val mdnsHost: StateFlow<String?> = ServerState.mdnsHost
    val running: StateFlow<Boolean> = ServerState.running
    val serverError: StateFlow<String?> = ServerState.lastError

    /** 开关 smartAi 聚合模型对局域网客户端的开放（即时生效） */
    fun setSmartAiEnabled(on: Boolean) {
        settings.setLanSmartAiEnabled(on)
    }

    /** 开关「开放所有模型」：客户端可直接选任意单个模型 vs 只暴露分组路由与 flux-auto（即时生效） */
    fun setOpenAllModels(on: Boolean) {
        settings.setLanOpenAllModels(on)
    }

    val pendingDevices: StateFlow<List<AuthDeviceEntity>> =
        authDao.observeByStatus(AuthDeviceEntity.STATUS_PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val authorizedDevices: StateFlow<List<AuthDeviceEntity>> =
        authDao.observeByStatus(AuthDeviceEntity.STATUS_AUTHORIZED)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val blockedDevices: StateFlow<List<AuthDeviceEntity>> =
        authDao.observeByStatus(AuthDeviceEntity.STATUS_BLOCKED)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _approvedPairingToken = MutableStateFlow<Pair<String, String>?>(null)
    val approvedPairingToken: StateFlow<Pair<String, String>?> = _approvedPairingToken.asStateFlow()

    fun dismissApprovedPairingToken() { _approvedPairingToken.value = null }

    /** 待决策的模型异常记录数（「模型异常记录」入口卡片摘要） */
    val pendingAbnormals: StateFlow<Int> =
        ctx.database.modelAbnormalDao().observeCountByDecision(AbnormalDecision.PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun lanIps(): List<String> = NetUtil.lanIps()

    fun setEnabled(on: Boolean) {
        settings.setLanEnabled(on)
        val context = getApplication<Application>()
        if (on) {
            ContextCompat.startForegroundService(context, LanService.startIntent(context))
        } else {
            context.stopService(Intent(context, LanService::class.java))
        }
    }

    /** 修改端口；若服务在运行则重启生效 */
    fun setPort(port: Int) {
        settings.setLanPort(port)
        val context = getApplication<Application>()
        if (ServerState.running.value) {
            context.stopService(Intent(context, LanService::class.java))
            ContextCompat.startForegroundService(context, LanService.startIntent(context))
        }
    }

    /** 修改 mDNS 主机名前缀（空 = 恢复默认 flux.local）；服务内监听变更自动重注册，无需重启 */
    fun setHostnameLabel(label: String?) {
        settings.setLanHostnameLabel(label)
    }

    fun approve(deviceId: String) = viewModelScope.launch {
        val token = ctx.deviceAuth.approve(deviceId) ?: return@launch
        _approvedPairingToken.value = deviceId to token
    }

    fun deny(deviceId: String) = viewModelScope.launch {
        authDao.byId(deviceId)?.let {
            authDao.upsert(it.copy(status = AuthDeviceEntity.STATUS_BLOCKED))
        }
    }

    fun unblock(deviceId: String) = viewModelScope.launch {
        authDao.byId(deviceId)?.let {
            authDao.upsert(it.copy(status = AuthDeviceEntity.STATUS_PENDING, authorizedAt = null))
        }
    }

    /** 给任意出现过的设备设置备注名 */
    fun setDeviceLabel(deviceId: String, label: String) = viewModelScope.launch {
        authDao.setLabel(deviceId, label.trim().takeIf { it.isNotEmpty() })
    }
}
