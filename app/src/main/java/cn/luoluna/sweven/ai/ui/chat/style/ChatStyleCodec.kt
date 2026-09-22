package cn.luoluna.sweven.ai.ui.chat.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 样式表 ⇄ JSON。
 *
 * **部分覆盖语义**（用户手写 JSON，只写想改的字段即可）：
 * - 某角色没写 → 用默认预设（扁平）里该角色的样式；
 * - 角色写了但缺字段 → 缺的字段继承「默认预设里该角色 + base 的改动」；
 * - `base` 的改动会传导到所有未单独写样式的角色（但不会抹掉角色自身特征，如用户主色）；
 * - 未知枚举名 → 回落该字段的继承值；整体不是合法 JSON 对象 → 返回 null，由调用方回落内置预设。
 *
 * 长度单位一律用 dp 的数值（Float）。**新增 BubbleStyle 字段时三处都要改**：
 * BubbleStyle、BubbleStyleDto.applyTo()、BubbleStyleDto.fromStyle()。
 */
object ChatStyleCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 编码（编辑对话框里给用户的默认模板 = 气泡预设的完整 JSON） */
    fun encode(config: ChatStyleConfig): String = json.encodeToString(
        ChatStyleDto.serializer(),
        ChatStyleDto.fromStyle(config),
    )

    /** 解码；任何非法输入返回 null */
    fun decode(raw: String?): ChatStyleConfig? {
        if (raw.isNullOrBlank()) return null
        val dto = runCatching {
            json.decodeFromString(ChatStyleDto.serializer(), raw)
        }.getOrNull() ?: return null
        return dto.toStyle()
    }
}

@Serializable
data class BubbleStyleDto(
    @SerialName("cornerRadius") val cornerRadius: Float? = null,
    @SerialName("cutCornerSize") val cutCornerSize: Float? = null,
    @SerialName("cutCorner") val cutCorner: String? = null,
    @SerialName("fill") val fill: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("stroke") val stroke: String? = null,
    @SerialName("strokeWidth") val strokeWidth: Float? = null,
    @SerialName("shadowElevation") val shadowElevation: Float? = null,
    @SerialName("dividerBottom") val dividerBottom: Boolean? = null,
    @SerialName("paddingH") val paddingH: Float? = null,
    @SerialName("paddingV") val paddingV: Float? = null,
    @SerialName("monospace") val monospace: Boolean? = null,
) {
    /** 把本 DTO 里**写过**的字段盖到 [fallback] 上；没写的保持 [fallback] */
    fun applyTo(fallback: BubbleStyle): BubbleStyle = fallback.copy(
        cornerRadius = cornerRadius?.dp() ?: fallback.cornerRadius,
        cutCornerSize = cutCornerSize?.dp() ?: fallback.cutCornerSize,
        cutCorner = enumOf(cutCorner, fallback.cutCorner),
        fill = enumOf(fill, fallback.fill),
        text = enumOf(text, fallback.text),
        stroke = enumOf(stroke, fallback.stroke),
        strokeWidth = strokeWidth?.dp() ?: fallback.strokeWidth,
        shadowElevation = shadowElevation?.dp() ?: fallback.shadowElevation,
        dividerBottom = dividerBottom ?: fallback.dividerBottom,
        paddingH = paddingH?.dp() ?: fallback.paddingH,
        paddingV = paddingV?.dp() ?: fallback.paddingV,
        monospace = monospace ?: fallback.monospace,
    )

    companion object {
        fun fromStyle(style: BubbleStyle): BubbleStyleDto = BubbleStyleDto(
            cornerRadius = style.cornerRadius.value,
            cutCornerSize = style.cutCornerSize.value,
            cutCorner = style.cutCorner.name,
            fill = style.fill.name,
            text = style.text.name,
            stroke = style.stroke.name,
            strokeWidth = style.strokeWidth.value,
            shadowElevation = style.shadowElevation.value,
            dividerBottom = style.dividerBottom,
            paddingH = style.paddingH.value,
            paddingV = style.paddingV.value,
            monospace = style.monospace,
        )
    }
}

