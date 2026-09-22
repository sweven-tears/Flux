package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlThumb

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
    val bitmap by rememberDataUrlThumb(
        url = imageDataUrl.takeIf { style.kind == ChatBackgroundKind.IMAGE },
        maxDim = 1280,
    )
    val bmp = bitmap
    Box(modifier) {
        when {
            style.kind == ChatBackgroundKind.COLOR -> {
                Box(Modifier.matchParentSize().background(style.color.resolve(scheme)))
            }

            style.kind == ChatBackgroundKind.IMAGE && bmp != null -> {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                // 遮罩：dim 越大越接近主题底色，保证气泡文字可读
                Box(Modifier.matchParentSize().background(scheme.surface.copy(alpha = style.dim)))
            }

            // THEME / 图片缺失：不画背景（行为与改动前一致）
            else -> Unit
        }
        content()
    }
}
