package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「脚本开发工具」的草稿合并与校验：全部纯函数（JVM 单测覆盖）。
 *
 * 模型给的是**局部字段**（create 时给全，update 时只给要改的），这里负责：
 * 1. 把 [raw] 与既有实体合并成一条完整 [CustomToolEntity]（未提到的字段保持原值）
 * 2. 用与编辑页/装载器同一套规则（[CustomToolSpec] / [JsToolConfig] / [HttpToolConfig]）校验
 *
 * 这样「模型建出来的工具」与「手写编辑页建出来的工具」入库形态完全一致，
 * 后续装载、执行、备份都不需要任何特判。
 */
object ScriptToolDraftSpec {

    /** 脚本长度上限：够写复杂工具，又不至于把一次工具调用塞爆 */
    const val MAX_CODE_CHARS = 20_000

    /** 参数 Schema 文本上限 */
    const val MAX_PARAMETERS_CHARS = 8_000

    const val MAX_DESCRIPTION_CHARS = 2_000

    /** 取字符串字段：JSON 原始值取其字面量，对象/数组取其 JSON 文本 */
    fun text(raw: JsonObject, key: String): String? = when (val element = raw[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    /** 取布尔字段：接受 true/false 与 "true"/"false" 文本 */
    fun bool(raw: JsonObject, key: String): Boolean? = when (val element = raw[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.content.toBooleanStrictOrNull()
        else -> null
    }

    /** 取整数字段：接受数字与数字文本 */
    fun long(raw: JsonObject, key: String): Long? = when (val element = raw[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.content.trim().toLongOrNull()
        else -> null
    }

    /** 字段是否被显式提供（哪怕是 null：表示「清空」） */
    fun provided(raw: JsonObject, key: String): Boolean = raw.containsKey(key)

    /**
     * 草稿 → 实体。
     * - [existing] 为 null 表示新建（此时 name / kind 必需）
     * - 抛 [IllegalArgumentException]：原因直接回给模型（文案是中文、可操作）
     */
    fun merge(existing: CustomToolEntity?, raw: JsonObject): CustomToolEntity {
        val kind = (text(raw, "kind")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: existing?.kind
            ?: CustomToolKind.JS)
        if (!CustomToolKind.isKnown(kind)) {
            throw IllegalArgumentException("kind 只能是 js 或 http（当前：$kind）")
        }

        val name = text(raw, "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.name
            ?: throw IllegalArgumentException("create 必须提供 name（暴露给模型的函数名，字母/数字/下划线）")
        // 换 kind 又不给新配置时保留旧配置没有意义（js 的 code ≠ http 的 url），直接报错更清楚
        if (existing != null && existing.kind != kind &&
            !provided(raw, "config") && !provided(raw, "code")
        ) {
            throw IllegalArgumentException("把 kind 从 ${existing.kind} 改成 $kind 时必须同时提供 config")
        }

        val description = when {
            provided(raw, "description") -> text(raw, "description").orEmpty().trim()
            else -> existing?.description.orEmpty()
        }
        val displayName = text(raw, "display_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.displayName?.takeIf { it.isNotBlank() }
            ?: name

        val parametersJson = if (provided(raw, "parameters")) {
            text(raw, "parameters")?.trim()?.takeIf { it.isNotEmpty() && it != "{}" }
        } else {
            existing?.parametersJson?.takeIf { it.isNotBlank() }
        }

        val configJson = configOf(existing, raw, kind)

        return CustomToolEntity(
            id = existing?.id ?: 0,
            name = name,
            displayName = displayName,
            description = description,
            parametersJson = parametersJson,
            kind = kind,
            configJson = configJson,
            needsApproval = bool(raw, "needs_approval") ?: existing?.needsApproval ?: true,
            enabled = bool(raw, "enabled") ?: existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 本次操作提供的配置；没提供就沿用既有配置 */
    private fun configOf(existing: CustomToolEntity?, raw: JsonObject, kind: String): String {
        if (provided(raw, "config")) {
            val element: JsonElement? = raw["config"]
            if (element == null || element is JsonNull) {
                throw IllegalArgumentException("config 不能为 null（要清空配置请重新提供一份完整配置）")
            }
            val text = (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: element.toString()
            if (text.isBlank() || text == "{}") throw IllegalArgumentException("config 不能为空对象")
            return if (kind == CustomToolKind.JS && element !is JsonPrimitive) {
                // {code: "..."} 形态与 {config:{code}} 等价；保持原样即可被 JsToolConfig 解析
                element.toString()
            } else {
                text
            }
        }
        if (provided(raw, "code")) {
            if (kind != CustomToolKind.JS) {
                throw IllegalArgumentException("只有 js 工具用 code 字段；http 工具请用 config{method,url,...}")
            }
            val code = text(raw, "code").orEmpty()
            if (code.isBlank()) throw IllegalArgumentException("code 不能为空")
            return JsToolConfig(code).toJson()
        }
        return existing?.configJson ?: when (kind) {
            CustomToolKind.HTTP -> throw IllegalArgumentException(
                "create http 工具必须提供 config{method,url}（headersJson/bodyTemplate/timeoutMs/maxChars/resultPath 可选）"
            )

            else -> throw IllegalArgumentException("create js 工具必须提供 code（参数从 args 取，用 return 返回）")
        }
    }

    /**
     * 静态校验（不落库）：返回错误列表，空 = 通过。
     *
     * [taken] 为已被占用的函数名（内置 + 其它自定义工具）；[selfName] 为编辑场景下自身原名（不算占用）。
     */
    fun errors(entity: CustomToolEntity, taken: Collection<String>, selfName: String?): List<String> {
        val errors = mutableListOf<String>()
        CustomToolSpec.nameError(entity.name, taken.filterNot { it == selfName })?.let { errors += it }
        CustomToolSpec.parametersError(entity.parametersJson)?.let { errors += it }
        if (entity.parametersJson != null && entity.parametersJson.length > MAX_PARAMETERS_CHARS) {
            errors += "parameters 过长（上限 $MAX_PARAMETERS_CHARS 字符）"
        }
        if (entity.description.length > MAX_DESCRIPTION_CHARS) {
            errors += "description 过长（上限 $MAX_DESCRIPTION_CHARS 字符）"
        }
        if (entity.displayName.length > MAX_DESCRIPTION_CHARS) {
            errors += "display_name 过长（上限 $MAX_DESCRIPTION_CHARS 字符）"
        }
        when (entity.kind) {
            CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(entity.configJson) }
                .onFailure { errors += it.message ?: "HTTP 配置不合法" }

            else -> runCatching { JsToolConfig.parse(entity.configJson) }
                .onFailure { errors += it.message ?: "JS 配置不合法" }
                .onSuccess { cfg ->
                    if (cfg.code.length > MAX_CODE_CHARS) {
                        errors += "code 过长（${cfg.code.length} 字符，上限 $MAX_CODE_CHARS）"
                    }
                }
        }
        return errors
    }

    /** 实体 → 给模型看的 JSON（[includeConfig] 时带完整配置，list 场景可省） */
    fun toJson(entity: CustomToolEntity, includeConfig: Boolean = true): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(entity.id))
            put("name", JsonPrimitive(entity.name))
            put("display_name", JsonPrimitive(entity.displayName))
            if (entity.description.isNotBlank()) put("description", JsonPrimitive(entity.description))
            put("kind", JsonPrimitive(entity.kind))
            put("enabled", JsonPrimitive(entity.enabled))
            put("needs_approval", JsonPrimitive(entity.needsApproval))
            entity.parametersJson?.takeIf { it.isNotBlank() }?.let { params ->
                val parsed = runCatching { ChatJson.json.parseToJsonElement(params) }.getOrNull()
                put("parameters", parsed ?: JsonPrimitive(params))
            }
            if (includeConfig) {
                val parsed = runCatching { ChatJson.json.parseToJsonElement(entity.configJson) }.getOrNull()
                put("config", parsed ?: JsonPrimitive(entity.configJson))
            }
        }
}
