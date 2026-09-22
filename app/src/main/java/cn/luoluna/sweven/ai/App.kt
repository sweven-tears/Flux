package cn.luoluna.sweven.ai

import android.app.Application
import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.db.AppDatabase
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.net.NetworkLog
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.server.DeviceAuthManager
import cn.luoluna.sweven.ai.server.Notifications
import cn.luoluna.sweven.ai.server.VideoJobManager
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.tools.custom.CustomToolLoader

class App : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val settings: AppSettings by lazy { AppSettings(this) }
    val providerClient: ProviderClient by lazy { ProviderClient() }
    val modelRouter: ModelRouter by lazy {
        ModelRouter(database.providerDao(), database.apiKeyDao(), database.modelDao())
    }
    val deviceAuth: DeviceAuthManager by lazy {
        DeviceAuthManager(this, database.authDeviceDao())
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // 全局网络日志（出站请求打点；开关在设置页，关闭时零开销）
        NetworkLog.init(this)
        // 本地工具注册（时间信息 / 剪贴板 / JS 执行器 / 联网搜索）
        ToolRegistry.init(this)
        // 自定义工具装载（custom_tools 表 → 注册表，增删改即时同步）
        CustomToolLoader.start(this)
        // 恢复上次未完成的视频生成任务（进程被杀后重新拉起轮询与前台服务）
        VideoJobManager.get(this).resumeAll(startService = true)
    }
}
