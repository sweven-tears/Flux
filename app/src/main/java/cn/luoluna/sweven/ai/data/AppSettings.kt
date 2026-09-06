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

    fun setLanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_ENABLED, enabled).apply()
        _lanEnabled.value = enabled
    }

    fun setLanPort(port: Int) {
        prefs.edit().putInt(KEY_LAN_PORT, port).apply()
        _lanPort.value = port
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_PORT = "lan_port"
    }
}
