package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 气泡样式契约：默认值可预知、copy 覆盖语义正确、切角几何收敛（不产生自交路径）。
 * 纯 JVM：颜色只存角色枚举，几何只算 Float。
 */
class BubbleStyleTest {

    @Test
    fun `默认样式是可预知的气泡基底`() {
        val style = BubbleStyle()
        assertEquals(16.dp, style.cornerRadius)
        assertEquals(0.dp, style.cutCornerSize)
        assertEquals(BubbleFillRole.SURFACE_VARIANT, style.fill)
        assertEquals(12.dp, style.paddingH)
        assertEquals(8.dp, style.paddingV)
        assertFalse(style.monospace)
        assertFalse(style.dividerBottom)
    }

    @Test
    fun `copy 只改指定字段其余继承基底`() {
        val base = BubbleStyle()
        val user = base.copy(fill = BubbleFillRole.PRIMARY, text = BubbleTextRole.ON_PRIMARY, paddingV = 9.dp)
        assertEquals(BubbleFillRole.PRIMARY, user.fill)
        assertEquals(9.dp, user.paddingV)
        // 未覆盖的字段必须与基底一致（DRY：角色样式不允许整份重写）
        assertEquals(base.cornerRadius, user.cornerRadius)
        assertEquals(base.paddingH, user.paddingH)
        assertEquals(base.cutCornerSize, user.cutCornerSize)
    }

    @Test
    fun `相同参数的两个样式相等 保证 remember 键稳定`() {
        assertEquals(BubbleStyle().copy(cornerRadius = 4.dp), BubbleStyle().copy(cornerRadius = 4.dp))
    }

    @Test
    fun `切角几何在正常尺寸下原样返回`() {
        val g = bubbleOutline(widthPx = 300f, heightPx = 100f, radiusPx = 16f, cutPx = 12f)
        assertEquals(16f, g.radiusPx, 0.001f)
        assertEquals(12f, g.cutPx, 0.001f)
    }

    @Test
    fun `半径被收敛到短边一半 不产生自交`() {
        val g = bubbleOutline(widthPx = 40f, heightPx = 20f, radiusPx = 16f, cutPx = 8f)
        assertEquals(10f, g.radiusPx, 0.001f)
    }

    @Test
    fun `切角尺寸被收敛到可用边长 且不为负`() {
        // 宽 40 高 30：短边一半 = 15 → 半径收敛为 15；可用边 = min(40-15, 30-15) = 15 → 切角上限 15
        val g = bubbleOutline(widthPx = 40f, heightPx = 30f, radiusPx = 16f, cutPx = 999f)
        assertEquals(15f, g.radiusPx, 0.001f)
        assertEquals(15f, g.cutPx, 0.001f)
        // 切角为 0 时保持 0（不因收敛变成别的值）
        assertEquals(0f, bubbleOutline(10f, 10f, 20f, 0f).cutPx, 0.001f)
    }

    @Test
    fun `尺寸为零时不产生负几何`() {
        val g = bubbleOutline(widthPx = 0f, heightPx = 0f, radiusPx = 16f, cutPx = 12f)
        assertEquals(0f, g.radiusPx, 0.001f)
        assertEquals(0f, g.cutPx, 0.001f)
    }
}
