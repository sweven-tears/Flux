package cn.luoluna.sweven.ai.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 请求进行中的波浪动画：三条相位错开的正弦波循环推进。
 * 建议 size 为 (48.dp, 16.dp) 左右。
 */
@Composable
fun WaveIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 48.dp,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier = modifier.size(width = width, height = height)) {
        val baseY = size.height / 2f
        val amplitude = size.height * 0.34f
        val step = 2.dp.toPx()
        val alphas = listOf(0.35f, 0.65f, 1f)
        val phaseOffsets = listOf(0f, 0.7f, 1.4f)
        alphas.forEachIndexed { i, alpha ->
            val path = Path()
            var x = 0f
            var first = true
            while (x <= size.width) {
                val y = baseY + amplitude * sin(x / size.width * 2 * PI + phase + phaseOffsets[i]).toFloat()
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += step
            }
            drawPath(
                path,
                color = color.copy(alpha = alpha),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
