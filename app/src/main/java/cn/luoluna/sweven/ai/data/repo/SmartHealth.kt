package cn.luoluna.sweven.ai.data.repo

import java.util.concurrent.ConcurrentHashMap

/**
 * 智能路由候选健康度（进程级单例，不持久化，重启即清零从链首开始）：
 * - 连续失败 + 冷却：真实失败（连接错误 / HTTP 错误 / 聊天档超时 / 流中断）累积连续失败，
 *   达阈值进入冷却；三桶排序时冷却候选垫底兜底，到期回中间桶半开试错
 * - 首字延迟 EWMA：极速模式据此把实测不达标的候选排除出极速轮，学费每候选最多交一次；
 *   「慢」不等于「病」，极速轮超时只记慢、不累积失败
 * App 内聊天与局域网网关共享同一份状态：任一链路发现候选不可用，另一链路同样跳过。
 */
object SmartHealth {

    private class State {
        var consecutiveFails: Int = 0
        var coolUntil: Long = 0L
        var firstTokenEwmaMs: Long? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun key(providerId: Long, modelName: String) = "$providerId:$modelName"

    private fun stateOf(k: String): State = states.getOrPut(k) { State() }

    /** 候选成功出字：清零失败与冷却，滚动更新首字延迟估计（EWMA 7:3） */
    fun recordSuccess(providerId: Long, modelName: String, firstTokenMs: Long) {
        val s = stateOf(key(providerId, modelName))
        synchronized(s) {
            s.consecutiveFails = 0
            s.coolUntil = 0L
            s.firstTokenEwmaMs = s.firstTokenEwmaMs?.let { it * 7 / 10 + firstTokenMs * 3 / 10 } ?: firstTokenMs
        }
    }

    /**
     * 极速轮超时（已建连、限极短时间内无输出）：只抬高速率估计的下界，
     * 不累积连续失败——慢模型会在放宽轮成功并带回真实测速
     */
    fun recordSlow(providerId: Long, modelName: String, fastMs: Long) {
        val s = stateOf(key(providerId, modelName))
        synchronized(s) {
            s.firstTokenEwmaMs = maxOf(s.firstTokenEwmaMs ?: fastMs, fastMs)
        }
    }

    /** 一次真实失败：连续失败 +1，达到阈值进入冷却期 */
    fun recordFailure(providerId: Long, modelName: String, now: Long, threshold: Int, cooldownMs: Long) {
        val s = stateOf(key(providerId, modelName))
        synchronized(s) {
            s.consecutiveFails += 1
            if (s.consecutiveFails >= threshold.coerceAtLeast(1)) {
                s.coolUntil = now + cooldownMs
            }
        }
    }

    /** 健康桶：0=健康（主力位） 1=有失败记录未冷却（降权备胎） 2=冷却中（垫底兜底） */
    fun bucket(providerId: Long, modelName: String, now: Long): Int {
        val s = states[key(providerId, modelName)] ?: return 0
        return synchronized(s) {
            when {
                s.coolUntil > now -> 2
                s.consecutiveFails > 0 -> 1
                else -> 0
            }
        }
    }

    /** 极速轮资格：速度未知（可交一次学费试速）或实测首字延迟达标 */
    fun fastCapable(providerId: Long, modelName: String, fastMs: Long): Boolean {
        val s = states[key(providerId, modelName)] ?: return true
        return synchronized(s) {
            val ewma = s.firstTokenEwmaMs
            ewma == null || ewma <= fastMs
        }
    }

    /** 配置页徽标展示用快照 */
    data class Info(
        val consecutiveFails: Int,
        val cooling: Boolean,
        val firstTokenEwmaMs: Long?,
    )

    fun info(providerId: Long, modelName: String, now: Long = System.currentTimeMillis()): Info {
        val s = states[key(providerId, modelName)] ?: return Info(0, false, null)
        return synchronized(s) { Info(s.consecutiveFails, s.coolUntil > now, s.firstTokenEwmaMs) }
    }
}
