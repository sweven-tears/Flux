package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.db.ApiKeyDao
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.net.AdvancedParams

/** 一次对话请求的路由目标 */
data class ModelRoute(
    val provider: ProviderEntity,
    val apiKey: ApiKeyEntity?,
    val model: ModelEntity?,
) {
    val advancedParams: AdvancedParams?
        get() = model?.let {
            if (it.temperature == null && it.topP == null && it.maxTokens == null &&
                it.presencePenalty == null && it.frequencyPenalty == null
            ) null
            else AdvancedParams(
                temperature = it.temperature,
                topP = it.topP,
                maxTokens = it.maxTokens,
                presencePenalty = it.presencePenalty,
                frequencyPenalty = it.frequencyPenalty,
            )
        }

    val displayKey: String get() = "${provider.alias}/${model?.name ?: ""}"
}

/** 智能路由聚合模型 smartAi：会话/设置中以哨兵 providerId 标识 */
object SmartAi {
    const val PROVIDER_ID = -1L
    const val MODEL_NAME = "smartAi"
    val pair: Pair<Long, String> get() = PROVIDER_ID to MODEL_NAME
}

/**
 * 路由分组目标：会话/设置中以哨兵 providerId 标识，modelName 存分组的 groupKey；
 * 对外暴露的模型名为 flux-<groupKey>（与局域网 /v1/models 一致）
 */
object RouteGroupTarget {
    const val PROVIDER_ID = -2L
    const val NAME_PREFIX = "flux-"
    fun routeName(groupKey: String) = NAME_PREFIX + groupKey
    fun groupKeyOf(routeName: String): String? =
        routeName.removePrefix(NAME_PREFIX).takeIf { it != routeName && it.isNotBlank() }
}

class ModelRouter(
    private val providerDao: ProviderDao,
    private val apiKeyDao: ApiKeyDao,
    private val modelDao: ModelDao,
) {
    /** 厂商当前使用的 Key：activeKeyId 优先，否则取第一个 */
    suspend fun activeKey(provider: ProviderEntity): ApiKeyEntity? =
        provider.activeKeyId?.let { apiKeyDao.byId(it) }
            ?: apiKeyDao.forProvider(provider.id).firstOrNull()

    suspend fun resolveById(providerId: Long?, modelName: String?): ModelRoute? {
        if (providerId == null || modelName.isNullOrBlank()) return null
        val provider = providerDao.byId(providerId) ?: return null
        // 停用厂商按不存在处理：智能路由与局域网网关统一跳过
        if (!provider.enabled) return null
        val key = activeKey(provider)
        val model = modelDao.find(providerId, modelName)
        return ModelRoute(provider, key, model)
    }

    /** 局域网路由：聚合模型 id = "厂商别名/模型名"，按第一个 / 拆分 */
    suspend fun resolveByAggregateId(aggregateId: String): ModelRoute? {
        val slash = aggregateId.indexOf('/')
        if (slash <= 0) return null
        val alias = aggregateId.substring(0, slash).trim()
        val modelName = aggregateId.substring(slash + 1).trim()
        if (alias.isEmpty() || modelName.isEmpty()) return null
        val provider = providerDao.byAlias(alias) ?: return null
        if (!provider.enabled) return null
        val key = activeKey(provider)
        val model = modelDao.find(provider.id, modelName)
        return ModelRoute(provider, key, model)
    }
}
