package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** 填充色角色 → 主题色 */
fun BubbleFillRole.resolve(scheme: ColorScheme): Color = when (this) {
    BubbleFillRole.PRIMARY -> scheme.primary
    BubbleFillRole.SECONDARY_CONTAINER -> scheme.secondaryContainer
    BubbleFillRole.SURFACE_VARIANT -> scheme.surfaceVariant
    BubbleFillRole.SURFACE_CONTAINER_HIGH -> scheme.surfaceContainerHigh
    BubbleFillRole.SURFACE -> scheme.surface
    BubbleFillRole.TRANSPARENT -> Color.Transparent
}

/** 文字色角色 → 主题色 */
fun BubbleTextRole.resolve(scheme: ColorScheme): Color = when (this) {
    BubbleTextRole.ON_PRIMARY -> scheme.onPrimary
    BubbleTextRole.ON_SECONDARY_CONTAINER -> scheme.onSecondaryContainer
    BubbleTextRole.ON_SURFACE -> scheme.onSurface
    BubbleTextRole.ON_SURFACE_VARIANT -> scheme.onSurfaceVariant
}

/** 描边色角色 → 主题色 */
fun BubbleStrokeRole.resolve(scheme: ColorScheme): Color = when (this) {
    BubbleStrokeRole.NONE -> Color.Transparent
    BubbleStrokeRole.OUTLINE -> scheme.outline
    BubbleStrokeRole.OUTLINE_VARIANT -> scheme.outlineVariant
    BubbleStrokeRole.PRIMARY -> scheme.primary
}

/**
 * 带真切角（右上角一条斜边）的气泡形状：三圆弧 + 一条斜边，顺时针闭合。
 *
 * 角度约定（Compose 的 Path.arcTo 以 3 点方向为 0°、y 向下为正，因此顺时针走是 +90°）：
 *   右下弧 中心(w-r, h-r)：入口 (w, h-r)=0° → 出口 (w-r, h)=90°
 *   左下弧 中心(r, h-r)：入口 (r, h)=90° → 出口 (0, h-r)=180°
 *   左上弧 中心(r, r)：入口 (0, r)=180° → 出口 (r, 0)=270°
 *
 * 想支持别的角（左下/右下/左上）：加一个 [BubbleCorner] 枚举值，把对应角的弧换成
 * lineTo 斜边即可（其余不变）。
 */
private class ChamferedShape(
    private val radiusPx: Float,
    private val cutPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val geometry = bubbleOutline(size.width, size.height, radiusPx, cutPx)
        val r = geometry.radiusPx
        val c = geometry.cutPx
        if (c <= 0f) {
            return Outline.Rounded(
                RoundRect(Rect(Offset.Zero, size), CornerRadius(r, r)),
            )
        }
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (r <= 0f) {
                // 直角兜底：不产生零尺寸圆弧
                moveTo(0f, 0f)
                lineTo(w - c, 0f)
                lineTo(w, c)
                lineTo(w, h)
                lineTo(0f, h)
                close()
                return@apply
            }
            moveTo(r, 0f)
            lineTo(w - c, 0f)
            lineTo(w, c)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 由 [BubbleStyle] 生成气泡形状。需要 [Density]（Shape 的 createOutline 才有 density），
 * 组合里请用 [rememberBubbleShape]。
 */
fun bubbleShape(style: BubbleStyle, density: Density): Shape {
    if (style.cutCornerSize.value <= 0f) {
        return RoundedCornerShape(style.cornerRadius)
    }
    return ChamferedShape(
        radiusPx = with(density) { style.cornerRadius.toPx() },
        cutPx = with(density) { style.cutCornerSize.toPx() },
    )
}

/** 组合里的形状入口：样式/密度变化才重建 */
@Composable
fun rememberBubbleShape(style: BubbleStyle): Shape {
    val density = LocalDensity.current
    return remember(style, density) { bubbleShape(style, density) }
}

/**
 * 唯一的消息气泡绘制核心：**不认识角色**，只按传入的 [BubbleStyle] 绘制。
 * 用户 → `config.user`；助手 → `config.ai`；工具结果 → `config.tool`。同一份代码。
 *
 * 绘制顺序：背景（Surface color）→ 形状（含切角）→ 描边 → 阴影 → 内容。
 * 状态/时间/操作按钮**不在这里**，见 [ChatMessageRow] 的 footer。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    style: BubbleStyle,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = rememberBubbleShape(style)
    val border = if (style.stroke != BubbleStrokeRole.NONE) {
        BorderStroke(style.strokeWidth, style.stroke.resolve(scheme))
    } else {
        null
    }
    var bubbleModifier = modifier
    if (onClick != null || onLongClick != null) {
        bubbleModifier = bubbleModifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    }
    Surface(
        color = style.fill.resolve(scheme),
        contentColor = style.text.resolve(scheme),
        shape = shape,
        border = border,
        shadowElevation = style.shadowElevation,
        modifier = bubbleModifier,
    ) {
        Column(
            Modifier.padding(
                horizontal = style.paddingH,
                vertical = style.paddingV,
            ),
            content = content,
        )
        if (style.dividerBottom) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 角色级布局：对齐 / 最大宽度（[RoleProfile.maxWidthFraction]）/ 外边距 / 头像行 / footer。
 * header 与 footer 由调用方给（头像文案、操作按钮、token 统计），本组件不关心内容。
 */
@Composable
fun ChatMessageRow(
    profile: RoleProfile,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    bubble: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = profile.marginH, vertical = profile.marginV),
        horizontalAlignment = when (profile.align) {
            RoleAlign.START -> Alignment.Start
            RoleAlign.END -> Alignment.End
            RoleAlign.CENTER -> Alignment.CenterHorizontally
        },
    ) {
        if (profile.showHeader) header?.invoke()
        BoxWithConstraints {
            val maxW = maxWidth * profile.maxWidthFraction.coerceIn(0.2f, 1f)
            Box(Modifier.widthIn(max = maxW)) { bubble() }
        }
        footer?.invoke()
    }
}
