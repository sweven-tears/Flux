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

    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun byId(id: Long): ApiKeyEntity?

    @Insert
    suspend fun insert(k: ApiKeyEntity): Long

    @Update
    suspend fun update(k: ApiKeyEntity)

    @Delete
    suspend fun delete(k: ApiKeyEntity)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeForProvider(providerId: Long): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE enabled = 1 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun forProvider(providerId: Long): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId AND name = :name LIMIT 1")
    suspend fun find(providerId: Long, name: String): ModelEntity?

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
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Insert
    suspend fun insert(f: FolderEntity): Long

    @Update
    suspend fun update(f: FolderEntity)

    @Delete
    suspend fun delete(f: FolderEntity)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE folderId = :folderId ORDER BY createdAt ASC")
    suspend fun forFolder(folderId: Long): List<PresetEntity>

    @Insert
    suspend fun insert(p: PresetEntity): Long

    @Update
    suspend fun update(p: PresetEntity)

    @Delete
    suspend fun delete(p: PresetEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

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

    @Query("SELECT * FROM auth_devices WHERE status = :status ORDER BY lastSeenAt DESC")
    fun observeByStatus(status: String): Flow<List<AuthDeviceEntity>>

    @Query("SELECT * FROM auth_devices WHERE deviceId = :deviceId")
    suspend fun byId(deviceId: String): AuthDeviceEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(d: AuthDeviceEntity)

    @Query("UPDATE auth_devices SET label = :label WHERE deviceId = :deviceId")
    suspend fun setLabel(deviceId: String, label: String?)

    @Query("UPDATE auth_devices SET responseMode = :mode WHERE deviceId = :deviceId")
    suspend fun setResponseMode(deviceId: String, mode: String)

    @Query("DELETE FROM auth_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
