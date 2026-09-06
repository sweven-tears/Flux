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

    // 0 为无效值；-1 表示智能路由 smartAi（聚合模型，无真实厂商）
    private val _lastModelProviderId =
        MutableStateFlow(prefs.getLong(KEY_LAST_MODEL_PROVIDER, 0L).takeIf { it != 0L })
    val lastModelProviderId: StateFlow<Long?> = _lastModelProviderId

    private val _lastModelName = MutableStateFlow(prefs.getString(KEY_LAST_MODEL_NAME, null))
    val lastModelName: StateFlow<String?> = _lastModelName

    /** 智能路由首字超时（秒）：候选模型超过该时长未输出首个内容则切换下一个 */
    private val _smartFirstTokenTimeoutSeconds =
        MutableStateFlow(prefs.getInt(KEY_SMART_FIRST_TOKEN_TIMEOUT, DEFAULT_SMART_FIRST_TOKEN_TIMEOUT))
    val smartFirstTokenTimeoutSeconds: StateFlow<Int> = _smartFirstTokenTimeoutSeconds

    /** 局域网代理是否开放 smartAi 聚合模型 */
    private val _lanSmartAiEnabled = MutableStateFlow(prefs.getBoolean(KEY_LAN_SMART_AI, false))
    val lanSmartAiEnabled: StateFlow<Boolean> = _lanSmartAiEnabled

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

    fun setSmartFirstTokenTimeout(seconds: Int) {
        val v = seconds.coerceIn(5, 300)
        prefs.edit().putInt(KEY_SMART_FIRST_TOKEN_TIMEOUT, v).apply()
        _smartFirstTokenTimeoutSeconds.value = v
    }

    fun setLanSmartAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_SMART_AI, enabled).apply()
        _lanSmartAiEnabled.value = enabled
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val DEFAULT_SMART_FIRST_TOKEN_TIMEOUT = 30
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAST_MODEL_PROVIDER = "last_model_provider_id"
        private const val KEY_LAST_MODEL_NAME = "last_model_name"
        private const val KEY_SMART_FIRST_TOKEN_TIMEOUT = "smart_first_token_timeout_seconds"
        private const val KEY_LAN_SMART_AI = "lan_smart_ai_enabled"
    }
}