@Serializable
data class RoleProfileDto(
    @SerialName("align") val align: String? = null,
    @SerialName("maxWidthFraction") val maxWidthFraction: Float? = null,
    @SerialName("marginH") val marginH: Float? = null,
    @SerialName("marginV") val marginV: Float? = null,
    @SerialName("showHeader") val showHeader: Boolean? = null,
    @SerialName("headerAvatarSize") val headerAvatarSize: Float? = null,
) {
    /** 同 [BubbleStyleDto.applyTo]：写过的字段盖到 [fallback] 上 */
    fun applyTo(fallback: RoleProfile): RoleProfile = fallback.copy(
        align = enumOf(align, fallback.align),
        maxWidthFraction = maxWidthFraction ?: fallback.maxWidthFraction,
        marginH = marginH?.dp() ?: fallback.marginH,
        marginV = marginV?.dp() ?: fallback.marginV,
        showHeader = showHeader ?: fallback.showHeader,
        headerAvatarSize = headerAvatarSize?.dp() ?: fallback.headerAvatarSize,
    )

    companion object {
        fun fromProfile(profile: RoleProfile): RoleProfileDto = RoleProfileDto(
            align = profile.align.name,
            maxWidthFraction = profile.maxWidthFraction,
            marginH = profile.marginH.value,
            marginV = profile.marginV.value,
            showHeader = profile.showHeader,
            headerAvatarSize = profile.headerAvatarSize.value,
        )
    }
}

@Serializable
data class ChatStyleDto(
    @SerialName("base") val base: BubbleStyleDto? = null,
    @SerialName("user") val user: BubbleStyleDto? = null,
    @SerialName("ai") val ai: BubbleStyleDto? = null,
    @SerialName("tool") val tool: BubbleStyleDto? = null,
    @SerialName("system") val system: BubbleStyleDto? = null,
    @SerialName("userProfile") val userProfile: RoleProfileDto? = null,
    @SerialName("aiProfile") val aiProfile: RoleProfileDto? = null,
    @SerialName("systemProfile") val systemProfile: RoleProfileDto? = null,
) {
    /**
     * DTO → 样式表（部分覆盖）。
     *
     * 顺序很重要：**先叠 base（只叠 base 里写过的字段），再叠该角色自己写过的字段**。
     * 反过来的话，编码全字段的 JSON 会把角色样式压成 base；而不区分"写过/没写"的话，
     * 没写 base 时也会把 base 的字段盖到 tool 这类有自己特征的样式上（丢掉等宽）。
     */
    fun toStyle(): ChatStyleConfig {
        val preset = ChatStylePreset.FLAT.config()
        // 没写 base 时是恒等变换；写了则只覆盖 base 里出现的字段
        val withBase: (BubbleStyle) -> BubbleStyle = { style -> base?.applyTo(style) ?: style }
        return ChatStyleConfig(
            base = withBase(preset.base),
            user = user?.applyTo(withBase(preset.user)) ?: withBase(preset.user),
            ai = ai?.applyTo(withBase(preset.ai)) ?: withBase(preset.ai),
            tool = tool?.applyTo(withBase(preset.tool)) ?: withBase(preset.tool),
            system = system?.applyTo(withBase(preset.system)) ?: withBase(preset.system),
            userProfile = userProfile?.applyTo(preset.userProfile) ?: preset.userProfile,
            aiProfile = aiProfile?.applyTo(preset.aiProfile) ?: preset.aiProfile,
            systemProfile = systemProfile?.applyTo(preset.systemProfile) ?: preset.systemProfile,
        )
    }

    companion object {
        fun fromStyle(config: ChatStyleConfig): ChatStyleDto = ChatStyleDto(
            base = BubbleStyleDto.fromStyle(config.base),
            user = BubbleStyleDto.fromStyle(config.user),
            ai = BubbleStyleDto.fromStyle(config.ai),
            tool = BubbleStyleDto.fromStyle(config.tool),
            system = BubbleStyleDto.fromStyle(config.system),
            userProfile = RoleProfileDto.fromProfile(config.userProfile),
            aiProfile = RoleProfileDto.fromProfile(config.aiProfile),
            systemProfile = RoleProfileDto.fromProfile(config.systemProfile),
        )
    }
}

/** Float（dp 值）→ Dp */
private fun Float.dp(): Dp = this.dp

/** 枚举名 → 枚举值；未知/大小写不符 → 继承值（不抛异常）。跨文件复用（ChatBackgroundCodec 也用） */
internal inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
