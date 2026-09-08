package cn.luoluna.sweven.ai.ui.chat.plugin

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

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

    /** 按 id 取插件，未注册（如外部插件被移除）回退默认 */
    fun resolve(id: String): ChatUiPlugin = plugins[id] ?: plugins[DEFAULT_ID]
        ?: error("default chat ui plugin not registered")

    const val DEFAULT_ID = "default"

    init {
        register(DefaultChatUiPlugin)
        register(FlatChatUiPlugin)
        register(MonoChatUiPlugin)
    }
}
