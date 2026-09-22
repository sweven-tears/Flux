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

    /** mDNS 主机名前缀：null = 默认广播 flux.local；如 "home" 则广播 home.flux.local */
    private val _lanHostnameLabel =
        MutableStateFlow(prefs.getString(KEY_LAN_HOSTNAME, null)?.takeIf { it.isNotBlank() })
    val lanHostnameLabel: StateFlow<String?> = _lanHostnameLabel

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

    /**
     * 局域网是否开放全部模型自由选择：
     * true = 客户端可直接按「厂商别名/模型名」选任意模型；false = 只暴露路由分组与 flux-auto 聚合路由
     */
    private val _lanOpenAllModels = MutableStateFlow(prefs.getBoolean(KEY_LAN_OPEN_ALL_MODELS, true))
    val lanOpenAllModels: StateFlow<Boolean> = _lanOpenAllModels

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

    /** 全局网络日志：记录 App 发出的每条出站 HTTP 请求（设置页查看） */
    private val _networkLogEnabled = MutableStateFlow(prefs.getBoolean(KEY_NETWORK_LOG, false))
    val networkLogEnabled: StateFlow<Boolean> = _networkLogEnabled

    /** 全局网络日志保留条数（写入时自动修剪） */
    private val _networkLogKeep = MutableStateFlow(prefs.getInt(KEY_NETWORK_LOG_KEEP, DEFAULT_NETWORK_LOG_KEEP))
    val networkLogKeep: StateFlow<Int> = _networkLogKeep

    /** 文件打开方式：图片/视频点击时交给系统应用查看（true=外部，false=内置预览） */
    private val _imageOpenExternal = MutableStateFlow(prefs.getBoolean(KEY_IMAGE_OPEN_EXTERNAL, false))
    val imageOpenExternal: StateFlow<Boolean> = _imageOpenExternal

    /**
     * 单次提问内工具调用的最大轮数：0 = 不限（模型不再请求工具时自然结束）。
     * 达到上限只停止继续调用工具，已产生的工具链与结果仍完整落库展示。
     */
    private val _toolMaxRounds =
        MutableStateFlow(prefs.getInt(KEY_TOOL_MAX_ROUNDS, DEFAULT_TOOL_MAX_ROUNDS))
    val toolMaxRounds: StateFlow<Int> = _toolMaxRounds

    fun setToolMaxRounds(rounds: Int) {
        val v = rounds.coerceIn(0, MAX_TOOL_ROUNDS_LIMIT)
        prefs.edit().putInt(KEY_TOOL_MAX_ROUNDS, v).apply()
        _toolMaxRounds.value = v
    }

    private val _videoOpenExternal = MutableStateFlow(prefs.getBoolean(KEY_VIDEO_OPEN_EXTERNAL, false))
    val videoOpenExternal: StateFlow<Boolean> = _videoOpenExternal

    fun setLanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_ENABLED, enabled).apply()
        _lanEnabled.value = enabled
    }

    fun setLanPort(port: Int) {
        prefs.edit().putInt(KEY_LAN_PORT, port).apply()
        _lanPort.value = port
    }

    /** 前缀清洗为小写，空白恢复默认；格式校验在 UI 层 */
    fun setLanHostnameLabel(label: String?) {
        val v = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(KEY_LAN_HOSTNAME, v).apply()
        _lanHostnameLabel.value = v
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

    fun setLanOpenAllModels(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LAN_OPEN_ALL_MODELS, enabled).apply()
        _lanOpenAllModels.value = enabled
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

    fun setNetworkLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NETWORK_LOG, enabled).apply()
        _networkLogEnabled.value = enabled
    }

    fun setNetworkLogKeep(count: Int) {
        val v = count.coerceIn(50, 2000)
        prefs.edit().putInt(KEY_NETWORK_LOG_KEEP, v).apply()
        _networkLogKeep.value = v
    }

    /** 联网搜索：搜索源（bing / duckduckgo / searxng），null = 默认 Bing 抓取 */
    private val _searchProvider = MutableStateFlow(prefs.getString(KEY_SEARCH_PROVIDER, null))
    val searchProvider: StateFlow<String?> = _searchProvider

    /** SearXNG 自建实例地址（选择 searxng 时必填） */
    private val _searchEndpoint =
        MutableStateFlow(prefs.getString(KEY_SEARCH_ENDPOINT, null)?.takeIf { it.isNotBlank() })
    val searchEndpoint: StateFlow<String?> = _searchEndpoint

    /** 付费搜索源 API Key：本期 UI 未暴露，给后续 provider 留位 */
    private val _searchApiKey = MutableStateFlow(prefs.getString(KEY_SEARCH_API_KEY, null))
    val searchApiKey: StateFlow<String?> = _searchApiKey

    /** 单次搜索默认条数（模型未指定 count 时使用） */
    private val _searchCount = MutableStateFlow(prefs.getInt(KEY_SEARCH_COUNT, DEFAULT_SEARCH_COUNT))
    val searchCount: StateFlow<Int> = _searchCount

    fun setSearchProvider(kind: String?) {
        val v = kind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(KEY_SEARCH_PROVIDER, v).apply()
        _searchProvider.value = v
    }

    fun setSearchEndpoint(url: String?) {
        val v = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(KEY_SEARCH_ENDPOINT, v).apply()
        _searchEndpoint.value = v
    }

    fun setSearchApiKey(key: String?) {
        val v = key?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(KEY_SEARCH_API_KEY, v).apply()
        _searchApiKey.value = v
    }

    fun setSearchCount(count: Int) {
        val v = count.coerceIn(1, 10)
        prefs.edit().putInt(KEY_SEARCH_COUNT, v).apply()
        _searchCount.value = v
    }

    fun setImageOpenExternal(external: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGE_OPEN_EXTERNAL, external).apply()
        _imageOpenExternal.value = external
    }

    fun setVideoOpenExternal(external: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_OPEN_EXTERNAL, external).apply()
        _videoOpenExternal.value = external
    }

    private val _chatPluginId = MutableStateFlow(prefs.getString(KEY_CHAT_PLUGIN, null))
    val chatPluginId: StateFlow<String?> = _chatPluginId

    fun setChatPluginId(id: String) {
        prefs.edit().putString(KEY_CHAT_PLUGIN, id).apply()
        _chatPluginId.value = id
    }

    /** 自定义（JSON）皮肤：整套样式表 JSON；null = 未自定义（回落内置预设） */
    private val _chatStyleJson = MutableStateFlow(prefs.getString(KEY_CHAT_STYLE_JSON, null))
    val chatStyleJson: StateFlow<String?> = _chatStyleJson

    fun setChatStyleJson(json: String?) {
        val normalized = json?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (normalized == null) remove(KEY_CHAT_STYLE_JSON) else putString(KEY_CHAT_STYLE_JSON, normalized)
        }.apply()
        _chatStyleJson.value = normalized
    }

    /** 聊天背景（kind/color/dim 的 JSON，见 ChatBackgroundCodec）；null = 跟随主题 */
    private val _chatBackgroundJson = MutableStateFlow(prefs.getString(KEY_CHAT_BACKGROUND, null))
    val chatBackgroundJson: StateFlow<String?> = _chatBackgroundJson

    fun setChatBackgroundJson(json: String?) {
        val normalized = json?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (normalized == null) remove(KEY_CHAT_BACKGROUND) else putString(KEY_CHAT_BACKGROUND, normalized)
        }.apply()
        _chatBackgroundJson.value = normalized
    }

    /** 聊天背景图片（压缩后的 JPEG data URL，形态同用户头像）；null = 无图片 */
    private val _chatBackgroundImage = MutableStateFlow(prefs.getString(KEY_CHAT_BACKGROUND_IMAGE, null))
    val chatBackgroundImage: StateFlow<String?> = _chatBackgroundImage

    fun setChatBackgroundImage(dataUrl: String?) {
        val v = dataUrl?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (v == null) remove(KEY_CHAT_BACKGROUND_IMAGE) else putString(KEY_CHAT_BACKGROUND_IMAGE, v)
        }.apply()
        _chatBackgroundImage.value = v
    }

    /**
     * 工具中心里启用的工具名集合（JSON 数组字符串）：新建会话默认勾选这些工具；
     * null/空 = 不启用任何工具（与旧版本行为一致）
     */
    private val _defaultToolIds = MutableStateFlow(prefs.getString(KEY_DEFAULT_TOOLS, null))
    val defaultToolIds: StateFlow<String?> = _defaultToolIds

    fun setDefaultToolIds(json: String?) {
        val v = json?.takeIf { it.isNotBlank() && it != "[]" }
        prefs.edit().putString(KEY_DEFAULT_TOOLS, v).apply()
        _defaultToolIds.value = v
    }

    private val _userName = MutableStateFlow(prefs.getString(KEY_USER_NAME, null))
    val userName: StateFlow<String?> = _userName

    /** 用户头像：图片内容的 data URL（压缩后），null 用首字合成 */
    private val _userAvatar = MutableStateFlow(prefs.getString(KEY_USER_AVATAR, null))
    val userAvatar: StateFlow<String?> = _userAvatar

    fun setUserName(name: String?) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
        _userName.value = name
    }

    fun setUserAvatar(dataUrl: String?) {
        prefs.edit().putString(KEY_USER_AVATAR, dataUrl).apply()
        _userAvatar.value = dataUrl
    }

    companion object {
        const val DEFAULT_PORT = 8787

        /** mDNS 默认主机名（flux.local）；自定义前缀为 <前缀>.flux.local */
        const val DEFAULT_LAN_HOST = "flux"
        const val DEFAULT_SMART_FIRST_TOKEN_TIMEOUT = 30
        const val DEFAULT_SMART_ROUTE_MAX_ROUNDS = 3
        const val DEFAULT_SMART_FAST_FIRST_TOKEN = 2000
        const val DEFAULT_SMART_LEISURE_FIRST_TOKEN = 120
        const val DEFAULT_SMART_COOLDOWN_THRESHOLD = 2
        const val DEFAULT_SMART_COOLDOWN_SECONDS = 60

        /** 全局网络日志默认保留条数 */
        const val DEFAULT_NETWORK_LOG_KEEP = 300

        /** 联网搜索默认返回条数 */
        const val DEFAULT_SEARCH_COUNT = 5

        /** 工具调用轮数上限默认值：0 = 不限（不主动打断模型的工具循环，由模型自行收敛或用户点停止） */
        const val DEFAULT_TOOL_MAX_ROUNDS = 0

        /** 工具调用轮数上限可配置的最大值（UI 校验与 setter 钳制共用） */
        const val MAX_TOOL_ROUNDS_LIMIT = 50

        private const val KEY_TOOL_MAX_ROUNDS = "tool_max_rounds"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val KEY_SEARCH_ENDPOINT = "search_endpoint"
        private const val KEY_SEARCH_API_KEY = "search_api_key"
        private const val KEY_SEARCH_COUNT = "search_count"
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAN_HOSTNAME = "lan_hostname_label"
        private const val KEY_LAST_MODEL_PROVIDER = "last_model_provider_id"
        private const val KEY_LAST_MODEL_NAME = "last_model_name"
        private const val KEY_SMART_FIRST_TOKEN_TIMEOUT = "smart_first_token_timeout_seconds"
        private const val KEY_SMART_ROUTE_MAX_ROUNDS = "smart_route_max_rounds"
        private const val KEY_LAN_SMART_AI = "lan_smart_ai_enabled"
        private const val KEY_LAN_OPEN_ALL_MODELS = "lan_open_all_models"
        private const val KEY_SMART_RESPONSE_MODE = "smart_response_mode"
        private const val KEY_SMART_FAST_FIRST_TOKEN = "smart_fast_first_token_ms"
        private const val KEY_SMART_LEISURE_FIRST_TOKEN = "smart_leisure_first_token_seconds"
        private const val KEY_SMART_COOLDOWN_THRESHOLD = "smart_cooldown_threshold"
        private const val KEY_SMART_COOLDOWN_SECONDS = "smart_cooldown_seconds"
        private const val KEY_LOG_REQUESTS = "log_requests_enabled"
        private const val KEY_NETWORK_LOG = "network_log_enabled"
        private const val KEY_NETWORK_LOG_KEEP = "network_log_keep"
        private const val KEY_IMAGE_OPEN_EXTERNAL = "image_open_external"
        private const val KEY_VIDEO_OPEN_EXTERNAL = "video_open_external"
        private const val KEY_CHAT_PLUGIN = "chat_ui_plugin"
        private const val KEY_CHAT_STYLE_JSON = "chat_style_json"
        private const val KEY_CHAT_BACKGROUND = "chat_background_json"
        private const val KEY_CHAT_BACKGROUND_IMAGE = "chat_background_image"
        private const val KEY_DEFAULT_TOOLS = "default_tool_ids"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR = "user_avatar"
    }
}
