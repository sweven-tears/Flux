package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 填充色角色（主题引用，暗色模式自动跟随；**不要在样式里存死颜色**） */
enum class BubbleFillRole {
    PRIMARY, SECONDARY_CONTAINER, SURFACE_VARIANT, SURFACE_CONTAINER_HIGH, SURFACE, TRANSPARENT,
}

/** 文字色角色 */
enum class BubbleTextRole { ON_PRIMARY, ON_SECONDARY_CONTAINER, ON_SURFACE, ON_SURFACE_VARIANT }

/** 描边色角色 */
enum class BubbleStrokeRole { NONE, OUTLINE, OUTLINE_VARIANT, PRIMARY }

/** 切角位置（本期只实现右上角；要加别的角见 ChamferedShape 的注释） */
enum class BubbleCorner { TOP_END }

/**
 * 气泡视觉样式：**唯一**的视觉参数来源。
 *
 * 三种预设（气泡 / 终端 / 平铺）与 JSON 自定义风格都只是本类的实例；角色差异
 * （user/ai/tool/system）由 [ChatStyleConfig] 用 `base.copy(...)` 覆盖。
 * 任何绘制代码都不许再硬编码圆角/颜色/内边距：新增视觉参数一律加到这里，再加进预设。
 *
 * 本样式**不含尾巴**（按 2026-09-22 决策）：用户/助手的区分靠 fill+text 与 [RoleProfile.align]。
 */
@Immutable
data class BubbleStyle(
    val cornerRadius: Dp = 16.dp,
    /** > 0 时启用真切角（一条斜边），见 [bubbleOutline] 与 ChamferedShape */
    val cutCornerSize: Dp = 0.dp,
    val cutCorner: BubbleCorner = BubbleCorner.TOP_END,
    val fill: BubbleFillRole = BubbleFillRole.SURFACE_VARIANT,
    val text: BubbleTextRole = BubbleTextRole.ON_SURFACE,
    val stroke: BubbleStrokeRole = BubbleStrokeRole.NONE,
    val strokeWidth: Dp = 1.dp,
    val shadowElevation: Dp = 0.dp,
    /** 平铺风格用：气泡底部一条分隔线代替气泡边界 */
    val dividerBottom: Boolean = false,
    val paddingH: Dp = 12.dp,
    val paddingV: Dp = 8.dp,
    /** 内容用等宽字体（终端风格） */
    val monospace: Boolean = false,
)

/** 收敛后的切角几何（像素） */
data class BubbleOutline(val radiusPx: Float, val cutPx: Float)

/**
 * 切角几何的纯计算：把半径与切角尺寸收敛到实际尺寸允许的范围，避免出现自交路径
 * （半径不超过短边一半；切角不超过切角所在两条可用边的较小者）。
 */
fun bubbleOutline(widthPx: Float, heightPx: Float, radiusPx: Float, cutPx: Float): BubbleOutline {
    if (widthPx <= 0f || heightPx <= 0f) return BubbleOutline(0f, 0f)
    val radius = radiusPx.coerceIn(0f, minOf(widthPx, heightPx) / 2f)
    val edge = minOf((widthPx - radius), (heightPx - radius)).coerceAtLeast(0f)
    val cut = cutPx.coerceIn(0f, edge)
    return BubbleOutline(radius, cut)
}
