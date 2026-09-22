package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 样式表编解码：往返一致、部分覆盖语义（未写的字段继承气泡预设里该角色的样式）、
 * base 传导到角色、未知枚举回落、整体非法返回 null。
 */
class ChatStyleCodecTest {

    @Test
    fun `往返编解码保持一致`() {
        val config = ChatStyleConfig.terminal()
        assertEquals(config, ChatStyleCodec.decode(ChatStyleCodec.encode(config)))
    }

    @Test
    fun `气泡预设往返一致`() {
        val config = ChatStyleConfig.bubble()
        assertEquals(config, ChatStyleCodec.decode(ChatStyleCodec.encode(config)))
    }

    @Test
    fun `部分覆盖 未写的字段继承预设里该角色的样式`() {
        val config = ChatStyleCodec.decode("""{"user":{"cornerRadius":8}}""")
        assertEquals(8.dp, config!!.user.cornerRadius)
        // 关键：没写的字段不该被清成硬默认，而是继承「气泡预设的用户样式」
        assertEquals(BubbleFillRole.PRIMARY, config.user.fill)
        assertEquals(9.dp, config.user.paddingV)
        // 其它角色完全不受影响
        assertEquals(ChatStyleConfig.bubble().ai, config.ai)
    }

    @Test
    fun `base 的改动会传导到未单独写样式的角色`() {
        val config = ChatStyleCodec.decode("""{"base":{"cornerRadius":2,"cutCornerSize":14}}""")
        assertEquals(2.dp, config!!.base.cornerRadius)
        assertEquals(2.dp, config.user.cornerRadius)
        assertEquals(2.dp, config.ai.cornerRadius)
        assertEquals(14.dp, config.ai.cutCornerSize)
        // 角色自身特征（用户主色）不被 base 抹掉
        assertEquals(BubbleFillRole.PRIMARY, config.user.fill)
    }

    @Test
    fun `未知枚举回落默认值 不崩`() {
        val config = ChatStyleCodec.decode(
            """{"base":{"fill":"不存在的颜色","stroke":"NOPE","cutCorner":"TOP_START"}}"""
        )
        assertEquals(BubbleStyle().fill, config!!.base.fill)
        assertEquals(BubbleStyle().stroke, config.base.stroke)
        assertEquals(BubbleStyle().cutCorner, config.base.cutCorner)
    }

    @Test
    fun `非法 JSON 返回 null 由调用方回落预设`() {
        assertNull(ChatStyleCodec.decode("not json"))
        assertNull(ChatStyleCodec.decode(""))
        assertNull(ChatStyleCodec.decode("[]"))
        assertNull(ChatStyleCodec.decode(null))
    }

    @Test
    fun `只有 profile 的 JSON 也能解出`() {
        val config = ChatStyleCodec.decode("""{"userProfile":{"align":"CENTER","maxWidthFraction":0.5}}""")
        assertEquals(RoleAlign.CENTER, config!!.userProfile.align)
        assertEquals(0.5f, config.userProfile.maxWidthFraction, 0.0001f)
        assertTrue(config.userProfile.showHeader)
        // 样式部分整体继承预设
        assertEquals(ChatStyleConfig.bubble().user, config.user)
    }

    @Test
    fun `编码结果可读 含关键字段名`() {
        val json = ChatStyleCodec.encode(ChatStyleConfig.bubble())
        assertTrue(json.contains("cornerRadius"))
        assertTrue(json.contains("userProfile"))
        assertTrue(json.contains("align"))
    }
}
