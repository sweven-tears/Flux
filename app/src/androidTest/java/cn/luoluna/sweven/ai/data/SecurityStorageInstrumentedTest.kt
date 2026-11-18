package cn.luoluna.sweven.ai.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.AppDatabase
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.security.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** 真 Android SQLite / Room / Keystore 回归，所有数据使用独立的测试库和偏好名。 */
class SecurityStorageInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null
    private val secret = "sk-instrumented-security-only-9876543210"

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "security-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
            override fun getApplicationContext(): Context = this
        }
        databaseName = "${prefix}flux.db"
    }

    @After fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("security_migration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun db(): AppDatabase = database ?: AppDatabase.build(context, databaseName).also { database = it }

    @Test fun daoEncryptsEveryWriteAndDecryptsEveryRead() = runBlocking {
        val db = db()
        val providerId = db.providerDao().insert(ProviderEntity(alias = "test", baseUrl = "https://example.com"))
        val id = db.apiKeyDao().insert(ApiKeyEntity(providerId = providerId, alias = "one", key = secret))
        assertEquals(secret, db.apiKeyDao().byId(id)?.key)
        assertEquals(secret, db.apiKeyDao().observeForProvider(providerId).first().single().key)
        val original = db.apiKeyDao().byId(id)!!
        db.apiKeyDao().update(original.copy(key = "$secret-updated"))
        db.apiKeyDao().insertAll(listOf(ApiKeyEntity(providerId = providerId, alias = "two", key = "$secret-bulk")))
        assertEquals(2, db.apiKeyDao().forProvider(providerId).size)
        assertEquals(2, db.apiKeyDao().all().size)
        db.openHelper.writableDatabase.query("SELECT `key` FROM api_keys").use { cursor ->
            while (cursor.moveToNext()) {
                val stored = cursor.getString(0)
                assertTrue(SecretCipher.isEncrypted(stored))
                assertFalse(stored.contains(secret))
            }
        }
    }

    @Test fun version22MigrationEncryptsPlainKeysPreservesRowsAndRemovesDiskRemnants() = runBlocking {
        // DROP COLUMN 仅用于构建升级夹具；产品迁移本身兼容 minSdk 26。
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val initial = db()
        val providerId = initial.providerDao().insert(ProviderEntity(alias = "preserved", baseUrl = "https://example.com"))
        initial.apiKeyDao().insert(ApiKeyEntity(providerId = providerId, alias = "legacy", key = secret))
        initial.authDeviceDao().upsertAll(listOf(AuthDeviceEntity(
            deviceId = "legacy-device", lastIp = "192.168.1.2", status = AuthDeviceEntity.STATUS_AUTHORIZED,
        )))
        initial.close()
        database = null
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            legacy.execSQL("UPDATE api_keys SET `key` = ?", arrayOf(secret))
            legacy.execSQL("UPDATE providers SET lastTestMessage = ?", arrayOf("upstream rejected $secret"))
            legacy.execSQL(
                "INSERT INTO network_logs (at, tag, method, url, ok, requestBody, error) VALUES (0, 'chat', 'POST', 'https://example.com', 0, ?, ?)",
                arrayOf("{\"apiKey\":\"$secret\"}", "upstream rejected $secret"),
            )
            legacy.execSQL("ALTER TABLE auth_devices DROP COLUMN tokenHash")
            legacy.execSQL("DROP TABLE room_master_table")
            legacy.version = 22
        }
        context.getSharedPreferences("security_migration", Context.MODE_PRIVATE).edit().clear().commit()
        val upgraded = db()
        assertEquals(secret, upgraded.apiKeyDao().all().single().key)
        assertEquals("preserved", upgraded.providerDao().all().single().alias)
        assertFalse(upgraded.providerDao().all().single().lastTestMessage.orEmpty().contains(secret))
        upgraded.openHelper.writableDatabase.query("SELECT requestBody, error FROM network_logs").use {
            assertTrue(it.moveToFirst())
            assertFalse(it.getString(0).contains(secret))
            assertFalse(it.getString(1).contains(secret))
        }
        assertEquals("", upgraded.authDeviceDao().byId("legacy-device")?.tokenHash)
        upgraded.openHelper.writableDatabase.query("SELECT `key` FROM api_keys").use {
            assertTrue(it.moveToFirst())
            assertTrue(SecretCipher.isEncrypted(it.getString(0)))
        }
        upgraded.close()
        database = null
        listOf("", "-wal", "-shm", "-journal").map { File(context.getDatabasePath(databaseName).path + it) }
            .filter { it.exists() }.forEach { file ->
                assertFalse("明文残留于 ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
            }
    }

    @Test fun settingsMigrateSearchKeyAndNeverPersistAutoApproval() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("search_api_key", secret).putBoolean("script_tool_auto_approve", true).commit()
        val settings = AppSettings(context)
        assertEquals(secret, settings.searchApiKey.value)
        assertTrue(SecretCipher.isEncrypted(prefs.getString("search_api_key", "")!!))
        assertFalse(settings.scriptToolAutoApprove.value)
        settings.setScriptToolAutoApprove(true)
        assertTrue(settings.scriptToolAutoApprove.value)
        assertFalse(prefs.contains("script_tool_auto_approve"))
        assertFalse(AppSettings(context).scriptToolAutoApprove.value)
        settings.setSearchApiKey("$secret-replaced")
        assertEquals("$secret-replaced", AppSettings(context).searchApiKey.value)
        settings.setSearchEndpoint("http://192.168.1.9:8080", userConfirmed = false)
        assertEquals(false, settings.searchEndpointSnapshot().second)
        settings.setSearchEndpoint("http://192.168.1.9:8080")
        assertEquals(true, settings.searchEndpointSnapshot().second)
    }

    @Test fun failedBackupImportRollsBackDatabaseAndOrdinarySettingsButResetsAutoApproval() = runBlocking {
        val db = db()
        val settings = AppSettings(context)
        val oldProviderId = db.providerDao().insert(
            ProviderEntity(alias = "preserved-provider", baseUrl = "https://old.example.com"),
        )
        db.apiKeyDao().insert(ApiKeyEntity(providerId = oldProviderId, alias = "preserved-key", key = secret))
        settings.setLanPort(9876)
        settings.setSearchEndpoint("https://search-before.example.com", userConfirmed = true)
        settings.setScriptToolAutoApprove(true)

        val manager = BackupManager(db, settings)
        val invalidImport = BackupDto(
            version = BackupManager.VERSION,
            exportedAt = System.currentTimeMillis(),
            providers = listOf(ProviderEntity(alias = "replacement-provider", baseUrl = "https://new.example.com")),
            // Provider replacement happens first; this invalid foreign key must abort and roll back the transaction.
            apiKeys = listOf(ApiKeyEntity(providerId = Long.MAX_VALUE, alias = "invalid-key", key = "invalid-secret")),
            settings = mapOf(
                "lanPort" to "9999",
                "searchEndpoint" to "https://search-after.example.com",
                "searchEndpointUserConfirmed" to "true",
            ),
        )

        val failure = runCatching { manager.import(invalidImport) }.exceptionOrNull()
        assertNotNull("Importing an API key with no provider must fail", failure)

        assertEquals("preserved-provider", db.providerDao().all().single().alias)
        assertEquals(secret, db.apiKeyDao().all().single().key)
        assertEquals(9876, settings.lanPort.value)
        assertEquals(
            "https://search-before.example.com" to true,
            settings.searchEndpointSnapshot(),
        )
        // This security reset intentionally occurs before the transaction and remains effective on failure.
        assertFalse(settings.scriptToolAutoApprove.value)
    }

    @Test fun backupDefaultsExcludeKeysAndImportCannotGrantPermissions() = runBlocking {
        val db = db()
        val settings = AppSettings(context)
        val providerId = db.providerDao().insert(ProviderEntity(alias = "test", baseUrl = "https://example.com"))
        db.apiKeyDao().insert(ApiKeyEntity(providerId = providerId, alias = "key", key = secret))
        db.authDeviceDao().upsertAll(listOf(AuthDeviceEntity(
            deviceId = "paired", lastIp = "192.168.1.2", status = AuthDeviceEntity.STATUS_AUTHORIZED,
            tokenHash = "sensitive-token-hash",
        )))
        val manager = BackupManager(db, settings)
        val defaultBackup = manager.parse(manager.export())
        assertTrue(defaultBackup.apiKeys.isEmpty())
        assertEquals("", defaultBackup.authDevices.single().tokenHash)
        val fullBackup = manager.parse(manager.export(includeKeys = true))
        assertEquals(secret, fullBackup.apiKeys.single().key)
        settings.setScriptToolAutoApprove(true)
        val malicious = fullBackup.copy(
            settings = mapOf("scriptToolAutoApprove" to "true", "lanEnabled" to "true",
                "searchEndpoint" to "http://192.168.1.9:8080", "searchEndpointUserConfirmed" to "true"),
            authDevices = fullBackup.authDevices.map { it.copy(status = AuthDeviceEntity.STATUS_AUTHORIZED, tokenHash = "forged") },
            customTools = listOf(CustomToolEntity(name = "unsafe", displayName = "unsafe", description = "test",
                kind = "http", configJson = "{}", needsApproval = false)),
        )
        assertEquals(3, manager.summaryOf(malicious).ignoredSecuritySettings.size)
        manager.import(malicious)
        assertFalse(settings.scriptToolAutoApprove.value)
        assertFalse(settings.lanEnabled.value)
        assertFalse(settings.searchEndpointSnapshot().second)
        val restored = db.authDeviceDao().byId("paired")!!
        assertEquals(AuthDeviceEntity.STATUS_PENDING, restored.status)
        assertEquals("", restored.tokenHash)
        assertTrue(db.customToolDao().all().single().needsApproval)
        assertEquals(secret, db.apiKeyDao().all().single().key)
    }
}
