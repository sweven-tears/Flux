package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 通用「问号 + 即时说明弹层」组件：
 * 点问号立即弹出说明卡片（PopupWindow），点弹层外任意位置 / 返回键即关闭。
 *
 * 用法（标题行右侧、表单字段旁均可）：
 * ```
 * SectionCard(title = "聚合路由服务", actions = { InfoIconWithPopup(text = "...") })
 * ```
 */
@Composable
fun InfoIconWithPopup(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = Icons.Outlined.HelpOutline,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                icon,
                contentDescription = "查看说明",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            // focusable=true：捕获返回键与外部点击；onDismissRequest 在任一情况下关闭
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 0),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 300.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        title?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                        )
                    }
                }
            }
        }
    }
}
