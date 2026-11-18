package cn.luoluna.sweven.ai.data

import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import org.junit.Assert.*
import org.junit.Test

class BackupSecurityTest {
    @Test fun maliciousBackupCannotRestoreSecurityPermissions() {
        val input = mapOf(
            "scriptToolAutoApprove" to "true",
            "lanEnabled" to "true",
            "searchEndpointUserConfirmed" to "true",
            "script_tool_auto_approve" to "true",
            "lan_enabled" to "true",
            "search_endpoint_user_confirmed" to "true",
            "searchCount" to "6",
        )
        assertEquals(mapOf("searchCount" to "6"), BackupSecurity.safeSettings(input))
        assertEquals(3, BackupSecurity.ignoredSettingLabels(input).size)
    }

    @Test fun deviceRecordsNeverTransferPairingCredentials() {
        val original = AuthDeviceEntity(
            deviceId = "test-device",
            lastIp = "192.168.1.5",
            status = AuthDeviceEntity.STATUS_AUTHORIZED,
            authorizedAt = 1L,
            tokenHash = "should-never-be-restored",
        )
        val sanitized = BackupSecurity.resetDevices(listOf(original)).single()
        assertEquals(AuthDeviceEntity.STATUS_PENDING, sanitized.status)
        assertEquals("", sanitized.tokenHash)
        assertNull(sanitized.authorizedAt)
        assertEquals(original.deviceId, sanitized.deviceId)
        assertEquals("should-never-be-restored", original.tokenHash)
    }
}
