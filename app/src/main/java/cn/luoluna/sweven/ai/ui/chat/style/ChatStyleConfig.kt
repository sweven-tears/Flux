package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.Role

/** 消息在屏幕上的对齐方式 */
enum class RoleAlign { START, END, CENTER }

/**
 * 角色布局档案：只管"这个角色怎么摆"，不含任何颜色 / 圆角 / 切角。
 * 分工：BubbleStyle 管气泡长什么样，RoleProfile 管气泡放哪儿。
 */
@Immutable
data class RoleProfile(
    val align: RoleAlign = RoleAlign.START,
    /** 气泡最大宽度占可用宽度的比例（会被收敛到 0.2 ~ 1.0） */
    val maxWidthFraction: Float = 0.8f,
    val marginH: Dp = 0.dp,
    val marginV: Dp = 0.dp,
    /** 是否显示头像+名字行 */
    val showHeader: Boolean = true,
    val headerAvatarSize: Dp = 24.dp,
)

/**
 * 全局样式表：换肤只替换本对象，不改任何绘制代码。
 * 优先级：**角色样式（本对象的 user/ai/...）> 预设（[ChatStylePreset]）> [BubbleStyle] 默认值**。
 */
@Immutable
data class ChatStyleConfig(
    val base: BubbleStyle,
    val user: BubbleStyle,
    val ai: BubbleStyle,
    val tool: BubbleStyle,
    /** 占位：库里不产生 system 气泡（见计划 D6）；保留是为了将来渲染时零改动可用 */
    val system: BubbleStyle,
    val userProfile: RoleProfile,
    val aiProfile: RoleProfile,
    val systemProfile: RoleProfile,
) {
    /** 按消息角色取气泡样式；未知角色（thinking 等）回落 AI 样式 */
    fun styleFor(role: String?): BubbleStyle = when (role) {
        Role.USER -> user
        Role.TOOL -> tool
        Role.SYSTEM -> system
        else -> ai
    }

    /** 按消息角色取布局档案；未知角色回落 AI 档案 */
    fun profileFor(role: String?): RoleProfile = when (role) {
        Role.USER -> userProfile
        Role.SYSTEM -> systemProfile
        else -> aiProfile
    }

    companion object {
        /** 气泡式（默认）：圆角 + 无尾巴，用户主色右对齐、助手浅灰左对齐 */
        fun bubble(): ChatStyleConfig {
            val base = BubbleStyle()
            return ChatStyleConfig(
                base = base,
                user = base.copy(
                    fill = BubbleFillRole.PRIMARY,
                    text = BubbleTextRole.ON_PRIMARY,
                    paddingH = 12.dp,
                    paddingV = 9.dp,
                ),
                ai = base.copy(
                    fill = BubbleFillRole.SURFACE_VARIANT,
                    text = BubbleTextRole.ON_SURFACE,
                    paddingH = 12.dp,
                    paddingV = 10.dp,
                ),
                tool = base.copy(
                    fill = BubbleFillRole.SURFACE_CONTAINER_HIGH,
                    text = BubbleTextRole.ON_SURFACE,
                    cornerRadius = 12.dp,
                    paddingH = 10.dp,
                    paddingV = 8.dp,
                    monospace = true,
                ),
                system = base.copy(
                    fill = BubbleFillRole.SURFACE_VARIANT,
                    text = BubbleTextRole.ON_SURFACE_VARIANT,
                    paddingH = 10.dp,
                    paddingV = 6.dp,
                ),
                userProfile = RoleProfile(align = RoleAlign.END, maxWidthFraction = 0.75f),
                aiProfile = RoleProfile(align = RoleAlign.START, maxWidthFraction = 0.8f),
                systemProfile = RoleProfile(
                    align = RoleAlign.CENTER,
                    maxWidthFraction = 1f,
                    showHeader = false,
                ),
            )
        }

        /** 终端式：小圆角 + 右上切角 + 细描边 + 等宽字体 */
        fun terminal(): ChatStyleConfig {
            val base = BubbleStyle(
                cornerRadius = 4.dp,
                cutCornerSize = 12.dp,
                fill = BubbleFillRole.SURFACE,
                text = BubbleTextRole.ON_SURFACE,
                stroke = BubbleStrokeRole.OUTLINE_VARIANT,
                strokeWidth = 1.dp,
                paddingH = 10.dp,
                paddingV = 6.dp,
                monospace = true,
            )
            return ChatStyleConfig(
                base = base,
                user = base.copy(stroke = BubbleStrokeRole.OUTLINE),
                ai = base.copy(),
                tool = base.copy(cornerRadius = 2.dp, cutCornerSize = 8.dp),
                system = base.copy(),
                userProfile = RoleProfile(align = RoleAlign.END, maxWidthFraction = 0.75f),
                aiProfile = RoleProfile(align = RoleAlign.START, maxWidthFraction = 0.9f),
                systemProfile = RoleProfile(align = RoleAlign.CENTER, maxWidthFraction = 1f, showHeader = false),
            )
        }

        /** 平铺式：无气泡底色 + 底部一条分隔线 */
        fun flat(): ChatStyleConfig {
            val base = BubbleStyle(
                cornerRadius = 2.dp,
                fill = BubbleFillRole.TRANSPARENT,
                text = BubbleTextRole.ON_SURFACE,
                dividerBottom = true,
                paddingH = 4.dp,
                paddingV = 6.dp,
            )
            return ChatStyleConfig(
                base = base,
                user = base.copy(
                    fill = BubbleFillRole.SECONDARY_CONTAINER,
                    text = BubbleTextRole.ON_SECONDARY_CONTAINER,
                    dividerBottom = false,
                    cornerRadius = 12.dp,
                    paddingH = 12.dp,
                    paddingV = 8.dp,
                ),
                ai = base.copy(),
                tool = base.copy(monospace = true),
                system = base.copy(),
                userProfile = RoleProfile(align = RoleAlign.END, maxWidthFraction = 0.75f),
                aiProfile = RoleProfile(align = RoleAlign.START, maxWidthFraction = 1f),
                systemProfile = RoleProfile(align = RoleAlign.CENTER, maxWidthFraction = 1f, showHeader = false),
            )
        }
    }
}

