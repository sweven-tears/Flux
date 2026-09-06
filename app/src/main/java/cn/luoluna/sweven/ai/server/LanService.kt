package cn.luoluna.sweven.ai.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.net.ProviderClient
import kotlinx.coroutines.flow.MutableStateFlow

/** 服务器运行状态，供 UI 观察 */
object ServerState {
    val running = MutableStateFlow(false)
    val activePort = MutableStateFlow(0)
    val lastError = MutableStateFlow<String?>(null)
}

object ServerRunner {
    private var server: LanServer? = null

    fun start(context: Context, port: Int) {
        stop()
        ServerState.lastError.value = null
        val app = context.applicationContext as App
        val s = LanServer(
            port = port,
            router = app.modelRouter,
            auth = app.deviceAuth,
            client = app.providerClient,
            providerDao = app.database.providerDao(),
            modelDao = app.database.modelDao(),
            smartRouteDao = app.database.smartRouteDao(),
            settings = app.settings,
            authDao = app.database.authDeviceDao(),
        )
        server = s
        try {
            s.start()
            ServerState.running.value = true
            ServerState.activePort.value = port
        } catch (e: Exception) {
            ServerState.lastError.value = "启动失败: ${e.message ?: e.javaClass.simpleName}"
            server = null
        }
    }

    fun stop() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        ServerState.running.value = false
        ServerState.activePort.value = 0
    }
}

class LanService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as App
        val port = app.settings.lanPort.value
        val ip = NetUtil.lanIps().firstOrNull() ?: "127.0.0.1"
        val notification = Notifications.serverNotification(this, "http://$ip:$port/v1")
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SERVER,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        ServerRunner.start(applicationContext, port)
        return START_STICKY
    }

    override fun onDestroy() {
        ServerRunner.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "cn.luoluna.sweven.ai.action.STOP"

        fun startIntent(context: Context): Intent = Intent(context, LanService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, LanService::class.java).setAction(ACTION_STOP)
    }
}
