package cn.luoluna.sweven.ai

import android.app.Application
import cn.luoluna.sweven.ai.data.AppSettings
import cn.luoluna.sweven.ai.data.db.AppDatabase
import cn.luoluna.sweven.ai.data.repo.ModelRouter
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.server.DeviceAuthManager
import cn.luoluna.sweven.ai.server.Notifications

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
    }
}
