package cn.luoluna.sweven.ai.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 侧滑露出的一个动作按钮（圆角色块：图标 + 文字，如 删除/禁用） */
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    val onClick: () -> Unit,
)

/**
 * 左滑露出操作按钮的容器（各列表页统一实现，以厂商详情页模型行为基准）：
 * - 动作按钮为圆角色块（图标 + 文字），宽度 [actionWidth]，多块之间留缝；
 * - 单个 Animatable 跟手横移，松手按过半阈值决定开/合；
 * - [isOpen]/[onOpenChange] 由外部提升状态，保证同一时间至多一行滑开；
 *   调用方另负责滚动列表时把 openId 置空（自动收回）；
 * - 滑开状态下点前景空白处收回；点动作按钮先收回再执行动作。
 */
@Composable
fun SwipeRevealActions(
    actions: List<SwipeAction>,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 84.dp,
    content: @Composable () -> Unit,
) {
    val gap = 6.dp
    val revealWidth = actionWidth * actions.size + gap * (actions.size - 1).coerceAtLeast(0)
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(isOpen) {
        if (!dragging) offsetX.animateTo(if (isOpen) -revealPx else 0f, tween(200))
    }

    Box(modifier) {
        // 背景：滑开后露出的动作按钮（右对齐，各自圆角色块）
        Row(
            Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) Spacer(Modifier.width(gap))
                Box(
                    Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.medium)
                        .background(action.container)
                        .clickable {
                            onOpenChange(false)
                            action.onClick()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(action.icon, contentDescription = action.label, tint = action.content)
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = action.content,
                        )
                    }
                }
            }
        }
        // 前景内容：跟手横移；滑开时点空白处收回
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = isOpen,
                ) { onOpenChange(false) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + amount).coerceIn(-revealPx, 0f))
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            val open = offsetX.value <= -revealPx / 2f
                            scope.launch {
                                offsetX.animateTo(if (open) -revealPx else 0f, tween(200))
                            }
                            onOpenChange(open)
                        },
                        onDragCancel = {
                            dragging = false
                            val open = offsetX.value <= -revealPx / 2f
                            scope.launch {
                                offsetX.animateTo(if (open) -revealPx else 0f, tween(200))
                            }
                            onOpenChange(open)
                        },
                    )
                },
        ) {
            content()
        }
    }
}
