package cn.luoluna.sweven.ai.ui.chat.style

import cn.luoluna.sweven.ai.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息槽位契约：用户消息与助手消息**走同一个组件**（`ChatUiDefaults.MessageBubble`），
 * 差别只有「用哪份气泡样式 / 哪份布局档案 / 操作行是否右对齐」三项，全部由 [ChatStyleConfig.slotFor] 决定。
 * 任何一边私自换样式、私自 if(isUser) 都会让这里变红。
 */
class MessageSlotTest {

    @Test
    fun `用户与助手用同一套槽位 只有样式 档案 对齐不同`() {
        ChatStylePreset.entries.forEach { preset ->
            val config = preset.config()
            val user = config.slotFor(Role.USER)
            val ai = config.slotFor(Role.ASSISTANT)
            assertEquals("$preset 的用户样式", config.user, user.style)
            assertEquals("$preset 的用户档案", config.userProfile, user.profile)
            assertTrue("$preset 的用户消息操作行应右对齐", user.alignEnd)
            assertEquals("$preset 的助手样式", config.ai, ai.style)
            assertEquals("$preset 的助手档案", config.aiProfile, ai.profile)
            assertFalse("$preset 的助手消息操作行应左对齐", ai.alignEnd)
            assertNotEquals("$preset 下用户与助手必须视觉可区分", user.style, ai.style)
        }
    }

    @Test
    fun `工具结果用工具样式 未知角色回落助手`() {
        val config = ChatStyleConfig.flat()
        val tool = config.slotFor(Role.TOOL)
        assertEquals(config.tool, tool.style)
        assertEquals(config.aiProfile, tool.profile)
        assertFalse(tool.alignEnd)
        assertEquals(config.ai, config.slotFor(null).style)
        assertEquals(config.ai, config.slotFor("thinking").style)
        assertEquals(config.aiProfile, config.slotFor("thinking").profile)
    }
}
