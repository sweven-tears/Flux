package cn.luoluna.sweven.ai.ui.lan

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.server.LanService
import cn.luoluna.sweven.ai.server.NetUtil
import cn.luoluna.sweven.ai.server.ServerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LanViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app as App
    private val settings = ctx.settings
    private val authDao = ctx.database.authDeviceDao()

    val lanEnabled: StateFlow<Boolean> = settings.lanEnabled
    val lanPort: StateFlow<Int> = settings.lanPort
    val lanSmartAiEnabled: StateFlow<Boolean> = settings.lanSmartAiEnabled
    val running: StateFlow<Boolean> = ServerState.running
    val serverError: StateFlow<String?> = ServerState.lastError

    /** 开关 smartAi 聚合模型对局域网客户端的开放（即时生效） */
    fun setSmartAiEnabled(on: Boolean) {
        settings.setLanSmartAiEnabled(on)
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

    fun approve(deviceId: String) = viewModelScope.launch {
        authDao.byId(deviceId)?.let {
            authDao.upsert(
                it.copy(
                    status = AuthDeviceEntity.STATUS_AUTHORIZED,
                    authorizedAt = System.currentTimeMillis(),
                )
            )
        }
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
