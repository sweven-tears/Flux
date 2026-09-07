package cn.luoluna.sweven.ai.data

/** 智能路由响应模式：本机全局与每台授权设备共用同一组取值 */
object SmartModes {
    /** 极速：首轮只发实测达标的候选并用极短首字超时，一轮无成功后放宽到聊天档 */
    const val FAST = "fast"

    /** 聊天（默认）：所有轮次用常规首字超时 */
    const val CHAT = "chat"

    /** 随缘：所有轮次用长首字超时，接受慢响应 */
    const val LEISURE = "leisure"

    val ALL = listOf(FAST, CHAT, LEISURE)

    fun isKnown(mode: String): Boolean = mode in ALL

    fun labelOf(mode: String): String = when (mode) {
        FAST -> "极速"
        LEISURE -> "随缘"
        else -> "聊天"
    }
}
