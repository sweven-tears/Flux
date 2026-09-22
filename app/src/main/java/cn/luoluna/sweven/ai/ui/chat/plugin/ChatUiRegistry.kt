package cn.luoluna.sweven.ai.ui.chat.plugin

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import cn.luoluna.sweven.ai.ui.chat.style.ChatStylePreset

/**
 * 聊天页 UI 插件注册表：mutableStateMap 支撑，运行时注册即触发 UI 更新。
 * 内置插件静态注册；后续 JSON 参数化皮肤、DexClassLoader 外部插件只需往这里注册，
 * 聊天页零改动。
 */
object ChatUiRegistry {
    private val plugins: SnapshotStateMap<String, ChatUiPlugin> = mutableStateMapOf()
    fun register(plugin: ChatUiPlugin) {
        plugins[plugin.id] = plugin
    }

    fun unregister(id: String) {
        plugins.remove(id)
    }

    fun get(id: String): ChatUiPlugin? = plugins[id]

    fun all(): List<ChatUiPlugin> = plugins.values.toList()

    /**
     * 按 id 取插件。历史 id（"default" → 气泡）与未注册 id 都先归一化到内置预设，
     * 最后兜底默认（扁平）——外部插件被移除后聊天页仍可用。
     */
    fun resolve(id: String): ChatUiPlugin = plugins[id]
        ?: plugins[ChatStylePreset.of(id).id]
        ?: plugins[DEFAULT_ID]
        ?: error("default chat ui plugin not registered")

    /** 默认风格 id：扁平（改默认只改这里 + [ChatStylePreset] 的回落值） */
    const val DEFAULT_ID = "flat"

    /** 自定义（JSON）皮肤的 id：样式表由宿主从 `AppSettings.chatStyleJson` 解出 */
    const val CUSTOM_ID = "custom"

    init {
        register(FlatChatUiPlugin)
        register(MonoChatUiPlugin)
        register(BubbleChatUiPlugin)
        register(CustomChatUiPlugin)
    }
}

/**
 * 自定义（JSON）皮肤：本身不提供样式表，只作为"选择标记"。
 * 真正的样式表由宿主从 `AppSettings.chatStyleJson` 解出（见 ChatScreen 的 styleOverride）；
 * 解不出（未设置/非法）时 `ChatUiEnv.chatStyle` 会回落内置预设。
 */
object CustomChatUiPlugin : ChatUiPlugin {
    override val id = ChatUiRegistry.CUSTOM_ID
    override val displayName = "自定义（JSON）"
    override val description = "用一份 JSON 样式表自定义气泡外观"
}
