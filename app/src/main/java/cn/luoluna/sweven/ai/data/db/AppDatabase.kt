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
        ConversationEntity::class,
        MessageEntity::class,
        AuthDeviceEntity::class,
        FolderEntity::class,
        PresetEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun modelDao(): ModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun authDeviceDao(): AuthDeviceDao
    abstract fun folderDao(): FolderDao
    abstract fun presetDao(): PresetDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "phone_router.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
    }
}
