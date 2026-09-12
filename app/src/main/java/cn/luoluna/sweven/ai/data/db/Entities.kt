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
    /** 厂商启用状态：停用后模型不进聊天选择器，智能路由与局域网网关均跳过 */
    val enabled: Boolean = true,
    /** 接口协议：openai（OpenAI 兼容）/ gemini（Gemini 原生）/ anthropic（Claude 原生），见 net.ProviderProtocol */
    val protocol: String = "openai",
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
    /**
     * 视频接口形态（VideoAdapters 注册表里的 shapeId：sora/agnes/kling/minimax/runway/veo）。
     * 首次视频提交探测成功后写回，此后该模型免探测；null = 尚未探测。
     */
    val videoApiShape: String? = null,
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

/**
 * 智能路由分组：同族模型（按模型 id 归组）聚合为一个对外模型 flux-<groupKey>，
 * 聊天选择器与局域网 /v1/models 同形暴露；组内跨厂商自动切换，成员见 [SmartRouteMemberEntity]。
 */
@Entity(tableName = "smart_route_groups")
@Serializable
data class SmartRouteGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归组键：模型 id 规范化后的基名（小写、去厂商命名空间、去尾部版本/日期后缀），如 deepseek-v4-flash */
    val groupKey: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 对外模型名（局域网请求 model 字段与聊天选择器展示名） */
    val routeName: String get() = "flux-$groupKey"
}

/**
 * 路由分组成员：与 smart_routes 同样按 providerId + modelName 引用模型（重拉模型列表不丢配置）。
 * [sortOrder] 决定组内切换优先级；enabled=false 仅从本组剔除，模型本身不受影响。
 */
@Entity(
    tableName = "smart_route_members",
    foreignKeys = [
        ForeignKey(
            entity = SmartRouteGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("groupId")],
)
@Serializable
data class SmartRouteMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val providerId: Long,
    val modelName: String,
    val sortOrder: Int = 0,
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
    /** 生成此助手消息的模型：厂商 id + 模型名；用户消息与旧数据为 null（显示回落"助手"） */
    val providerId: Long? = null,
    val modelName: String? = null,
    /** 上游 usage 统计：输入/输出 token；无 usage 帧（旧数据、生图、厂商不回传）为 null */
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    /** prompt 中命中缓存的 token 数 */
    val cachedTokens: Long? = null,
    /** 首字延迟毫秒（首个内容/思考增量到达）；未出任何内容即结束为 null */
    val ttftMs: Long? = null,
    /** 本次生成总耗时毫秒（发起请求到收尾落库） */
    val totalMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val images: List<String>
        get() = imagesJson?.let {
            runCatching { IMAGES_ADAPTER.decode(it) }.getOrNull()
        } ?: emptyList()

    companion object {
        /**
         * imagesJson 中「已被清理」的图片占位标记：从「我的创作」删除后原位替换 data URL，
         * 气泡原位渲染占位块；非 data: 前缀，天然不会被回传上游/保存相册/再添加为附件。
         */
        const val DELETED_IMAGE = "flux-deleted"

        fun isDeletedImage(url: String): Boolean = url == DELETED_IMAGE

        /** 视频消息条目前缀：`flux-video:<App 私有目录视频绝对路径>`（视频生成结果） */
        const val VIDEO_PREFIX = "flux-video:"

        fun isVideo(url: String): Boolean = url.startsWith(VIDEO_PREFIX)

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

/**
 * 视频生成任务：提交→轮询→下载的全生命周期落库（进程被杀后服务重启可恢复轮询）。
 * 视频文件存 App 私有目录（filesDir/videos），消息与创作通过 imagesJson 的 `flux-video:<路径>` 前缀引用。
 */
@Entity(tableName = "video_jobs")
data class VideoJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val providerId: Long,
    val modelName: String,
    val prompt: String,
    /** 上游任务 id（提交成功后回填；提交即失败为 null） */
    val upstreamJobId: String? = null,
    /** pending / running / done / failed / cancelled */
    val status: String,
    /** 完成后生成的视频文件绝对路径 */
    val videoPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
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
