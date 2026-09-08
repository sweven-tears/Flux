package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 头像组件。
 * - 用户头像：有 data URL 显示图片，否则按名字首字合成（配色按名字 hash 稳定）。
 * - 助手头像：占位符（机器人图标）；后续按模型 id 匹配，匹配不到回落厂商首字合成。
 */

/** 首字合成头像的稳定配色板（Material 调性，按名字 hash 取色） */
private val AVATAR_COLORS = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFFAB47BC),
    Color(0xFF42A5F5), Color(0xFFFF7043), Color(0xFF9CCC65), Color(0xFF8D6E63),
    Color(0xFF78909C), Color(0xFFD4E157), Color(0xFF7E57C2), Color(0xFF26C6DA),
)

private fun avatarColorFor(key: String): Color =
    AVATAR_COLORS[abs(key.hashCode()) % AVATAR_COLORS.size]

/** 取名字首字（中英文通用：取第一个非空白字符，大写） */
private fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

/** 首字合成圆形头像 */
@Composable
fun InitialAvatar(
    name: String,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = avatarColorFor(name.ifBlank { "?" }),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initialOf(name),
                style = if (size >= 32.dp) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                color = Color.White,
            )
        }
    }
}

/**
 * 用户头像：有 data URL 显示图片，否则按名字首字合成。
 */
@Composable
fun UserAvatar(
    name: String?,
    avatarDataUrl: String?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberDataUrlBitmap(avatarDataUrl).value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "用户头像",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        InitialAvatar(name = name?.ifBlank { null } ?: "我", size = size, modifier = modifier)
    }
}

/**
 * 助手头像：占位符（机器人图标）。
 * 后续按模型 id 匹配专属头像；匹配不到时回落厂商首字合成（[InitialAvatar]）。
 */
@Composable
fun AssistantAvatar(
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = "助手头像",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}