/**
 * 内置风格预设（插件 id ↔ 样式表）；`custom`（JSON）皮肤不走这里，见 ChatStyleCodec。
 * **默认风格 = 扁平（FLAT）**：其 id 即 `ChatUiRegistry.DEFAULT_ID`。
 */
enum class ChatStylePreset(val id: String, val displayName: String) {
    /** 默认：助手无气泡全宽 + 底部分隔线，用户浅色气泡右对齐 */
    FLAT("flat", "扁平"),
    /** 小圆角 + 右上切角 + 细描边 + 等宽字体 */
    TERMINAL("mono", "等宽"),
    /** 圆角 16dp + 无尾巴，用户主色、助手浅灰 */
    BUBBLE("bubble", "气泡"),
    ;

    fun config(): ChatStyleConfig = when (this) {
        FLAT -> ChatStyleConfig.flat()
        TERMINAL -> ChatStyleConfig.terminal()
        BUBBLE -> ChatStyleConfig.bubble()
    }

    companion object {
        /** 历史 id：旧版本的「默认」就是气泡式，改默认后仍要解析回来（老用户已保存的选择不丢） */
        private val LEGACY_IDS = mapOf("default" to BUBBLE)

        /** 未设置 / 未知 id 一律回落默认风格（扁平） */
        fun of(pluginId: String?): ChatStylePreset =
            entries.firstOrNull { it.id == pluginId }
                ?: pluginId?.let { LEGACY_IDS[it] }
                ?: FLAT
    }
}
