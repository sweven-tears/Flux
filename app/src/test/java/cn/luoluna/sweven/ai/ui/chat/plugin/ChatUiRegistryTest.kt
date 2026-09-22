package cn.luoluna.sweven.ai.ui.chat.plugin

import cn.luoluna.sweven.ai.ui.chat.style.ChatStylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 注册表契约：默认 id = 扁平；历史 id 与未知 id 都要解析出可用插件（不崩）。 */
class ChatUiRegistryTest {

    @Test
    fun `默认 id 就是扁平的 id`() {
        assertEquals("flat", ChatUiRegistry.DEFAULT_ID)
        assertEquals(ChatStylePreset.FLAT.id, ChatUiRegistry.DEFAULT_ID)
    }

    @Test
    fun `未知 id 回落默认插件`() {
        assertEquals("flat", ChatUiRegistry.resolve("没注册过的皮肤").id)
        assertEquals("flat", ChatUiRegistry.resolve(ChatUiRegistry.DEFAULT_ID).id)
    }

    @Test
    fun `历史 id default 解析为气泡插件`() {
        assertEquals("bubble", ChatUiRegistry.resolve("default").id)
    }

    @Test
    fun `三个预设插件都自带样式表 custom 不带`() {
        listOf("flat", "mono", "bubble").forEach { id ->
            assertNotNull("插件 $id 必须自带样式表", ChatUiRegistry.resolve(id).chatStyle)
        }
        assertNull(
            "custom 的样式表由 AppSettings.chatStyleJson 解出，插件本身不提供",
            ChatUiRegistry.resolve(ChatUiRegistry.CUSTOM_ID).chatStyle,
        )
    }
}
