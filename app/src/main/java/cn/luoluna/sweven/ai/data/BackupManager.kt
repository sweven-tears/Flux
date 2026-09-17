package cn.luoluna.sweven.ai.data

import androidx.room.withTransaction
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteMemberEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 备份文件内容：与配置实体字段一一对应，保留原始 id 以保证导入后引用完整 */
@Serializable
data class BackupDto(
    val version: Int,
    val exportedAt: Long,
    val providers: List<ProviderEntity> = emptyList(),
    val apiKeys: List<ApiKeyEntity> = emptyList(),
    val models: List<ModelEntity> = emptyList(),
    val smartRoutes: List<SmartRouteEntity> = emptyList(),
    val smartRouteGroups: List<SmartRouteGroupEntity> = emptyList(),
    val smartRouteMembers: List<SmartRouteMemberEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val presets: List<PresetEntity> = emptyList(),
    val authDevices: List<AuthDeviceEntity> = emptyList(),
    /** AppSettings 里的路由参数等，统一按字符串存储（布尔为 "true"/"false"） */
    val settings: Map<String, String> = emptyMap(),
)

/** 备份摘要：导入确认弹窗与结果提示使用 */
data class BackupSummary(
    val exportedAt: Long?,
    val providers: Int,
    val apiKeys: Int,
    val models: Int,
    val smartRoutes: Int,
    val smartRouteGroups: Int,
    val smartRouteMembers: Int,
    val folders: Int,
    val presets: Int,
    val authDevices: Int,
)

/**
 * 配置备份/恢复：
 * - 导出为 JSON 快照（厂商、API Key、模型、智能路由链、分组、预设、设备授权、路由参数），
 *   不含会话与聊天消息。
 * - 导入在单个事务内全量替换配置表（聊天记录保留、会话先脱离文件夹），任一步失败整体回滚。
 */
class BackupManager(private val app: App) {

    private val db = app.database

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** 导出配置快照；includeKeys=false 时不导出 API Key 并清空 activeKeyId 引用 */
    suspend fun export(includeKeys: Boolean): String {
        val providers = db.providerDao().all()
        val dto = BackupDto(
            version = VERSION,
            exportedAt = System.currentTimeMillis(),
            providers = if (includeKeys) providers else providers.map { it.copy(activeKeyId = null) },
            apiKeys = if (includeKeys) db.apiKeyDao().all() else emptyList(),
            models = db.modelDao().all(),
            smartRoutes = db.smartRouteDao().all(),
            smartRouteGroups = db.smartRouteGroupDao().all(),
            smartRouteMembers = db.smartRouteMemberDao().all(),
            folders = db.folderDao().all(),
            presets = db.presetDao().all(),
            authDevices = db.authDeviceDao().all(),
            settings = exportSettings(),
        )
        return withContext(Dispatchers.IO) { json.encodeToString(dto) }
    }

    /** 解析备份文本并校验版本（不落库）；格式或版本不支持时抛 [IllegalArgumentException] */
    fun parse(text: String): BackupDto {
        val dto = try {
            json.decodeFromString<BackupDto>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件格式不正确：${e.message}")
        }
        if (dto.version != VERSION) {
            throw IllegalArgumentException("不支持的备份版本 v${dto.version}（当前支持 v$VERSION）")
        }
        return dto
    }

    fun summaryOf(dto: BackupDto) = BackupSummary(
        exportedAt = dto.exportedAt,
        providers = dto.providers.size,
        apiKeys = dto.apiKeys.size,
        models = dto.models.size,
        smartRoutes = dto.smartRoutes.size,
        smartRouteGroups = dto.smartRouteGroups.size,
        smartRouteMembers = dto.smartRouteMembers.size,
        folders = dto.folders.size,
        presets = dto.presets.size,
        authDevices = dto.authDevices.size,
    )

