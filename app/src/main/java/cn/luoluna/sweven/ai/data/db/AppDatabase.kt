package cn.luoluna.sweven.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ApiKeyEntity::class,
        ModelEntity::class,
        SmartRouteEntity::class,
        SmartRouteGroupEntity::class,
        SmartRouteMemberEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AuthDeviceEntity::class,
        FolderEntity::class,
        PresetEntity::class,
        RequestLogEntity::class,
        VideoJobEntity::class,
        NetworkLogEntity::class,
        ModelAbnormalEntity::class,
        CustomToolEntity::class,
    ],
    version = 23,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun modelDao(): ModelDao
    abstract fun smartRouteDao(): SmartRouteDao
    abstract fun smartRouteGroupDao(): SmartRouteGroupDao
    abstract fun smartRouteMemberDao(): SmartRouteMemberDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun authDeviceDao(): AuthDeviceDao
    abstract fun folderDao(): FolderDao
    abstract fun presetDao(): PresetDao
    abstract fun requestLogDao(): RequestLogDao
    abstract fun videoJobDao(): VideoJobDao
    abstract fun networkLogDao(): NetworkLogDao
    abstract fun modelAbnormalDao(): ModelAbnormalDao
    abstract fun customToolDao(): CustomToolDao

    companion object {
        /** v1 → v2：新增文件夹、预设对话表；会话表增加 folderId / systemPrompt */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `presets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`folderId` INTEGER NOT NULL, `name` TEXT NOT NULL, `systemPrompt` TEXT, " +
                        "`providerId` INTEGER, `modelName` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_presets_folderId` ON `presets` (`folderId`)")
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `folderId` INTEGER")
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `systemPrompt` TEXT")
            }
        }

        /** v2 → v3：消息表增加思考内容字段 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
            }
        }

        /** v3 → v4：模型表新增别名/类型/能力/内置工具/自定义 headers 与 body */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `models` ADD COLUMN `alias` TEXT",
                    "ALTER TABLE `models` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'chat'",
                    "ALTER TABLE `models` ADD COLUMN `capabilityTools` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `models` ADD COLUMN `capabilityReasoning` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `models` ADD COLUMN `builtinGoogleSearch` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `models` ADD COLUMN `builtinUrlContext` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `models` ADD COLUMN `builtinImageGen` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `models` ADD COLUMN `customHeaders` TEXT",
                    "ALTER TABLE `models` ADD COLUMN `customBody` TEXT",
                ).forEach(db::execSQL)
            }
        }

        /** v4 → v5：会话表增加思考深度字段 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `thinkingDepth` TEXT")
            }
        }

        /** v5 → v6：厂商表增加备注字段 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `remark` TEXT")
            }
        }

        /** v6 → v7：厂商/模型表增加排序字段；新增智能路由候选表 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                // 按迁移前的展示顺序初始化排序权重：厂商按创建时间，模型按同厂商内名称
                db.execSQL(
                    "UPDATE `providers` SET `sortOrder` = (" +
                        "SELECT COUNT(*) FROM `providers` p2 WHERE p2.`createdAt` < `providers`.`createdAt`" +
                        ")"
                )
                db.execSQL(
                    "UPDATE `models` SET `sortOrder` = (" +
                        "SELECT COUNT(*) FROM `models` m2 WHERE m2.`providerId` = `models`.`providerId` " +
                        "AND (m2.`name` < `models`.`name` COLLATE NOCASE " +
                        "OR (m2.`name` = `models`.`name` AND m2.`id` < `models`.`id`))" +
                        ")"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_routes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`providerId` INTEGER NOT NULL, `modelName` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "FOREIGN KEY(`providerId`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_routes_providerId` ON `smart_routes` (`providerId`)")
            }
        }

        /** v7 → v8：设备表增加响应模式字段（智能路由按设备区分极速/聊天/随缘） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `auth_devices` ADD COLUMN `responseMode` TEXT NOT NULL DEFAULT 'chat'")
            }
        }

        /** v8 → v9：模型表增加上下文窗口字段；新增局域网请求日志表（实体与打点在后续版本启用） */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `contextWindow` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `request_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `ip` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, `via` TEXT, `stream` INTEGER NOT NULL, " +
                        "`ttftMs` INTEGER, `totalMs` INTEGER, `ok` INTEGER NOT NULL, " +
                        "`error` TEXT, `promptTokens` INTEGER, `completionTokens` INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_logs_at` ON `request_logs` (`at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_logs_deviceId` ON `request_logs` (`deviceId`)")
            }
        }

        /** v9 → v10：消息表增加生成模型字段（记录每条助手消息实际由哪个模型生成） */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `providerId` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `modelName` TEXT")
            }
        }

        /** v10 → v11：厂商表增加启用/禁用开关 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v11 → v12：厂商表增加接口协议字段（openai/gemini/anthropic） */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'openai'")
            }
        }

        /** v12 → v13：消息表增加 token 用量与耗时字段（助手消息的消耗展示） */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `promptTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `completionTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `cachedTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `ttftMs` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `totalMs` INTEGER")
            }
        }

        /** v13 → v14：新增智能路由分组表与分组成员表（flux-<groupKey> 组内切换） */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_route_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupKey` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL DEFAULT 0, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_route_members` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, `providerId` INTEGER NOT NULL, `modelName` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "FOREIGN KEY(`groupId`) REFERENCES `smart_route_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_route_members_groupId` ON `smart_route_members` (`groupId`)")
            }
        }

        /** v14 → v15：新增视频生成任务表（提交/轮询/下载生命周期落库） */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` INTEGER NOT NULL, `providerId` INTEGER NOT NULL, `modelName` TEXT NOT NULL, " +
                        "`prompt` TEXT NOT NULL, `upstreamJobId` TEXT, `status` TEXT NOT NULL, " +
                        "`videoPath` TEXT, `error` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v15 → v16：模型表增加视频接口形态字段（首次探测成功后持久化，免重复探测） */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `videoApiShape` TEXT")
            }
        }

        /** v16 → v17：新增全局网络日志表（出站请求打点，设置页开关与保留条数可配） */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `tag` TEXT NOT NULL, `method` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`httpCode` INTEGER, `ok` INTEGER NOT NULL, `durationMs` INTEGER, " +
                        "`requestHeaders` TEXT, `requestBody` TEXT, `responseHeaders` TEXT, " +
                        "`responseBody` TEXT, `error` TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_logs_at` ON `network_logs` (`at`)")
            }
        }

        /** v17 → v18：新增模型异常记录表；厂商表增加手动熔断/可重试决策列 */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `model_abnormals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `providerId` INTEGER NOT NULL, `providerAlias` TEXT NOT NULL, " +
                        "`modelName` TEXT NOT NULL, `via` TEXT NOT NULL, `httpCode` INTEGER, `error` TEXT, " +
                        "`decision` TEXT NOT NULL DEFAULT 'PENDING', `decidedAt` INTEGER, " +
                        "`routeTriggered` INTEGER NOT NULL DEFAULT 0, `count` INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_abnormals_at` ON `model_abnormals` (`at`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_model_abnormals_providerId` ON `model_abnormals` (`providerId`)"
                )
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `circuitOpen` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `circuitOpenAt` INTEGER")
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `retryable` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v18 → v19：会话表增加工具勾选列；消息表增加工具调用/结果配对列（role=tool） */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `toolsJson` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `toolCallsJson` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `toolCallId` TEXT")
            }
        }

        /** v19 → v20：新增自定义工具表（工具中心里用户自建的 HTTP/JS 工具） */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_tools` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `displayName` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`parametersJson` TEXT, `kind` TEXT NOT NULL, `configJson` TEXT NOT NULL, " +
                        "`needsApproval` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_tools_name` ON `custom_tools` (`name`)")
            }
        }

        /** v20 → v21：模型绑定密钥（apiKeyId）+ 对外引用名（refName） */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `apiKeyId` INTEGER")
                db.execSQL("ALTER TABLE `models` ADD COLUMN `refName` TEXT")
                // 旧内容兼容：已有模型统一归到「当前启动的密钥」（无则最早一把），
                // 其余密钥需要重新获取模型列表来绑定自己的模型。
                db.execSQL(
                    "UPDATE `models` SET `apiKeyId` = COALESCE(" +
                        "(SELECT p.`activeKeyId` FROM `providers` p " +
                        " WHERE p.`id` = `models`.`providerId` " +
                        "   AND p.`activeKeyId` IN (SELECT `id` FROM `api_keys`)), " +
                        "(SELECT k.`id` FROM `api_keys` k " +
                        " WHERE k.`providerId` = `models`.`providerId` " +
                        " ORDER BY k.`createdAt` ASC, k.`id` ASC LIMIT 1))"
                )
                // refName 默认等于上游模型 id：旧会话 / 预设 / 网关引用全部保持有效
                db.execSQL("UPDATE `models` SET `refName` = `name`")
                // 旧库没有唯一约束，可能已存在同厂商同名重复行：保留最小 id，其余追加不重复后缀
                db.execSQL(
                    "UPDATE `models` SET `refName` = `refName` || '#dup' || `id` " +
                        "WHERE `id` NOT IN (SELECT MIN(`id`) FROM `models` GROUP BY `providerId`, `name`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_models_apiKeyId` ON `models` (`apiKeyId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_models_providerId_refName` " +
                        "ON `models` (`providerId`, `refName`)"
                )
            }
        }

        /** v21 → v22：厂商表增加余额查询配置（开关 / 是否按密钥 / URL 模板 / 请求头 / 结果键） */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `balanceEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `providers` ADD COLUMN `balancePerKey` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE `providers` ADD COLUMN `balanceUrl` TEXT NOT NULL " +
                        "DEFAULT '{{baseUrl}}/credits'"
                )
                db.execSQL(
                    "ALTER TABLE `providers` ADD COLUMN `balanceHeaders` TEXT NOT NULL " +
                        "DEFAULT 'Authorization: Bearer {{apiKey}}'"
                )
                db.execSQL(
                    "ALTER TABLE `providers` ADD COLUMN `balanceJsonKey` TEXT NOT NULL " +
                        "DEFAULT 'data.total_usage'"
                )
            }
        }

        /** v22 → v23：设备配对令牌摘要、将旧明文密钥转换为 Keystore 密文。
         * Room 在事务内执行迁移；加密失败时中止升级，保留原库，不清库降级。
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `auth_devices` ADD COLUMN `tokenHash` TEXT NOT NULL DEFAULT ''")
                db.query("PRAGMA secure_delete=ON").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 1) { "无法启用密钥安全迁移" }
                }
                val encryptedKeys = mutableListOf<Pair<Long, String>>()
                val oldSecrets = mutableListOf<String>()
                db.query("SELECT `id`, `key` FROM `api_keys`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val plain = cursor.getString(1)
                        oldSecrets += plain
                        encryptedKeys += cursor.getLong(0) to
                            cn.luoluna.sweven.ai.data.security.KeyVault.encrypt(plain)
                    }
                }
                // 旧日志也可能包含有效的原始密钥；保留诊断结构，先脱敏再清理空闲页。
                val diagnosticColumns = mapOf(
                    "network_logs" to listOf("url", "requestHeaders", "requestBody", "responseHeaders", "responseBody", "error"),
                    "request_logs" to listOf("error"),
                    "model_abnormals" to listOf("error"),
                    "video_jobs" to listOf("error"),
                    "providers" to listOf("lastTestMessage"),
                )
                diagnosticColumns.forEach { (table, columns) ->
                    var lastId: Long? = null
                    while (true) {
                        // 分页避免升级时复制整张大日志表。旧认证头直接置空，无法可靠识别任意自定义头。
                        val rewritten = mutableListOf<Array<Any?>>()
                        val where = if (lastId == null) "" else " WHERE `id` > ?"
                        val args = lastId?.let { arrayOf<Any>(it) } ?: emptyArray<Any>()
                        db.query("SELECT `id`, ${columns.joinToString { "`$it`" }} FROM `$table`$where ORDER BY `id` LIMIT 25", args).use { cursor ->
                            while (cursor.moveToNext()) {
                                val values = columns.mapIndexed { index, column ->
                                    if (cursor.isNull(index + 1) || column == "requestHeaders" || column == "responseHeaders") null else
                                        cn.luoluna.sweven.ai.net.SensitiveDataRedactor.redactText(
                                            cursor.getString(index + 1), knownSecrets = oldSecrets,
                                            maxLength = if (table == "network_logs" && column != "error") 24_000 else 300,
                                        )
                                }.toMutableList<Any?>()
                                lastId = cursor.getLong(0)
                                values += lastId
                                rewritten += values.toTypedArray()
                            }
                        }
                        if (rewritten.isEmpty()) break
                        rewritten.forEach { values ->
                            db.execSQL("UPDATE `$table` SET ${columns.joinToString { "`$it` = ?" }} WHERE `id` = ?", values)
                        }
                    }
                }
                encryptedKeys.forEach { (id, encrypted) ->
                    db.execSQL("UPDATE `api_keys` SET `key` = ? WHERE `id` = ?", arrayOf(encrypted, id))
                }
            }
        }

        fun build(context: Context): AppDatabase = build(context, "flux.db")

        internal fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // UPDATE 不能保证擦除旧空闲页/WAL。升级后只做一次重写与截断，
                        // 成功前不落标记；异常中止打开，避免把未清理误记为完成。
                        val preferences = context.applicationContext.getSharedPreferences("security_migration", Context.MODE_PRIVATE)
                        val marker = "${name}_encrypted_storage_v23_sanitized"
                        if (!preferences.getBoolean(marker, false)) {
                            fun checkpoint() {
                                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                                        "密钥存储安全迁移等待数据库解锁"
                                    }
                                }
                            }
                            checkpoint()
                            db.execSQL("VACUUM")
                            checkpoint()
                            check(preferences.edit().putBoolean(marker, true).commit()) {
                                "密钥存储安全迁移未完成"
                            }
                        }
                    }
                })
                .build()
    }
}
