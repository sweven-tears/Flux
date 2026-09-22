package cn.luoluna.sweven.ai.ui.chat.style

import org.junit.Assert.assertEquals
import org.junit.Test

/** 聊天背景配置：容错解码（坏数据一律回落默认，不崩）、未知枚举回落、dim 收敛 0..1、往返一致。 */
class ChatBackgroundCodecTest {

    @Test
    fun `未设置回落跟随主题`() {
        assertEquals(ChatBackgroundStyle(), ChatBackgroundCodec.decode(null))
        assertEquals(ChatBackgroundKind.THEME, ChatBackgroundCodec.decode("").kind)
    }

    @Test
    fun `坏数据不崩 回落默认`() {
        assertEquals(ChatBackgroundStyle(), ChatBackgroundCodec.decode("不是 JSON"))
        assertEquals(ChatBackgroundStyle(), ChatBackgroundCodec.decode("[1,2,3]"))
    }

    @Test
    fun `未知枚举名回落默认值`() {
        val style = ChatBackgroundCodec.decode("""{"kind":"NEON","color":"RAINBOW"}""")
        assertEquals(ChatBackgroundKind.THEME, style.kind)
        assertEquals(BubbleFillRole.SURFACE_VARIANT, style.color)
    }

    @Test
    fun `dim 收敛到 0 到 1`() {
        assertEquals(0f, ChatBackgroundCodec.decode("""{"dim":-3}""").dim, 0.0001f)
        assertEquals(1f, ChatBackgroundCodec.decode("""{"dim":9}""").dim, 0.0001f)
    }

    @Test
    fun `往返编解码保持一致`() {
        val style = ChatBackgroundStyle(
            kind = ChatBackgroundKind.IMAGE,
            color = BubbleFillRole.SURFACE_CONTAINER_HIGH,
            dim = 0.35f,
        )
        assertEquals(style, ChatBackgroundCodec.decode(ChatBackgroundCodec.encode(style)))
    }
}
