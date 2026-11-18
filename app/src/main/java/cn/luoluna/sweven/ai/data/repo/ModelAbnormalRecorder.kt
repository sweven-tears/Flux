package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.db.AbnormalDecision
import cn.luoluna.sweven.ai.data.db.AppDatabase
import cn.luoluna.sweven.ai.data.db.ModelAbnormalEntity
import cn.luoluna.sweven.ai.net.SensitiveDataRedactor

/**
 * 模型异常记录的唯一写入点（App 内聊天与局域网网关共用）：
 * 只记「真实上游失败」——连接失败 / 超时 / HTTP 错误 / 流中断；
 * 网关自判的校验失败（messages 缺失、未授权、分组为空等）不记，避免把网关自身的问题记到厂商头上。
 *
 * 短窗口内同 (厂商, 模型, 状态码, 错误) 合并计数，单次故障不刷屏；写入失败不影响转发结果。
 */
class ModelAbnormalRecorder(private val db: AppDatabase) {

    private companion object {
        /** 同键合并窗口：窗口内重复出现只累加计数 */
        const val MERGE_WINDOW_MS = 60_000L

        /** 保留条数，写入时自动修剪 */
        const val KEEP = 1000
    }

    suspend fun record(
        providerId: Long,
        providerAlias: String,
        modelName: String,
        via: String,
        httpCode: Int?,
        error: String?,
    ) {
        if (providerId <= 0 || modelName.isBlank()) return
        runCatching {
            val dao = db.modelAbnormalDao()
            val now = System.currentTimeMillis()
            val message = error?.let { SensitiveDataRedactor.redactText(it, maxLength = 300) }
            val target = dao.findMergeTarget(
                providerId = providerId,
                modelName = modelName,
                httpCode = httpCode,
                error = message,
                pending = AbnormalDecision.PENDING,
                since = now - MERGE_WINDOW_MS,
            )
            if (target != null) {
                dao.bump(target.id, now, message)
            } else {
                dao.insert(
                    ModelAbnormalEntity(
                        at = now,
                        providerId = providerId,
                        providerAlias = providerAlias,
                        modelName = modelName,
                        via = via,
                        httpCode = httpCode,
                        error = message,
                    )
                )
            }
            dao.prune(KEEP)
        }
    }
}
