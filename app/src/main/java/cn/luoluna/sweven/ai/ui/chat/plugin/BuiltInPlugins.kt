package cn.luoluna.sweven.ai.ui.chat.plugin

import cn.luoluna.sweven.ai.ui.chat.style.ChatStyleConfig
import cn.luoluna.sweven.ai.ui.chat.style.ChatStylePreset

/**
 * 内置风格插件：**只提供样式表**，所有绘制统一走 [ChatUiDefaults] + ChatBubble。
 *
 * 历史包袱说明：本文件曾让每个皮肤各自复制一整套气泡/工具链/流式绘制（三份近似代码），
 * 结果是换肤要改三处、带工具调用的回复还容易回落默认风格。现在皮肤 = 一份 [ChatStyleConfig]，
 * 新增风格只需在 [ChatStylePreset] 加一个预设（或写一份 JSON，见 custom 皮肤）。
 */
object DefaultChatUiPlugin : ChatUiPlugin {
    override val id = ChatUiRegistry.DEFAULT_ID
    override val displayName = "默认"
    override val description = "气泡式：圆角 + 无尾巴，用户主色、助手浅灰"
    override val chatStyle: ChatStyleConfig = ChatStylePreset.BUBBLE.config()
}

/** 平铺风格（ChatGPT 式）：助手无气泡全宽、用户浅色气泡右对齐 */
object FlatChatUiPlugin : ChatUiPlugin {
    override val id = ChatStylePreset.FLAT.id
    override val displayName = "扁平"
    override val description = "助手回复全宽无气泡，用户消息浅色右对齐"
    override val chatStyle: ChatStyleConfig = ChatStylePreset.FLAT.config()
}

/** 等宽风格：小圆角 + 右上切角 + 细边框 + 等宽字体，终端质感 */
object MonoChatUiPlugin : ChatUiPlugin {
    override val id = ChatStylePreset.TERMINAL.id
    override val displayName = "等宽"
    override val description = "等宽字体 + 切角细边框气泡，终端质感"
    override val chatStyle: ChatStyleConfig = ChatStylePreset.TERMINAL.config()
}
