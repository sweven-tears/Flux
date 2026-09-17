package cn.luoluna.sweven.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun all(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observeById(id: Long): Flow<ProviderEntity?>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun byId(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE alias = :alias COLLATE NOCASE LIMIT 1")
    suspend fun byAlias(alias: String): ProviderEntity?

    @Query("SELECT COUNT(*) FROM providers WHERE alias = :alias COLLATE NOCASE AND id != :excludeId")
    suspend fun countAliasExcluding(alias: String, excludeId: Long): Int

    @Insert
    suspend fun insert(p: ProviderEntity): Long

    @Insert
    suspend fun insertAll(list: List<ProviderEntity>)

    @Query("DELETE FROM providers")
    suspend fun clearAll()

    @Update
    suspend fun update(p: ProviderEntity)

    @Update
    suspend fun updateAll(list: List<ProviderEntity>)

    @Delete
    suspend fun delete(p: ProviderEntity)

    @Query("UPDATE providers SET activeKeyId = :keyId WHERE id = :providerId")
    suspend fun setActiveKey(providerId: Long, keyId: Long?)

    @Query("UPDATE providers SET lastTestOk = :ok, lastTestLatencyMs = :latencyMs, lastTestMessage = :message, lastTestAt = :at WHERE id = :providerId")
    suspend fun updateTestResult(providerId: Long, ok: Boolean, latencyMs: Long, message: String, at: Long)
}

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY createdAt ASC")
    fun observeForProvider(providerId: Long): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY createdAt ASC")
    suspend fun forProvider(providerId: Long): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys")
    suspend fun all(): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun byId(id: Long): ApiKeyEntity?

    @Insert
    suspend fun insert(k: ApiKeyEntity): Long

    @Insert
    suspend fun insertAll(list: List<ApiKeyEntity>)

    @Query("DELETE FROM api_keys")
    suspend fun clearAll()

    @Update
    suspend fun update(k: ApiKeyEntity)

    @Delete
    suspend fun delete(k: ApiKeyEntity)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models")
    suspend fun all(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeForProvider(providerId: Long): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE enabled = 1 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun forProvider(providerId: Long): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId AND name = :name LIMIT 1")
    suspend fun find(providerId: Long, name: String): ModelEntity?

    @Query("DELETE FROM models")
    suspend fun clearAll()

    @Insert
    suspend fun insert(m: ModelEntity): Long

    @Insert
    suspend fun insertAll(list: List<ModelEntity>)

    @Update
    suspend fun update(m: ModelEntity)

    @Update
    suspend fun updateAll(list: List<ModelEntity>)

    @Delete
    suspend fun delete(m: ModelEntity)
}

@Dao
interface SmartRouteDao {
    @Query("SELECT * FROM smart_routes ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SmartRouteEntity>>

    @Query("SELECT * FROM smart_routes ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<SmartRouteEntity>

    @Query("SELECT * FROM smart_routes WHERE providerId = :providerId AND modelName = :modelName LIMIT 1")
    suspend fun find(providerId: Long, modelName: String): SmartRouteEntity?

    @Query("SELECT MAX(sortOrder) FROM smart_routes")
    suspend fun maxSortOrder(): Int?

    @Query("DELETE FROM smart_routes")
    suspend fun clearAll()

    @Insert
    suspend fun insert(e: SmartRouteEntity): Long

    @Insert
    suspend fun insertAll(list: List<SmartRouteEntity>)

    @Update
    suspend fun update(e: SmartRouteEntity)

    @Update
    suspend fun updateAll(list: List<SmartRouteEntity>)

    @Delete
    suspend fun delete(e: SmartRouteEntity)
}

@Dao
interface SmartRouteGroupDao {
    @Query("SELECT * FROM smart_route_groups ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SmartRouteGroupEntity>>

    @Query("SELECT * FROM smart_route_groups ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<SmartRouteGroupEntity>

    @Query("SELECT * FROM smart_route_groups WHERE groupKey = :key LIMIT 1")
    suspend fun byKey(key: String): SmartRouteGroupEntity?

    @Insert
    suspend fun insert(g: SmartRouteGroupEntity): Long

    @Insert
    suspend fun insertAll(list: List<SmartRouteGroupEntity>)

    @Update
    suspend fun update(g: SmartRouteGroupEntity)

    @Update
    suspend fun updateAll(list: List<SmartRouteGroupEntity>)

    @Delete
    suspend fun delete(g: SmartRouteGroupEntity)

    @Query("DELETE FROM smart_route_groups")
    suspend fun clearAll()
}

@Dao
interface SmartRouteMemberDao {
    @Query("SELECT * FROM smart_route_members ORDER BY groupId ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SmartRouteMemberEntity>>

    @Query("SELECT * FROM smart_route_members ORDER BY groupId ASC, sortOrder ASC, id ASC")
    suspend fun all(): List<SmartRouteMemberEntity>

    @Query("SELECT * FROM smart_route_members WHERE groupId = :groupId ORDER BY sortOrder ASC, id ASC")
    suspend fun forGroup(groupId: Long): List<SmartRouteMemberEntity>

    @Insert
    suspend fun insert(m: SmartRouteMemberEntity): Long

    @Insert
    suspend fun insertAll(list: List<SmartRouteMemberEntity>)

    @Update
    suspend fun update(m: SmartRouteMemberEntity)

    @Update
    suspend fun updateAll(list: List<SmartRouteMemberEntity>)

    @Delete
    suspend fun delete(m: SmartRouteMemberEntity)

    @Query("DELETE FROM smart_route_members")
    suspend fun clearAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Delete
    suspend fun delete(c: ConversationEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET providerId = :providerId, modelName = :modelName WHERE id = :id")
    suspend fun setModel(id: Long, providerId: Long?, modelName: String?)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("UPDATE conversations SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("UPDATE conversations SET thinkingDepth = :depth WHERE id = :id")
    suspend fun setThinkingDepth(id: Long, depth: String?)

    @Query("UPDATE conversations SET systemPrompt = :prompt WHERE id = :id")
    suspend fun setSystemPrompt(id: Long, prompt: String?)

    /** 全局搜索：会话标题或任一条消息内容命中即返回（不搜 imagesJson，避开 base64 噪声），按最近活跃排序 */
    @Query(
        "SELECT DISTINCT conversations.* FROM conversations " +
            "LEFT JOIN messages ON messages.conversationId = conversations.id " +
            "WHERE conversations.title LIKE '%' || :query || '%' " +
            "OR messages.content LIKE '%' || :query || '%' " +
            "ORDER BY conversations.updatedAt DESC"
    )
    suspend fun search(query: String): List<ConversationEntity>

    /** 分组内无消息、无系统提示词的空会话（新建对话前复用它，保证每组只有一个空窗口） */
    @Query(
        "SELECT * FROM conversations WHERE " +
            "((:folderId IS NULL AND folderId IS NULL) OR folderId = :folderId) " +
            "AND systemPrompt IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id) " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun findEmptyConversation(folderId: Long?): ConversationEntity?

    /** 分组内由同一预设创建、尚未使用的空会话（点击预设时复用，不重复建窗口） */
    @Query(
        "SELECT * FROM conversations WHERE " +
            "((:folderId IS NULL AND folderId IS NULL) OR folderId = :folderId) " +
            "AND title = :title AND systemPrompt IS :systemPrompt " +
            "AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id) " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun findEmptyByPreset(folderId: Long?, title: String, systemPrompt: String?): ConversationEntity?

    /** 全部会话脱离文件夹（配置导入清空重建 folders 前调用，聊天记录不动） */
    @Query("UPDATE conversations SET folderId = NULL")
    suspend fun clearAllFolderRefs()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Insert
    suspend fun insert(f: FolderEntity): Long

    @Insert
    suspend fun insertAll(list: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun clearAll()

    @Update
    suspend fun update(f: FolderEntity)

    @Delete
    suspend fun delete(f: FolderEntity)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets ORDER BY createdAt ASC")
    suspend fun all(): List<PresetEntity>

    @Query("SELECT * FROM presets WHERE folderId = :folderId ORDER BY createdAt ASC")
    suspend fun forFolder(folderId: Long): List<PresetEntity>

    @Insert
    suspend fun insert(p: PresetEntity): Long

    @Insert
    suspend fun insertAll(list: List<PresetEntity>)

    @Query("DELETE FROM presets")
    suspend fun clearAll()

    @Update
    suspend fun update(p: PresetEntity)

    @Delete
    suspend fun delete(p: PresetEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    /** 单条消息实时观察，供自由复制等按 id 打开的页面 */
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    /** 会话内 id ≤ messageId 的消息（含目标消息），供创建分支复制历史 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND id <= :messageId ORDER BY id ASC")
    suspend fun forConversationUpTo(conversationId: Long, messageId: Long): List<MessageEntity>

    /** 删除会话内 id ≥（inclusive）/ ＞（否则）fromId 的消息，供重新生成清理旧回复 */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteFromInclusive(conversationId: Long, fromId: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :fromId")
    suspend fun deleteAfter(conversationId: Long, fromId: Long): Int

    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Update
    suspend fun update(m: MessageEntity)

    /** 我的创作：全部带图的助手消息（新→旧），页面侧再按 data: 前缀滤掉已清理的占位标记 */
    @Query(
        "SELECT * FROM messages WHERE role = 'assistant' AND imagesJson IS NOT NULL " +
            "AND imagesJson != '' ORDER BY createdAt DESC"
    )
    fun observeCreations(): Flow<List<MessageEntity>>

    /** 会话内首条命中搜索词的消息内容，供搜索结果展示命中片段 */
    @Query(
        "SELECT content FROM messages WHERE conversationId = :conversationId " +
            "AND content LIKE '%' || :query || '%' ORDER BY id ASC LIMIT 1"
    )
    suspend fun firstMatchContent(conversationId: Long, query: String): String?

    @Delete
    suspend fun delete(m: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)
}

@Dao
interface AuthDeviceDao {
    @Query("SELECT * FROM auth_devices ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<AuthDeviceEntity>>

    @Query("SELECT * FROM auth_devices ORDER BY lastSeenAt DESC")
    suspend fun all(): List<AuthDeviceEntity>

    @Query("SELECT * FROM auth_devices WHERE status = :status ORDER BY lastSeenAt DESC")
    fun observeByStatus(status: String): Flow<List<AuthDeviceEntity>>

    @Query("SELECT * FROM auth_devices WHERE deviceId = :deviceId")
    suspend fun byId(deviceId: String): AuthDeviceEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(d: AuthDeviceEntity)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<AuthDeviceEntity>)

    @Query("DELETE FROM auth_devices")
    suspend fun clearAll()

    @Query("UPDATE auth_devices SET label = :label WHERE deviceId = :deviceId")
    suspend fun setLabel(deviceId: String, label: String?)

    @Query("UPDATE auth_devices SET responseMode = :mode WHERE deviceId = :deviceId")
    suspend fun setResponseMode(deviceId: String, mode: String)

    @Query("DELETE FROM auth_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

/** 按设备聚合的用量统计（请求日志页展示） */
data class DeviceUsageStat(
    val deviceId: String,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val lastAt: Long,
)

/** 一段时间窗内的整体统计 */
data class LogStats(
    val total: Int,
    val okCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
)

@Dao
interface RequestLogDao {
    @Query("SELECT * FROM request_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RequestLogEntity>>

    @Insert
    suspend fun insert(e: RequestLogEntity): Long

    /** 只保留最近 keep 条，写入时调用防表无限膨胀 */
    @Query("DELETE FROM request_logs WHERE id NOT IN (SELECT id FROM request_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM request_logs")
    suspend fun clear()

    /** 时间窗内的整体统计（单行，无记录时各列为 0） */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END), 0) AS okCount, " +
            "COALESCE(SUM(promptTokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completionTokens), 0) AS completionTokens " +
            "FROM request_logs WHERE at >= :since"
    )
    fun observeStatsSince(since: Long): Flow<LogStats>

    /** 时间窗内按设备聚合的用量 */
    @Query(
        "SELECT deviceId, COUNT(*) AS requests, " +
            "COALESCE(SUM(promptTokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completionTokens), 0) AS completionTokens, " +
            "MAX(at) AS lastAt " +
            "FROM request_logs WHERE at >= :since GROUP BY deviceId ORDER BY requests DESC"
    )
    fun observeUsageByDeviceSince(since: Long): Flow<List<DeviceUsageStat>>
}

@Dao
interface NetworkLogDao {
    @Query("SELECT * FROM network_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NetworkLogEntity>>

    /** 单条日志实时观察，供请求详情页按 id 打开 */
    @Query("SELECT * FROM network_logs WHERE id = :id")
    fun observeById(id: Long): Flow<NetworkLogEntity?>

    @Insert
    suspend fun insert(e: NetworkLogEntity): Long

    /** 只保留最近 keep 条，写入时调用防表无限膨胀 */
    @Query("DELETE FROM network_logs WHERE id NOT IN (SELECT id FROM network_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM network_logs")
    suspend fun clear()
}

@Dao
interface VideoJobDao {
    @Query("SELECT * FROM video_jobs WHERE status IN ('pending','running') ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<VideoJobEntity>>

    @Query("SELECT * FROM video_jobs WHERE status IN ('pending','running') ORDER BY createdAt ASC")
    suspend fun active(): List<VideoJobEntity>

    @Query("SELECT * FROM video_jobs WHERE status IN ('pending','running') ORDER BY createdAt ASC")
    fun activeBlocking(): List<VideoJobEntity>

    @Query("SELECT * FROM video_jobs WHERE status NOT IN ('pending','running') ORDER BY updatedAt DESC LIMIT 30")
    suspend fun recentTerminal(): List<VideoJobEntity>

    @Query("SELECT * FROM video_jobs WHERE id = :id")
    suspend fun byId(id: Long): VideoJobEntity?

    @Insert
    suspend fun insert(job: VideoJobEntity): Long

    @Update
    suspend fun update(job: VideoJobEntity)
}
