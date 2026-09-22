package cn.luoluna.sweven.ai.ui.chat.plugin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 皮肤插件契约测试（纯 JVM，不需要 Compose 运行时）。
 *
 * 带工具调用的回复在列表里走 [ChatUiPlugin.toolTurn] 槽位，而不是 [ChatUiPlugin.messageBubble]
 * （见 [ChatUiDefaults.renderToolTurn] / `ChatUiDefaults.MessageList`）。只覆写 messageBubble
 * 的皮肤会让这类回复回落默认气泡风格，用户看到的现象就是「界面风格设置失效」。
 * 本测试把这条契约钉住：内置皮肤都必须成对覆写。
 */
class ChatUiPluginContractTest {

    private val builtInPlugins = listOf(
        DefaultChatUiPlugin,
        FlatChatUiPlugin,
        MonoChatUiPlugin,
    )

    @Test
    fun `覆写 messageBubble 的内置皮肤必须同时覆写 toolTurn`() {
        builtInPlugins.forEach { plugin ->
            if (plugin.messageBubble != null) {
                assertNotNull(
                    "插件「${plugin.displayName}」(${plugin.id}) 覆写了 messageBubble 但未覆写 toolTurn，" +
                        "带工具调用的回复会回落默认气泡风格",
                    plugin.toolTurn,
                )
            }
        }
    }

    @Test
    fun `插件 id 与展示名不为空`() {
        builtInPlugins.forEach { plugin ->
            assertNotNull(plugin.id.takeIf { it.isNotBlank() })
            assertNotNull(plugin.displayName.takeIf { it.isNotBlank() })
        }
    }

    /**
     * 单一渲染核心的纪律：内置皮肤必须只提供样式表，不得再各自绘制气泡。
     * 一旦有人重新覆写这些绘制槽位，就会出现"两套绘制、两套换肤"的维护债。
     */
    @Test
    fun `内置皮肤只提供样式表 不覆写绘制槽位`() {
        builtInPlugins.forEach { plugin ->
            assertNull(
                "插件「${plugin.displayName}」(${plugin.id}) 覆写了 messageBubble，应改为只提供 chatStyle",
                plugin.messageBubble,
            )
            assertNull(
                "插件「${plugin.displayName}」(${plugin.id}) 覆写了 toolTurn，应改为只提供 chatStyle",
                plugin.toolTurn,
            )
            assertNull(
                "插件「${plugin.displayName}」(${plugin.id}) 覆写了 streamingBubble，应改为只提供 chatStyle",
                plugin.streamingBubble,
            )
            assertNotNull(
                "插件「${plugin.displayName}」(${plugin.id}) 必须提供 chatStyle",
                plugin.chatStyle,
            )
        }
    }
}