    /** 导入：单事务全量替换配置表；外键按子表→父表顺序清空、父表→子表顺序重建 */
    suspend fun import(dto: BackupDto) {
        db.withTransaction {
            // 会话先脱离文件夹（folders 将清空重建），聊天记录本身不动
            db.conversationDao().clearAllFolderRefs()
            db.apiKeyDao().clearAll()
            db.modelDao().clearAll()
            db.smartRouteMemberDao().clearAll()
            db.smartRouteGroupDao().clearAll()
            db.smartRouteDao().clearAll()
            db.presetDao().clearAll()
            db.folderDao().clearAll()
            db.providerDao().clearAll()
            db.providerDao().insertAll(dto.providers)
            db.apiKeyDao().insertAll(dto.apiKeys)
            db.modelDao().insertAll(dto.models)
            db.smartRouteDao().insertAll(dto.smartRoutes)
            db.smartRouteGroupDao().insertAll(dto.smartRouteGroups)
            db.smartRouteMemberDao().insertAll(dto.smartRouteMembers)
            db.folderDao().insertAll(dto.folders)
            db.presetDao().insertAll(dto.presets)
            db.authDeviceDao().clearAll()
            db.authDeviceDao().upsertAll(dto.authDevices)
            restoreSettings(dto.settings)
        }
    }

    private fun exportSettings(): Map<String, String> {
        val s = app.settings
        return buildMap {
            put("lanPort", s.lanPort.value.toString())
            put("smartFirstTokenTimeoutSeconds", s.smartFirstTokenTimeoutSeconds.value.toString())
            put("smartRouteMaxRounds", s.smartRouteMaxRounds.value.toString())
            put("lanSmartAiEnabled", s.lanSmartAiEnabled.value.toString())
            put("lanOpenAllModels", s.lanOpenAllModels.value.toString())
            put("smartResponseMode", s.smartResponseMode.value)
            put("smartFastFirstTokenMs", s.smartFastFirstTokenMs.value.toString())
            put("smartLeisureFirstTokenSeconds", s.smartLeisureFirstTokenSeconds.value.toString())
            put("smartCooldownThreshold", s.smartCooldownThreshold.value.toString())
            put("smartCooldownSeconds", s.smartCooldownSeconds.value.toString())
            put("logRequestsEnabled", s.logRequestsEnabled.value.toString())
            put("networkLogEnabled", s.networkLogEnabled.value.toString())
            put("networkLogKeep", s.networkLogKeep.value.toString())
            put("imageOpenExternal", s.imageOpenExternal.value.toString())
            put("videoOpenExternal", s.videoOpenExternal.value.toString())
        }
    }

    private fun restoreSettings(map: Map<String, String>) {
        val s = app.settings
        map["lanPort"]?.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { s.setLanPort(it) }
        map["smartFirstTokenTimeoutSeconds"]?.toIntOrNull()?.let { s.setSmartFirstTokenTimeout(it) }
        map["smartRouteMaxRounds"]?.toIntOrNull()?.let { s.setSmartRouteMaxRounds(it) }
        map["lanSmartAiEnabled"]?.toBooleanStrictOrNull()?.let { s.setLanSmartAiEnabled(it) }
        map["lanOpenAllModels"]?.toBooleanStrictOrNull()?.let { s.setLanOpenAllModels(it) }
        map["smartResponseMode"]?.let { s.setSmartResponseMode(it) }
        map["smartFastFirstTokenMs"]?.toIntOrNull()?.let { s.setSmartFastFirstToken(it) }
        map["smartLeisureFirstTokenSeconds"]?.toIntOrNull()?.let { s.setSmartLeisureFirstToken(it) }
        map["smartCooldownThreshold"]?.toIntOrNull()?.let { s.setSmartCooldownThreshold(it) }
        map["smartCooldownSeconds"]?.toIntOrNull()?.let { s.setSmartCooldownSeconds(it) }
        map["logRequestsEnabled"]?.toBooleanStrictOrNull()?.let { s.setLogRequestsEnabled(it) }
        map["networkLogEnabled"]?.toBooleanStrictOrNull()?.let { s.setNetworkLogEnabled(it) }
        map["networkLogKeep"]?.toIntOrNull()?.let { s.setNetworkLogKeep(it) }
        map["imageOpenExternal"]?.toBooleanStrictOrNull()?.let { s.setImageOpenExternal(it) }
        map["videoOpenExternal"]?.toBooleanStrictOrNull()?.let { s.setVideoOpenExternal(it) }
    }

    companion object {
        const val VERSION = 1
    }
}
