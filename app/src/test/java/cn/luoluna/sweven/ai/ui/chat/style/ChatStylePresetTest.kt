package cn.luoluna.sweven.ai.ui.chat.style

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预设风格表契约：默认 = 扁平；展示名给编辑页用；历史 id "default"（旧版「默认」= 气泡）仍要能解析回气泡。
 */
class ChatStylePresetTest {

    @Test
    fun `三个预设的 id 与展示名`() {
        assertEquals("flat", ChatStylePreset.FLAT.id)
        assertEquals("扁平", ChatStylePreset.FLAT.displayName)
        assertEquals("mono", ChatStylePreset.TERMINAL.id)
        assertEquals("等宽", ChatStylePreset.TERMINAL.displayName)
        assertEquals("bubble", ChatStylePreset.BUBBLE.id)
        assertEquals("气泡", ChatStylePreset.BUBBLE.displayName)
    }

    @Test
    fun `未设置与未知 id 回落扁平`() {
        assertEquals(ChatStylePreset.FLAT, ChatStylePreset.of(null))
        assertEquals(ChatStylePreset.FLAT, ChatStylePreset.of(""))
        assertEquals(ChatStylePreset.FLAT, ChatStylePreset.of("某个第三方皮肤"))
    }

    @Test
    fun `历史 id default 仍解析为气泡`() {
        assertEquals(ChatStylePreset.BUBBLE, ChatStylePreset.of("default"))
    }

    @Test
    fun `每个预设 id 都能解析回自己`() {
        ChatStylePreset.entries.forEach { preset ->
            assertEquals(preset, ChatStylePreset.of(preset.id))
        }
    }
}
