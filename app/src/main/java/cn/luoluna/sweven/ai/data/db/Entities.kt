package cn.luoluna.sweven.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
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
    val remark: String? = null,
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
