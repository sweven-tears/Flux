package cn.luoluna.sweven.ai.server

import android.content.Context
import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.AppDatabase

sealed interface AuthDecision {
    /** 放行并携带设备实体（含响应模式等设备级配置） */
    data class Allowed(val device: AuthDeviceEntity) : AuthDecision
    data class NeedsApproval(val isNew: Boolean) : AuthDecision
    data object InvalidToken : AuthDecision
    data object RateLimited : AuthDecision
    data object Blocked : AuthDecision
}

/**
 * 局域网设备鉴权：设备批准时下发一次性显示的随机 256-bit bearer token；DB 仅保存 SHA-256。
 * 未知设备申请需批准；旧版空 hash 授权设备首次访问转待批准重新配对。
 */
class DeviceAuthManager(
    private val dao: AuthDeviceDao,
    private val failureLimiter: AuthFailureLimiter = AuthFailureLimiter(),
    private val pendingNotifier: (deviceId: String, ip: String) -> Unit = { _, _ -> },
) {
    /** Android wiring constructor; the primary constructor stays usable from plain JVM tests. */
    constructor(context: Context, dao: AuthDeviceDao) : this(
        dao = dao,
        pendingNotifier = { deviceId, ip -> Notifications.notifyPendingDevice(context, deviceId, ip) },
    )

    suspend fun check(deviceId: String, token: String?, ip: String): AuthDecision {
        val now = System.currentTimeMillis()
        if (failureLimiter.isLimited(ip, now)) return AuthDecision.RateLimited
        repeat(4) {
            val existing = dao.byId(deviceId)
            if (existing == null) {
                val createdAt = System.currentTimeMillis()
                val inserted = dao.insertIfAbsent(
                    AuthDeviceEntity(
                        deviceId = deviceId,
                        lastIp = ip,
                        status = AuthDeviceEntity.STATUS_PENDING,
                        requestCount = 1,
                        firstSeenAt = createdAt,
                        lastSeenAt = createdAt,
                    )
                )
                if (inserted != -1L) {
                    pendingNotifier(deviceId, ip)
                    return failed(ip, AuthDecision.NeedsApproval(isNew = true))
                }
                return@repeat
            }
            when (existing.status) {
                AuthDeviceEntity.STATUS_AUTHORIZED -> {
                    if (existing.tokenHash.isBlank()) {
                        // Existing authorized records predate token auth. Force explicit re-approval.
                        val changed = dao.requireLegacyRepair(deviceId)
                        if (changed > 0) {
                            dao.bumpIfStatus(deviceId, AuthDeviceEntity.STATUS_PENDING, ip, System.currentTimeMillis())
                            pendingNotifier(deviceId, ip)
                            return failed(ip, AuthDecision.NeedsApproval(isNew = false))
                        }
                        return@repeat
                    }
                    if (!DeviceAuthSecurity.matches(existing.tokenHash, token)) {
                        return failed(ip, AuthDecision.InvalidToken)
                    }
                    val updatedAt = System.currentTimeMillis()
                    val changed = dao.bumpAuthorizedWithToken(deviceId, existing.tokenHash, ip, updatedAt)
                    if (changed > 0) {
                        return AuthDecision.Allowed(
                            existing.copy(
                                requestCount = existing.requestCount + 1,
                                lastSeenAt = updatedAt,
                                lastIp = ip,
                            )
                        )
                    }
                    return@repeat
                }
                AuthDeviceEntity.STATUS_BLOCKED -> {
                    dao.bumpIfStatus(deviceId, AuthDeviceEntity.STATUS_BLOCKED, ip, System.currentTimeMillis())
                    return AuthDecision.Blocked
                }
                else -> {
                    dao.bumpIfStatus(deviceId, AuthDeviceEntity.STATUS_PENDING, ip, System.currentTimeMillis())
                    return failed(ip, AuthDecision.NeedsApproval(isNew = false))
                }
            }
        }
        // Re-read once after a concurrent approval/revocation changed the row.
        val current = dao.byId(deviceId)
        if (current?.status == AuthDeviceEntity.STATUS_AUTHORIZED && current.tokenHash.isNotBlank() &&
            DeviceAuthSecurity.matches(current.tokenHash, token)
        ) {
            val updatedAt = System.currentTimeMillis()
            if (dao.bumpAuthorizedWithToken(deviceId, current.tokenHash, ip, updatedAt) > 0) {
                return AuthDecision.Allowed(current.copy(requestCount = current.requestCount + 1, lastSeenAt = updatedAt, lastIp = ip))
            }
        }
        return failed(ip, AuthDecision.InvalidToken)
    }

    private fun failed(ip: String, decision: AuthDecision): AuthDecision =
        if (failureLimiter.recordFailure(ip, System.currentTimeMillis())) AuthDecision.RateLimited else decision

    /** Generate a token and conditionally authorize only the row still awaiting approval. */
    suspend fun approve(deviceId: String): String? {
        val token = DeviceAuthSecurity.newToken()
        val updated = dao.approvePending(deviceId, DeviceAuthSecurity.sha256Hex(token), System.currentTimeMillis())
        return token.takeIf { updated > 0 }
    }

    companion object {
        const val HEADER_DEVICE_ID = "X-Device-Id"
        const val HEADER_DEVICE_TOKEN = "X-Device-Token"

        fun fromDatabase(context: Context, db: AppDatabase): DeviceAuthManager =
            DeviceAuthManager(context.applicationContext, db.authDeviceDao())
    }
}
