package cn.luoluna.sweven.ai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DataUrlDecodeStateTest {

    @Test
    fun `空 URL 不解码`() {
        assertEquals(DataUrlDecodeState.Idle(), dataUrlDecodeState(null, true, null, false))
        assertEquals(DataUrlDecodeState.Idle(), dataUrlDecodeState("", true, null, false))
    }

    @Test
    fun `非 data URL 视为失败`() {
        assertEquals(DataUrlDecodeState.Failed(), dataUrlDecodeState("https://example.com/a.jpg", true, null, false))
    }

    @Test
    fun `解码完成前不算失败`() {
        assertEquals(DataUrlDecodeState.Loading(), dataUrlDecodeState("data:image/jpeg;base64,AAAA", true, null, false))
    }

    @Test
    fun `解码完成且无图才算失败`() {
        assertEquals(DataUrlDecodeState.Failed(), dataUrlDecodeState("data:image/jpeg;base64,AAAA", true, null, true))
    }

    @Test
    fun `有图即成功`() {
        val bitmap = Any()
        assertEquals(DataUrlDecodeState.Ready(bitmap), dataUrlDecodeState("data:image/jpeg;base64,AAAA", true, bitmap, true))
    }
}
