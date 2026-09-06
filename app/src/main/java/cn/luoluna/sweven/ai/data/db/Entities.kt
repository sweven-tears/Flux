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
    // 输入/输出类型：文本、图像、视频
    val inputText: Boolean = true,
    val inputImage: Boolean = false,
    val inputVideo: Boolean = false,
    val outputText: Boolean = true,
    val outputImage: Boolean = false,
    val outputVideo: Boolean = false,
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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
