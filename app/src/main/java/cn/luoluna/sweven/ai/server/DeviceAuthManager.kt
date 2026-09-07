package cn.luoluna.sweven.ai.server

import android.content.Context
import cn.luoluna.sweven.ai.data.db.AuthDeviceDao
import cn.luoluna.sweven.ai.data.db.AuthDeviceEntity
import cn.luoluna.sweven.ai.data.db.AppDatabase

sealed interface AuthDecision {
    /** 放行并携带设备实体（含响应模式等设备级配置） */
    data class Allowed(val device: AuthDeviceEntity) : AuthDecision
    data class NeedsApproval(val isNew: Boolean) : AuthDecision
    data object Blocked : AuthDecision
}

/**
 * 局域网设备鉴权：
 * - 未知设备 → 记录为待授权申请（弹通知），请求返回 401
 * - 已批准设备 → 放行
 * - 被拒绝/拉黑设备 → 403
 */
class DeviceAuthManager(
    private val context: Context,
    private val dao: AuthDeviceDao,
) {

    suspend fun check(deviceId: String, ip: String): AuthDecision {
        val now = System.currentTimeMillis()
        val existing = dao.byId(deviceId)
        if (existing == null) {
            dao.upsert(
                AuthDeviceEntity(
                    deviceId = deviceId,
                    lastIp = ip,
                    status = AuthDeviceEntity.STATUS_PENDING,
                    requestCount = 1,
                    firstSeenAt = now,
                    lastSeenAt = now,
                )
            )
            Notifications.notifyPendingDevice(context, deviceId, ip)
            return AuthDecision.NeedsApproval(isNew = true)
        }
        when (existing.status) {
            AuthDeviceEntity.STATUS_AUTHORIZED -> {
                val updated = existing.copy(requestCount = existing.requestCount + 1, lastSeenAt = now, lastIp = ip)
                dao.upsert(updated)
                return AuthDecision.Allowed(updated)
            }
            AuthDeviceEntity.STATUS_BLOCKED -> {
                dao.upsert(existing.copy(requestCount = existing.requestCount + 1, lastSeenAt = now, lastIp = ip))
                return AuthDecision.Blocked
            }
        }
        dao.upsert(existing.copy(requestCount = existing.requestCount + 1, lastSeenAt = now, lastIp = ip))
        return AuthDecision.NeedsApproval(isNew = false)
    }

    companion object {
        const val HEADER_DEVICE_ID = "X-Device-Id"

        fun fromDatabase(context: Context, db: AppDatabase): DeviceAuthManager =
            DeviceAuthManager(context.applicationContext, db.authDeviceDao())
    }
}
