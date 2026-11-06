package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.db.ApiKeyDao
import cn.luoluna.sweven.ai.data.db.ApiKeyEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.ref
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

    val displayKey: String get() = "${provider.alias}/${model?.ref ?: ""}"
}

/**
 * flux-auto 聚合路由：会话/设置中以哨兵 providerId 标识。
 * 候选固定为全部启用模型（厂商顺序×模型顺序），按请求模态自动择优选发。
 */
object SmartAi {
    const val PROVIDER_ID = -1L
    const val MODEL_NAME = "flux-auto"
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
    /** 厂商当前使用的 Key：activeKeyId 优先，否则取第一个（旧数据兜底 / 未绑定模型的场景） */
    suspend fun activeKey(provider: ProviderEntity): ApiKeyEntity? =
        provider.activeKeyId?.let { apiKeyDao.byId(it) }
            ?: apiKeyDao.forProvider(provider.id).firstOrNull()

    /** 模型实际使用的 Key：优先模型绑定的 apiKeyId；旧数据（null 或已删 key）回退厂商默认 Key */
    suspend fun keyFor(model: ModelEntity, provider: ProviderEntity? = null): ApiKeyEntity? =
        model.apiKeyId?.let { apiKeyDao.byId(it) }
            ?: (provider ?: providerDao.byId(model.providerId))?.let { activeKey(it) }

    suspend fun resolveById(providerId: Long?, ref: String?): ModelRoute? {
        if (providerId == null || ref.isNullOrBlank()) return null
        val provider = providerDao.byId(providerId) ?: return null
        // 停用厂商按不存在处理：智能路由与局域网网关统一跳过
        if (!provider.enabled) return null
        val model = modelDao.find(providerId, ref)
        val key = model?.let { keyFor(it, provider) } ?: activeKey(provider)
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
        val model = modelDao.find(provider.id, modelName)
        val key = model?.let { keyFor(it, provider) } ?: activeKey(provider)
        return ModelRoute(provider, key, model)
    }

    /**
     * flux-auto 的候选合集：启用厂商按配置顺序 × 其下启用的 chat/图像输出模型按配置顺序。
     * 这是「直接路由所有大模型」的枚举口径——本机聊天与局域网网关共用同一份来源，
     * 与任何开关无关；每次请求再由 selectCandidates 按请求模态自动挑出能用的子集。
     */
    suspend fun allModelsChain(): List<Pair<ProviderEntity, ModelEntity>> =
        providerDao.all().filter { it.enabled }.flatMap { provider ->
            modelDao.forProvider(provider.id)
                .filter { it.enabled && (it.type == ModelType.CHAT || it.outputImage) }
                .map { provider to it }
        }
}
