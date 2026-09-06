package cn.luoluna.sweven.ai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lanEnabled = MutableStateFlow(prefs.getBoolean(KEY_LAN_ENABLED, false))
    val lanEnabled: StateFlow<Boolean> = _lanEnabled

    private val _lanPort = MutableStateFlow(prefs.getInt(KEY_LAN_PORT, DEFAULT_PORT))
    val lanPort: StateFlow<Int> = _lanPort

    private val _lastModelProviderId =
        MutableStateFlow(prefs.getLong(KEY_LAST_MODEL_PROVIDER, -1L).takeIf { it > 0 })
    val lastModelProviderId: StateFlow<Long?> = _lastModelProviderId

    private val _lastModelName = MutableStateFlow(prefs.getString(KEY_LAST_MODEL_NAME, null))
    val lastModelName: StateFlow<String?> = _lastModelName

    fun setLanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_ENABLED, enabled).apply()
        _lanEnabled.value = enabled
    }

    fun setLanPort(port: Int) {
        prefs.edit().putInt(KEY_LAN_PORT, port).apply()
        _lanPort.value = port
    }

    fun setLastModel(providerId: Long, modelName: String) {
        prefs.edit()
            .putLong(KEY_LAST_MODEL_PROVIDER, providerId)
            .putString(KEY_LAST_MODEL_NAME, modelName)
            .apply()
        _lastModelProviderId.value = providerId
        _lastModelName.value = modelName
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAST_MODEL_PROVIDER = "last_model_provider_id"
        private const val KEY_LAST_MODEL_NAME = "last_model_name"
    }
}
