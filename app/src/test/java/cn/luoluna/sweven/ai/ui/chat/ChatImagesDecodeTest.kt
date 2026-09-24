package cn.luoluna.sweven.ai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImagesDecodeTest {

    @Test
    fun `大于目标尺寸时按 2 的幂降采样`() {
        assertEquals(2, decodeSampleSize(3000, 2000, 1280))
        assertEquals(4, decodeSampleSize(8000, 6000, 1280))
    }

    @Test
    fun `不超过目标尺寸不降采样`() {
        assertEquals(1, decodeSampleSize(1280, 720, 1280))
        assertEquals(1, decodeSampleSize(720, 1280, 1280))
    }

    @Test
    fun `非法尺寸不崩溃并返回最小样本`() {
        assertEquals(1, decodeSampleSize(0, 0, 1280))
        assertEquals(1, decodeSampleSize(-2, 3000, 1280))
        assertEquals(1, decodeSampleSize(3000, -2, 1280))
        assertEquals(1, decodeSampleSize(3000, 2000, 0))
    }
}
