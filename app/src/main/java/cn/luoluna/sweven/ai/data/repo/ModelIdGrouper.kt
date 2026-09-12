package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.db.ModelEntity

/**
 * 模型 id 归组：同一模型在不同厂商/不同版本号下 id 存在差异
 * （厂商命名空间前缀 openai/…、尾部版本或日期后缀 -20250601 / -2025-06-01 / @v2 等），
 * 归组时以规范化后的「基名」为键，把同族模型聚到一组（flux-<基名>）。
 *
 * 匹配优先级：groupKey 更长的组更具体，搜索建议与查找一律按键长降序，
 * 保证 deepseek-v4-flash 先匹配到 v4-flash 族，而不是落入 deepseek-v4 族。
 */
object ModelIdGrouper {

    /** 规范化：小写、去 models/ 前缀、去厂商命名空间（最后一个 / 之前），去首尾空白 */
    fun normalize(id: String): String =
        id.trim().lowercase()
            .removePrefix("models/")
            .substringAfterLast('/')
            .trim()

    /**
     * 尾部版本/日期后缀：-20250601 / -2506 / -2025-06-01 / -2025.06.01 / @20250601 / :free / :latest。
     * 只认数字（或 free/latest）结尾的尾巴；字母变体（-thinking、-exp、-preview）是不同模型，不剥。
     */
    private val TAIL_VERSION = Regex(
        "([\\-._@]\\d{4,8}|[\\-._@]\\d{4}[-._]\\d{1,2}([-._]\\d{1,2})?|:(free|latest))+$"
    )

    /** 归组基名：规范化后再循环剥掉尾部版本/日期后缀 */
    fun baseKey(id: String): String {
        var k = normalize(id)
        while (true) {
            val m = TAIL_VERSION.find(k) ?: break
            if (m.range.first <= 0) break
            k = k.substring(0, m.range.first)
        }
        return k
    }

    /** 模型是否属于 [groupKey] 族：基名相等即同族 */
    fun matches(groupKey: String, modelId: String): Boolean = baseKey(modelId) == groupKey

    /**
     * 在一次匹配搜索中汇总同族模型：[models] 中基名等于 [groupKey] 的全部条目。
     * 结果按厂商 id + 模型名排序，保证组内顺序稳定。
     */
    fun membersOf(groupKey: String, models: List<ModelEntity>): List<ModelEntity> =
        models.filter { matches(groupKey, it.name) }
            .sortedWith(compareBy({ it.providerId }, { it.name.lowercase() }))

    /** 搜索归一化：小写并去掉分隔符，模糊匹配用（v4-flash ≈ v4flash） */
    private fun fuzzy(s: String): String = s.lowercase().replace(Regex("[-_. /]"), "")

    /**
     * 搜索建议：在 [models] 中按 [query] 模糊匹配（分隔符不敏感的包含即中），
     * 汇总为候选归组键（基名去重），**按基名长度降序**——更具体的族排前面。
     * [excludeKeys] 排除已存在的分组。
     */
    fun suggestKeys(
        query: String,
        models: List<ModelEntity>,
        excludeKeys: Set<String> = emptySet(),
    ): List<String> {
        val q = fuzzy(query)
        if (q.isEmpty()) return emptyList()
        return models.map { baseKey(it.name) }
            .filter { it.isNotEmpty() && fuzzy(it).contains(q) && it !in excludeKeys }
            .distinct()
            .sortedByDescending { it.length }
    }
}
