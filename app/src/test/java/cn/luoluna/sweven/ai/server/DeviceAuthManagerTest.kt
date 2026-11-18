package cn.luoluna.sweven.ai.server

import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthManagerTest {
    @Test fun authorizedDeviceNeedsCorrectToken() = runBlocking {
        val token = DeviceAuthSecurity.newToken()
        val dao = MemoryAuthDao(authorized(token))
        val manager = DeviceAuthManager(dao)

        assertEquals(AuthDecision.InvalidToken, manager.check("phone", null, "10.0.0.2"))
        assertEquals(AuthDecision.InvalidToken, manager.check("phone", "0".repeat(64), "10.0.0.2"))
        assertEquals(10, dao.row!!.requestCount)

        val allowed = manager.check("phone", token, "10.0.0.2") as AuthDecision.Allowed
        assertEquals("phone", allowed.device.deviceId)
        assertEquals(11, dao.row!!.requestCount)
        assertEquals("10.0.0.2", dao.row!!.lastIp)
    }

    @Test fun legacyAuthorizedDeviceIsMovedToPendingForExplicitRepair() = runBlocking {
        val dao = MemoryAuthDao(authorized(tokenHash = ""))
        var notified = false
        val manager = DeviceAuthManager(dao, pendingNotifier = { id, ip -> notified = id == "phone" && ip == "10.0.0.3" })

        assertEquals(AuthDecision.NeedsApproval(isNew = false), manager.check("phone", null, "10.0.0.3"))
        assertEquals(AuthDeviceEntity.STATUS_PENDING, dao.row!!.status)
        assertEquals("", dao.row!!.tokenHash)
        assertNull(dao.row!!.authorizedAt)
        assertTrue(notified)
    }

    @Test fun revokeRaceCannotAllowRequestAfterConditionalCounterUpdateFails() = runBlocking {
        val token = DeviceAuthSecurity.newToken()
        val dao = MemoryAuthDao(authorized(token))
        dao.beforeAuthorizedBump = { row -> dao.row = row.copy(status = AuthDeviceEntity.STATUS_BLOCKED) }
        val manager = DeviceAuthManager(dao)

        assertEquals(AuthDecision.Blocked, manager.check("phone", token, "10.0.0.4"))
        assertFalse(dao.row!!.status == AuthDeviceEntity.STATUS_AUTHORIZED)
    }

    @Test fun approvalOnlyUpdatesPendingAndReturnsSinglePlaintextToken() = runBlocking {
        val dao = MemoryAuthDao(AuthDeviceEntity(deviceId = "phone", lastIp = "10.0.0.5", status = AuthDeviceEntity.STATUS_PENDING))
        val manager = DeviceAuthManager(dao)

        val token = manager.approve("phone")
        assertTrue(token != null && DeviceAuthSecurity.matches(dao.row!!.tokenHash, token))
        assertEquals(AuthDeviceEntity.STATUS_AUTHORIZED, dao.row!!.status)
        assertNull(manager.approve("phone"))
    }

    private fun authorized(token: String? = null, tokenHash: String = token?.let { DeviceAuthSecurity.sha256Hex(it) }.orEmpty()) =
        AuthDeviceEntity(
            deviceId = "phone",
            lastIp = "10.0.0.1",
            status = AuthDeviceEntity.STATUS_AUTHORIZED,
            requestCount = 10,
            tokenHash = tokenHash,
            authorizedAt = 1L,
        )

    private class MemoryAuthDao(initial: AuthDeviceEntity? = null) : AuthDeviceDao {
        var row: AuthDeviceEntity? = initial
        var beforeAuthorizedBump: ((AuthDeviceEntity) -> Unit)? = null

        override fun observeAll(): Flow<List<AuthDeviceEntity>> = flowOf(listOfNotNull(row))
        override suspend fun all(): List<AuthDeviceEntity> = listOfNotNull(row)
        override fun observeByStatus(status: String): Flow<List<AuthDeviceEntity>> = flowOf(listOfNotNull(row?.takeIf { it.status == status }))
        override suspend fun byId(deviceId: String): AuthDeviceEntity? = row?.takeIf { it.deviceId == deviceId }
        override suspend fun upsert(d: AuthDeviceEntity) { row = d }
        override suspend fun insertIfAbsent(d: AuthDeviceEntity): Long {
            if (row != null) return -1L
            row = d
            return 1L
        }
        override suspend fun bumpIfStatus(deviceId: String, status: String, ip: String, now: Long): Int {
            val current = row?.takeIf { it.deviceId == deviceId && it.status == status } ?: return 0
            row = current.copy(requestCount = current.requestCount + 1, lastSeenAt = now, lastIp = ip)
            return 1
        }
        override suspend fun bumpAuthorizedWithToken(deviceId: String, tokenHash: String, ip: String, now: Long): Int {
            val current = row?.takeIf { it.deviceId == deviceId && it.status == AuthDeviceEntity.STATUS_AUTHORIZED && it.tokenHash == tokenHash } ?: return 0
            beforeAuthorizedBump?.invoke(current)
            val latest = row?.takeIf { it.deviceId == deviceId && it.status == AuthDeviceEntity.STATUS_AUTHORIZED && it.tokenHash == tokenHash } ?: return 0
            row = latest.copy(requestCount = latest.requestCount + 1, lastSeenAt = now, lastIp = ip)
            return 1
        }
        override suspend fun approvePending(deviceId: String, tokenHash: String, now: Long): Int {
            val current = row?.takeIf { it.deviceId == deviceId && it.status == AuthDeviceEntity.STATUS_PENDING } ?: return 0
            row = current.copy(status = AuthDeviceEntity.STATUS_AUTHORIZED, tokenHash = tokenHash, authorizedAt = now)
            return 1
        }
        override suspend fun requireLegacyRepair(deviceId: String): Int {
            val current = row?.takeIf { it.deviceId == deviceId && it.status == AuthDeviceEntity.STATUS_AUTHORIZED && it.tokenHash.isEmpty() } ?: return 0
            row = current.copy(status = AuthDeviceEntity.STATUS_PENDING, tokenHash = "", authorizedAt = null)
            return 1
        }
        override suspend fun upsertAll(list: List<AuthDeviceEntity>) { list.forEach { row = it } }
        override suspend fun clearAll() { row = null }
        override suspend fun setLabel(deviceId: String, label: String?) { row = row?.copy(label = label) }
        override suspend fun setResponseMode(deviceId: String, mode: String) { row = row?.copy(responseMode = mode) }
        override suspend fun delete(deviceId: String) { if (row?.deviceId == deviceId) row = null }
    }
}
