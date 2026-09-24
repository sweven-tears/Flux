package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import cn.luoluna.sweven.ai.ui.chat.DataUrlDecodeState
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlDecode

/**
 * 聊天背景容器：把背景画在内容下面（空态与消息列表都包在里面），内容行为不变。
 * THEME = 不画（与改动前完全一致）；COLOR = 主题色角色铺满；IMAGE = 图片铺满 + [ChatBackgroundStyle.dim] 遮罩。
 *
 * 图片解码用 [rememberDataUrlThumb]（降采样到 1280 内），避免大图整张解码吃内存。
 */
@Composable
fun ChatBackground(
    style: ChatBackgroundStyle,
    imageDataUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val decodeState by rememberDataUrlDecode(
        url = imageDataUrl.takeIf { style.kind == ChatBackgroundKind.IMAGE },
        maxDim = 1280,
    )
    val state = decodeState
    Box(modifier) {
        when {
            style.kind == ChatBackgroundKind.COLOR -> {
                Box(Modifier.matchParentSize().background(style.color.resolve(scheme)))
            }

            style.kind == ChatBackgroundKind.IMAGE && state is DataUrlDecodeState.Ready -> {
                val bmp = state.bitmap as? ImageBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 遮罩：dim 越大越接近主题底色，保证气泡文字可读
                    Box(Modifier.matchParentSize().background(scheme.surface.copy(alpha = style.dim)))
                }
            }

            style.kind == ChatBackgroundKind.IMAGE && state is DataUrlDecodeState.Loading -> {
                Box(Modifier.matchParentSize().background(scheme.surfaceVariant.copy(alpha = 0.12f)))
                Text(
                    "背景图加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            style.kind == ChatBackgroundKind.IMAGE && state is DataUrlDecodeState.Failed -> {
                Box(Modifier.matchParentSize().background(scheme.surfaceVariant.copy(alpha = 0.2f)))
                Text(
                    "图片加载失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // THEME / 不选图片类型：不画背景（行为与改动前一致）
            else -> Unit
        }
        content()
    }
}
