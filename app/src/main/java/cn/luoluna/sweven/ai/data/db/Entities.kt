package cn.luoluna.sweven.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "providers")
@Serializable
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val baseUrl: String,
    val isPreset: Boolean = false,
    val presetId: String? = null,
    val activeKeyId: Long? = null,
    val lastTestOk: Boolean? = null,
    val lastTestLatencyMs: Long? = null,
    val lastTestMessage: String? = null,
    val lastTestAt: Long? = null,
    /** 用户备注（计费方式、注意事项等），仅本地展示 */
    val remark: String? = null,
    /** 手动拖拽排序权重，小者在前；同值按 createdAt */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "api_keys",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("providerId")],
)
@Serializable
data class ApiKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val alias: String,
    val key: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object ModelType {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val EMBEDDING = "embedding"
}

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("providerId")],
)
@Serializable
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    val enabled: Boolean = true,
    /** 显示别名（为空时显示模型 id） */
    val alias: String? = null,
    /** chat / image / embedding */
    val type: String = ModelType.CHAT,
    // 输入/输出类型：文本、图像、视频
    val inputText: Boolean = true,
    val inputImage: Boolean = false,
    val inputVideo: Boolean = false,
    val outputText: Boolean = true,
    val outputImage: Boolean = false,
    val outputVideo: Boolean = false,
    // 能力
    val capabilityTools: Boolean = false,
    val capabilityReasoning: Boolean = false,
    // 内置工具（API 侧提供，当前仅 Gemini 官方 API 生效）
    val builtinGoogleSearch: Boolean = false,
    val builtinUrlContext: Boolean = false,
    val builtinImageGen: Boolean = false,
    // 高级：自定义请求头（每行一个 "Name: Value"）与自定义 body（JSON 对象，键值合入请求体）
    val customHeaders: String? = null,
    val customBody: String? = null,
    // 高级参数，null = 使用 API 默认值
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    /** 上下文窗口（tokens）：聊天页用量显示与超长自动截断的依据；null = 未知（不截断） */
    val contextWindow: Int? = null,
    val remark: String? = null,
    /** 手动拖拽排序权重，小者在前；同值按 name */
    val sortOrder: Int = 0,
)

/**
 * 智能路由（smartAi 聚合模型）的候选模型条目，按 [sortOrder] 升序回退。
 * 以 providerId + modelName 引用模型（与会话一致），重新拉取模型列表不丢配置。
 */
@Entity(
    tableName = "smart_routes",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("providerId")],
)
@Serializable
data class SmartRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelName: String,
    val sortOrder: Int = 0,
    /** false = 从 smartAi 回退链中排除（模型本身不受影响） */
    val enabled: Boolean = true,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    val providerId: Long? = null,
    val modelName: String? = null,
    val folderId: Long? = null,
    val systemPrompt: String? = null,
    /** 思考深度：off / low / medium / high，null = 未设置（不注入思考相关参数） */
    val thinkingDepth: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "folders")
@Serializable
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 文件夹内的预设对话模板：名称 + 系统提示词 + 指定模型 */
@Entity(
    tableName = "presets",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("folderId")],
)
@Serializable
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val name: String,
    val systemPrompt: String? = null,
    val providerId: Long? = null,
    val modelName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    /** JSON 数组字符串，元素为 data URL（图片附件） */
    val imagesJson: String? = null,
    /** 模型思考过程（reasoning_content / <think>），仅助手消息 */
    val reasoning: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val images: List<String>
        get() = imagesJson?.let {
            runCatching { IMAGES_ADAPTER.decode(it) }.getOrNull()
        } ?: emptyList()

    companion object {
        private val IMAGES_ADAPTER = ImagesAdapter
        fun imagesJsonOf(images: List<String>): String? =
            if (images.isEmpty()) null else ImagesAdapter.encode(images)
    }
}

private object ImagesAdapter {
    private const val SEP = "\u0001"
    fun encode(images: List<String>): String = images.joinToString(SEP)
    fun decode(json: String): List<String> = json.split(SEP).filter { it.isNotBlank() }
}

@Entity(tableName = "auth_devices")
@Serializable
data class AuthDeviceEntity(
    @PrimaryKey val deviceId: String,
    val label: String? = null,
    val lastIp: String,
    /** pending / authorized / blocked */
    val status: String,
    val requestCount: Int = 1,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val authorizedAt: Long? = null,
    /** 响应模式（SmartModes）：不同设备对首字延迟的需求不同，默认聊天档 */
    val responseMode: String = "chat",
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_AUTHORIZED = "authorized"
        const val STATUS_BLOCKED = "blocked"
    }
}

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

/** 局域网请求日志：一次 /v1/chat/completions 请求一条（含校验失败），自动修剪保留最近若干条 */
@Entity(
    tableName = "request_logs",
    indices = [Index("at"), Index("deviceId")],
)
data class RequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val deviceId: String,
    val ip: String,
    /** 请求的聚合模型 id（厂商别名/模型名 或 smartAi） */
    val model: String,
    /** 实际命中的上游（直连厂商/模型或 smartAi 胜出候选）；请求在校验阶段失败时为 null */
    val via: String? = null,
    val stream: Boolean = false,
    /** 流式：首字耗时；非流式为 null */
    val ttftMs: Long? = null,
    /** 从收到请求到响应结束的总耗时 */
    val totalMs: Long? = null,
    val ok: Boolean = false,
    /** 失败原因摘要（截断存储），成功时为 null */
    val error: String? = null,
    /** 上游 usage 的 token 计数，上游未返回时为 null */
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
)
