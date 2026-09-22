package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样式表契约：按角色取样式/档案、三预设互不相同、未知角色回落 AI、优先级正确、system 是占位。 */
class ChatStyleConfigTest {

    @Test
    fun `按角色取样式 用户与助手必须视觉可区分`() {
        val config = ChatStyleConfig.bubble()
        assertEquals(BubbleFillRole.PRIMARY, config.styleFor(Role.USER).fill)
        assertEquals(BubbleTextRole.ON_PRIMARY, config.styleFor(Role.USER).text)
        assertEquals(BubbleFillRole.SURFACE_VARIANT, config.styleFor(Role.ASSISTANT).fill)
        assertNotEquals(config.styleFor(Role.USER), config.styleFor(Role.ASSISTANT))
    }

    @Test
    fun `未知角色的样式与档案回落 AI`() {
        val config = ChatStyleConfig.bubble()
        assertEquals(config.styleFor(Role.ASSISTANT), config.styleFor(null))
        assertEquals(config.styleFor(Role.ASSISTANT), config.styleFor("thinking"))
        assertEquals(config.aiProfile, config.profileFor(null))
    }

    @Test
    fun `角色布局档案决定对齐与最大宽度`() {
        val config = ChatStyleConfig.bubble()
        assertEquals(RoleAlign.END, config.profileFor(Role.USER).align)
        assertEquals(0.75f, config.profileFor(Role.USER).maxWidthFraction, 0.0001f)
        assertEquals(RoleAlign.START, config.profileFor(Role.ASSISTANT).align)
        assertEquals(0.8f, config.profileFor(Role.ASSISTANT).maxWidthFraction, 0.0001f)
        assertFalse(config.systemProfile.showHeader)
    }

    @Test
    fun `三预设的基底互不相同`() {
        val bubble = ChatStyleConfig.bubble()
        val terminal = ChatStyleConfig.terminal()
        val flat = ChatStyleConfig.flat()
        assertEquals(16.dp, bubble.base.cornerRadius)
        assertEquals(0.dp, bubble.base.cutCornerSize)
        assertEquals(4.dp, terminal.base.cornerRadius)
        assertEquals(12.dp, terminal.base.cutCornerSize)
        assertEquals(BubbleStrokeRole.OUTLINE_VARIANT, terminal.base.stroke)
        assertTrue(terminal.base.monospace)
        assertEquals(BubbleFillRole.TRANSPARENT, flat.base.fill)
        assertTrue(flat.base.dividerBottom)
    }

    @Test
    fun `优先级 角色覆盖 大于 预设 大于 默认值`() {
        val preset = ChatStyleConfig.bubble()
        val custom = preset.copy(user = preset.user.copy(fill = BubbleFillRole.SECONDARY_CONTAINER))
        assertEquals(BubbleFillRole.SECONDARY_CONTAINER, custom.styleFor(Role.USER).fill)
        assertEquals(preset.user.cornerRadius, custom.styleFor(Role.USER).cornerRadius)
        assertEquals(preset.ai, custom.styleFor(Role.ASSISTANT))
    }

    @Test
    fun `system 是占位样式 可取值但不参与渲染`() {
        val config = ChatStyleConfig.bubble()
        // 占位：库里不产生 system 气泡，配置存在只是为将来渲染预留；取值不应崩溃
        assertEquals(BubbleTextRole.ON_SURFACE_VARIANT, config.styleFor(Role.SYSTEM).text)
        assertEquals(RoleAlign.CENTER, config.profileFor(Role.SYSTEM).align)
    }

    @Test
    fun `预设配置相等 可作常量比较`() {
        assertEquals(ChatStylePreset.BUBBLE.config(), ChatStylePreset.BUBBLE.config())
    }
}
