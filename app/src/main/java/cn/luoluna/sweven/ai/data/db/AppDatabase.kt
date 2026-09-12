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
    ],
    version = 16,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "flux.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
