package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 聊天背景类型。THEME = 什么都不画（用主题底色）；COLOR = 主题色角色铺满；
 * IMAGE = 图片铺满 + 遮罩（图片本身是 data URL，体积大，单独存 `AppSettings.chatBackgroundImage`）。
 */
enum class ChatBackgroundKind { THEME, COLOR, IMAGE }

/**
 * 聊天背景样式。**故意独立于 [ChatStyleConfig]**：切换预设风格不该把用户精心选的背景一起换掉。
 */
@Immutable
data class ChatBackgroundStyle(
    val kind: ChatBackgroundKind = ChatBackgroundKind.THEME,
    /** 纯色背景用的主题色角色（用角色而非裸色值，暗色模式自动跟随） */
    val color: BubbleFillRole = BubbleFillRole.SURFACE_VARIANT,
    /** 图片背景上的遮罩不透明度 0f..1f：越大图片越淡，保证气泡文字可读 */
    val dim: Float = 0.5f,
)

/**
 * 背景配置 ⇄ JSON。
 * **容错语义与 [ChatStyleCodec] 不同**：任何非法输入都回落 [ChatBackgroundStyle] 默认值，
 * 而不是返回 null —— 背景是可选装饰，坏数据不该让聊天页没有背景以外还报错。
 */
object ChatBackgroundCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(style: ChatBackgroundStyle): String =
        json.encodeToString(ChatBackgroundDto.serializer(), ChatBackgroundDto.fromStyle(style))

    fun decode(raw: String?): ChatBackgroundStyle {
        if (raw.isNullOrBlank()) return ChatBackgroundStyle()
        val dto = runCatching { json.decodeFromString(ChatBackgroundDto.serializer(), raw) }.getOrNull()
            ?: return ChatBackgroundStyle()
        return dto.toStyle()
    }
}

@Serializable
private data class ChatBackgroundDto(
    @SerialName("kind") val kind: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("dim") val dim: Float? = null,
) {
    fun toStyle(): ChatBackgroundStyle {
        val fallback = ChatBackgroundStyle()
        return ChatBackgroundStyle(
            kind = enumOf(kind, fallback.kind),
            color = enumOf(color, fallback.color),
            dim = (dim ?: fallback.dim).coerceIn(0f, 1f),
        )
    }

    companion object {
        fun fromStyle(style: ChatBackgroundStyle): ChatBackgroundDto = ChatBackgroundDto(
            kind = style.kind.name,
            color = style.color.name,
            dim = style.dim,
        )
    }
}
