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

    /** 智能路由轮巡次数：整条候选链尝试 N 轮仍全部失败才报「全部候选不可用」 */
    private val _smartRouteMaxRounds =
        MutableStateFlow(prefs.getInt(KEY_SMART_ROUTE_MAX_ROUNDS, DEFAULT_SMART_ROUTE_MAX_ROUNDS))
    val smartRouteMaxRounds: StateFlow<Int> = _smartRouteMaxRounds

    /** 局域网代理是否开放 smartAi 聚合模型 */
    private val _lanSmartAiEnabled = MutableStateFlow(prefs.getBoolean(KEY_LAN_SMART_AI, false))
    val lanSmartAiEnabled: StateFlow<Boolean> = _lanSmartAiEnabled

    /** 本机（App 内聊天）响应模式：极速 / 聊天 / 随缘 */
    private val _smartResponseMode =
        MutableStateFlow(prefs.getString(KEY_SMART_RESPONSE_MODE, null)?.takeIf { SmartModes.isKnown(it) } ?: SmartModes.CHAT)
    val smartResponseMode: StateFlow<String> = _smartResponseMode

    /** 极速档首字超时（毫秒）：仅极速模式首轮使用，超时记「慢」并放宽到聊天档 */
    private val _smartFastFirstTokenMs =
        MutableStateFlow(prefs.getInt(KEY_SMART_FAST_FIRST_TOKEN, DEFAULT_SMART_FAST_FIRST_TOKEN))
    val smartFastFirstTokenMs: StateFlow<Int> = _smartFastFirstTokenMs

    /** 随缘档首字超时（秒）：随缘模式所有轮次使用 */
    private val _smartLeisureFirstTokenSeconds =
        MutableStateFlow(prefs.getInt(KEY_SMART_LEISURE_FIRST_TOKEN, DEFAULT_SMART_LEISURE_FIRST_TOKEN))
    val smartLeisureFirstTokenSeconds: StateFlow<Int> = _smartLeisureFirstTokenSeconds

    /** 智能路由冷却阈值：候选连续失败 N 次进入冷却（降桶垫底） */
    private val _smartCooldownThreshold =
        MutableStateFlow(prefs.getInt(KEY_SMART_COOLDOWN_THRESHOLD, DEFAULT_SMART_COOLDOWN_THRESHOLD))
    val smartCooldownThreshold: StateFlow<Int> = _smartCooldownThreshold

    /** 智能路由冷却时长（秒）：冷却期内候选垫底兜底，到期回中间桶半开试错 */
    private val _smartCooldownSeconds =
        MutableStateFlow(prefs.getInt(KEY_SMART_COOLDOWN_SECONDS, DEFAULT_SMART_COOLDOWN_SECONDS))
    val smartCooldownSeconds: StateFlow<Int> = _smartCooldownSeconds

    /** 是否记录局域网请求日志（失败排查与用量统计的数据源） */
    private val _logRequestsEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOG_REQUESTS, true))
    val logRequestsEnabled: StateFlow<Boolean> = _logRequestsEnabled

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

    fun setSmartRouteMaxRounds(rounds: Int) {
        val v = rounds.coerceIn(1, 5)
        prefs.edit().putInt(KEY_SMART_ROUTE_MAX_ROUNDS, v).apply()
        _smartRouteMaxRounds.value = v
    }

    fun setLanSmartAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_SMART_AI, enabled).apply()
        _lanSmartAiEnabled.value = enabled
    }

    fun setSmartResponseMode(mode: String) {
        if (!SmartModes.isKnown(mode)) return
        prefs.edit().putString(KEY_SMART_RESPONSE_MODE, mode).apply()
        _smartResponseMode.value = mode
    }

    fun setSmartFastFirstToken(ms: Int) {
        val v = ms.coerceIn(500, 5000)
        prefs.edit().putInt(KEY_SMART_FAST_FIRST_TOKEN, v).apply()
        _smartFastFirstTokenMs.value = v
    }

    fun setSmartLeisureFirstToken(seconds: Int) {
        val v = seconds.coerceIn(30, 600)
        prefs.edit().putInt(KEY_SMART_LEISURE_FIRST_TOKEN, v).apply()
        _smartLeisureFirstTokenSeconds.value = v
    }

    fun setSmartCooldownThreshold(n: Int) {
        val v = n.coerceIn(1, 5)
        prefs.edit().putInt(KEY_SMART_COOLDOWN_THRESHOLD, v).apply()
        _smartCooldownThreshold.value = v
    }

    fun setSmartCooldownSeconds(seconds: Int) {
        val v = seconds.coerceIn(10, 600)
        prefs.edit().putInt(KEY_SMART_COOLDOWN_SECONDS, v).apply()
        _smartCooldownSeconds.value = v
    }

    fun setLogRequestsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOG_REQUESTS, enabled).apply()
        _logRequestsEnabled.value = enabled
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val DEFAULT_SMART_FIRST_TOKEN_TIMEOUT = 30
        const val DEFAULT_SMART_ROUTE_MAX_ROUNDS = 3
        const val DEFAULT_SMART_FAST_FIRST_TOKEN = 2000
        const val DEFAULT_SMART_LEISURE_FIRST_TOKEN = 120
        const val DEFAULT_SMART_COOLDOWN_THRESHOLD = 2
        const val DEFAULT_SMART_COOLDOWN_SECONDS = 60
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAST_MODEL_PROVIDER = "last_model_provider_id"
        private const val KEY_LAST_MODEL_NAME = "last_model_name"
        private const val KEY_SMART_FIRST_TOKEN_TIMEOUT = "smart_first_token_timeout_seconds"
        private const val KEY_SMART_ROUTE_MAX_ROUNDS = "smart_route_max_rounds"
        private const val KEY_LAN_SMART_AI = "lan_smart_ai_enabled"
        private const val KEY_SMART_RESPONSE_MODE = "smart_response_mode"
        private const val KEY_SMART_FAST_FIRST_TOKEN = "smart_fast_first_token_ms"
        private const val KEY_SMART_LEISURE_FIRST_TOKEN = "smart_leisure_first_token_seconds"
        private const val KEY_SMART_COOLDOWN_THRESHOLD = "smart_cooldown_threshold"
        private const val KEY_SMART_COOLDOWN_SECONDS = "smart_cooldown_seconds"
        private const val KEY_LOG_REQUESTS = "log_requests_enabled"
    }
}
