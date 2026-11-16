package cn.luoluna.sweven.ai.tools

import android.content.Context
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.CustomToolDao
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.tools.custom.HttpToolConfig
import cn.luoluna.sweven.ai.tools.custom.HttpToolExecutor
import cn.luoluna.sweven.ai.tools.custom.JsToolConfig
import cn.luoluna.sweven.ai.tools.custom.JsToolExecutor
import cn.luoluna.sweven.ai.tools.custom.ScriptToolDraftSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具管理类（原生层）：脚本开发工具 `script_tool_dev` 的全部动作实现。
 *
 * 动作与确认策略：
 * | 动作 | 行为 | 是否需要用户确认 |
 * |------|------|------------------|
 * | create | 新建自定义工具（写 `custom_tools`） | 是（[AppSettings] 关掉「写操作需确认」后免确认） |
 * | update | 局部更新（只改提供的字段） | 是 |
 * | delete | 删除工具 | 是 |
 * | list / get / validate | 只读 | 否，直接执行 |
 * | test | 在 QuickJS 沙箱（或 HTTP 执行器）里真跑一次 | 否，沙箱内执行 |
 * | enable / disable | 注册/注销进 [ToolRegistry] | 否，默认允许 |
 *
 * 所有动作都返回 JSON 文本：成功 `{"ok":true,...}`，失败走 [toolErrorJson] 的 `{"error":"..."}`
 * 约定（与内置工具一致，错误也作为工具结果回给模型，让它自己纠正）。
 *
 * 写操作失败一律不落库；校验规则与编辑页共用 [ScriptToolDraftSpec]，所以模型建出来的工具
 * 与手写的完全同构，[cn.luoluna.sweven.ai.tools.custom.CustomToolLoader] 会自动注册它。
 */
class ToolManager(context: Context) {

    private val dao: CustomToolDao = (context.applicationContext as App).database.customToolDao()

    private val settings = (context.applicationContext as App).settings

    /** 统一入口：按 [action] 分发；未知动作返回错误（不抛异常，避免整轮中断） */
    suspend fun handle(action: String, raw: JsonObject): String = when (action.trim().lowercase()) {
        ACTION_CREATE -> create(raw)
        ACTION_UPDATE -> update(raw)
        ACTION_DELETE -> delete(raw)
        ACTION_LIST -> list(raw)
        ACTION_GET -> get(raw)
        ACTION_VALIDATE -> validate(raw)
        ACTION_TEST -> test(raw)
        ACTION_ENABLE -> setEnabled(raw, enabled = true)
        ACTION_DISABLE -> setEnabled(raw, enabled = false)
        else -> toolErrorJson("未知动作：$action（可选 ${ACTIONS.joinToString(" / ")}）")
    }

    // ---- 写操作（默认需确认） ----

    private suspend fun create(raw: JsonObject): String {
        // 先在内存里算好草稿并校验，确认通过才询问用户，避免用户点了允许才发现字段不合法
        val draft = runCatching { ScriptToolDraftSpec.merge(null, raw) }
            .getOrElse { return toolErrorJson(it.message ?: "草稿解析失败") }
        val errors = ScriptToolDraftSpec.errors(draft, takenNames(null), selfName = null)
        if (errors.isNotEmpty()) return validationError(errors)

        confirm(
            title = "新建工具「${draft.displayName}」",
            detail = buildString {
                append("函数名：")
                append(draft.name)
                append("\n执行方式：")
                append(kindLabel(draft.kind))
                if (draft.description.isNotBlank()) append("\n说明：${draft.description}")
                previewOf(draft)?.let {
                    append("\n\n")
                    append(it)
                }
            },
        ).let { if (!it) return denied("create") }

        // 确认期间可能有别的写入占用了这个名字，落库前再查一次
        if (dao.countNameExcluding(draft.name, 0) > 0) {
            return toolErrorJson("函数名「${draft.name}」已被占用，请换一个")
        }
        val id = dao.insert(draft)
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_CREATE)
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(draft.name))
            put("enabled", JsonPrimitive(draft.enabled))
            put("message", JsonPrimitive(createdHint(draft.enabled)))
        }.toString()
    }

    private suspend fun update(raw: JsonObject): String {
        val existing = resolve(raw) ?: return notFound(raw)
        val draft = runCatching { ScriptToolDraftSpec.merge(existing, raw) }
            .getOrElse { return toolErrorJson(it.message ?: "草稿解析失败") }
        val errors = ScriptToolDraftSpec.errors(draft, takenNames(existing), selfName = existing.name)
        if (errors.isNotEmpty()) return validationError(errors)

        val changes = changedFields(existing, draft)
        if (changes.isEmpty()) return toolErrorJson("没有任何字段变化（update 需要至少一个要改的字段）")

        confirm(
            title = "修改工具「${draft.displayName}」",
            detail = buildString {
                append("函数名：")
                append(draft.name)
                append("\n变更：")
                append(changes.joinToString("、"))
                previewOf(draft)?.let {
                    append("\n\n")
                    append(it)
                }
            },
        ).let { if (!it) return denied("update") }

        if (draft.name != existing.name && dao.countNameExcluding(draft.name, existing.id) > 0) {
            return toolErrorJson("函数名「${draft.name}」已被占用，请换一个")
        }
        dao.update(draft)
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_UPDATE)
            put("id", JsonPrimitive(draft.id))
            put("name", JsonPrimitive(draft.name))
            put("changed", buildJsonArray { changes.forEach { add(JsonPrimitive(it)) } })
            put("enabled", JsonPrimitive(draft.enabled))
            put("message", JsonPrimitive("已更新，改动即时生效（无需重启）"))
        }.toString()
    }

    private suspend fun delete(raw: JsonObject): String {
        val existing = resolve(raw) ?: return notFound(raw)
        confirm(
            title = "删除工具「${existing.displayName}」",
            detail = "函数名：${existing.name}\n删除后它会从工具列表移除；历史会话里的调用记录仍保留。",
        ).let { if (!it) return denied("delete") }

        dao.deleteById(existing.id)
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_DELETE)
            put("id", JsonPrimitive(existing.id))
            put("name", JsonPrimitive(existing.name))
            put("message", JsonPrimitive("已删除「${existing.displayName}」"))
        }.toString()
    }

    // ---- 只读（直接执行） ----

    private suspend fun list(raw: JsonObject): String {
        val filter = ScriptToolDraftSpec.text(raw, "filter")?.trim()?.lowercase()
        val keyword = ScriptToolDraftSpec.text(raw, "query")?.trim()?.lowercase()
        val includeDisabled = ScriptToolDraftSpec.bool(raw, "include_disabled") ?: true
        val items = dao.all()
            .asSequence()
            .filter { includeDisabled || it.enabled }
            .filter { entity ->
                when (filter) {
                    null, "", "all" -> true
                    "enabled" -> entity.enabled
                    "disabled" -> !entity.enabled
                    "js" -> entity.kind == CustomToolKind.JS
                    "http" -> entity.kind == CustomToolKind.HTTP
                    else -> true
                }
            }
            .filter { entity ->
                keyword.isNullOrEmpty() ||
                    entity.name.lowercase().contains(keyword) ||
                    entity.displayName.lowercase().contains(keyword) ||
                    entity.description.lowercase().contains(keyword)
            }
            .toList()

        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_LIST)
            put("count", JsonPrimitive(items.size))
            put("items", buildJsonArray {
                items.forEach { entity -> add(ScriptToolDraftSpec.toJson(entity, includeConfig = false)) }
            })
            put("reserved_names", buildJsonArray {
                ToolRegistry.builtIns().forEach { add(JsonPrimitive(it.name)) }
            })
        }.toString()
    }

    private suspend fun get(raw: JsonObject): String {
        val existing = resolve(raw) ?: return notFound(raw)
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_GET)
            put("tool", ScriptToolDraftSpec.toJson(existing, includeConfig = true))
            put("registered", JsonPrimitive(ToolRegistry.byName(existing.name) != null))
        }.toString()
    }

    /**
     * 静态校验：走与 create/update 完全相同的规则，但不落库。
     * 允许只给 code 不给 name（用占位名跳过函数名判重），方便先调通脚本再建工具。
     */
    private suspend fun validate(raw: JsonObject): String {
        val existing = resolveOrNull(raw)
        val result = runCatching {
            val draft = ScriptToolDraftSpec.merge(existing, withPlaceholderName(raw))
            // 没提供 name 且不是编辑场景：跳过函数名检查（占位名可能与既有工具重名）
            val checkName = existing != null || !ScriptToolDraftSpec.text(raw, "name").isNullOrBlank()
            val errors = ScriptToolDraftSpec.errors(
                draft,
                takenNames(existing),
                selfName = existing?.name,
            ).filterNot { !checkName && it.startsWith("函数名") }
            draft to errors
        }
        val (draft, errors) = result.getOrElse {
            return buildJsonObject {
                put("ok", false)
                put("action", ACTION_VALIDATE)
                put("valid", false)
                put("errors", buildJsonArray {
                    add(JsonPrimitive(it.message ?: "草稿解析失败"))
                })
            }.toString()
        }
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_VALIDATE)
            put("valid", JsonPrimitive(errors.isEmpty()))
            put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
            put("kind", JsonPrimitive(draft.kind))
            put("name", JsonPrimitive(draft.name))
            if (errors.isEmpty()) {
                put("message", JsonPrimitive("校验通过，可以提交 create/update"))
            }
        }.toString()
    }

    /**
     * 沙箱试运行：`args` 按工具入参注入，返回与线上调用一致的结果文本（含 logs）。
     *
     * 支持三种用法：
     * - 只给 `id` / `name`：跑已保存的工具
     * - 另带 `args`：覆盖试运行入参
     * - 只给 `code`（js）：不落库直接跑草稿脚本，便于边写边调
     */
    private suspend fun test(raw: JsonObject): String {
        val args = when (val element = raw["args"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> element
            is JsonPrimitive -> runCatching {
                ChatJson.json.parseToJsonElement(element.content).let { it as? JsonObject }
            }.getOrNull() ?: return toolErrorJson("args 必须是 JSON 对象")
            else -> return toolErrorJson("args 必须是 JSON 对象")
        }

        val inlineCode = ScriptToolDraftSpec.text(raw, "code")?.takeIf { it.isNotBlank() }
        val existing = resolveOrNull(raw)

        val kind: String
        val name: String
        val payload: suspend () -> String
        if (inlineCode != null || (existing == null && ScriptToolDraftSpec.provided(raw, "config"))) {
            // 草稿试运行：不落库
            val draft = runCatching {
                ScriptToolDraftSpec.merge(existing, withPlaceholderName(raw))
            }.getOrElse { return toolErrorJson(it.message ?: "草稿解析失败") }
            // 没给 name 时草稿用的是占位名，不该因为占位名重名而拒绝试运行
            val checkName = existing != null || !ScriptToolDraftSpec.text(raw, "name").isNullOrBlank()
            val errors = ScriptToolDraftSpec.errors(
                draft, takenNames(existing), selfName = existing?.name,
            ).filterNot { !checkName && it.startsWith("函数名") }
            if (errors.isNotEmpty()) return validationError(errors)
            kind = draft.kind
            name = draft.displayName
            payload = { execute(draft.kind, draft.configJson, args) }
        } else {
            val entity = existing ?: return notFound(raw)
            kind = entity.kind
            name = entity.displayName
            payload = { execute(entity.kind, entity.configJson, args) }
        }

        val started = System.currentTimeMillis()
        val text = try {
            withTimeoutOrNull(TEST_TIMEOUT_MS) { payload() }
                ?: toolErrorJson("试运行超时（${TEST_TIMEOUT_MS / 1000}s）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toolErrorJson("试运行失败：${(e.message ?: e.javaClass.simpleName).take(200)}")
        }
        val failed = runCatching { (ChatJson.json.parseToJsonElement(text) as JsonObject)["error"] != null }
            .getOrDefault(false)
        return buildJsonObject {
            put("ok", true)
            put("action", ACTION_TEST)
            put("name", JsonPrimitive(name))
            put("kind", JsonPrimitive(kind))
            put("success", JsonPrimitive(!failed))
            put("elapsed_ms", JsonPrimitive(System.currentTimeMillis() - started))
            put("args", args)
            // 结果原样透传：可能是 {"logs":...,"result":...}，也可能是 {"error":...}
            put("output", runCatching { ChatJson.json.parseToJsonElement(text) }.getOrNull() ?: JsonPrimitive(text))
        }.toString()
    }

    // ---- 开关（默认允许，不弹确认） ----

    private suspend fun setEnabled(raw: JsonObject, enabled: Boolean): String {
        val existing = resolve(raw) ?: return notFound(raw)
        if (existing.enabled == enabled) {
            return buildJsonObject {
                put("ok", true)
                put("action", if (enabled) ACTION_ENABLE else ACTION_DISABLE)
                put("id", JsonPrimitive(existing.id))
                put("name", JsonPrimitive(existing.name))
                put("enabled", JsonPrimitive(enabled))
                put("message", JsonPrimitive("状态未变化（当前已是${if (enabled) "启用" else "禁用"}）"))
            }.toString()
        }
        dao.setEnabled(existing.id, enabled, System.currentTimeMillis())
        return buildJsonObject {
            put("ok", true)
            put("action", if (enabled) ACTION_ENABLE else ACTION_DISABLE)
            put("id", JsonPrimitive(existing.id))
            put("name", JsonPrimitive(existing.name))
            put("enabled", JsonPrimitive(enabled))
            put(
                "message",
                JsonPrimitive(
                    if (enabled) {
                        "已启用：已注册进工具列表，新建会话默认勾选；当前会话如未勾选，可在输入栏扳手按钮里手动加上"
                    } else {
                        "已禁用：已从工具列表移除，工具中心仍可编辑/试运行"
                    },
                ),
            )
        }.toString()
    }

    // ---- 内部 ----

    /**
     * 判重用的占用名集合：内置工具 + **库里所有自定义工具**（含已禁用）。
     *
     * 不能只用 [ToolRegistry.takenNames]：注册表只含已启用项，而 `custom_tools.name` 上有
     * UNIQUE 约束——复用一条「已禁用工具」的名字会直接撞约束抛 SQLite 异常。
     *
     * [self] 为编辑中的自身（它自己的名字不算占用）。
     */
    private suspend fun takenNames(self: CustomToolEntity?): Set<String> {
        val dbNames = dao.all().mapNotNull { entity ->
            if (self != null && entity.id == self.id) null else entity.name
        }
        return ToolRegistry.builtIns().map { it.name }.toSet() + dbNames
    }

    /** 执行器分发：与 [cn.luoluna.sweven.ai.tools.custom.CustomToolLoader] 走同一套代码 */
    private suspend fun execute(kind: String, configJson: String, args: JsonObject): String =
        when (kind) {
            CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(configJson) }
                .fold(
                    onSuccess = { HttpToolExecutor.run(it, args) },
                    onFailure = { toolErrorJson("HTTP 配置无效：${it.message?.take(200).orEmpty()}") },
                )

            else -> runCatching { JsToolConfig.parse(configJson) }
                .fold(
                    onSuccess = { JsToolExecutor.run(it.code, args) },
                    onFailure = { toolErrorJson("JS 配置无效：${it.message?.take(200).orEmpty()}") },
                )
        }

    /** 按 id 优先、name 兜底解析既有工具 */
    private suspend fun resolve(raw: JsonObject): CustomToolEntity? = resolveOrNull(raw)

    private suspend fun resolveOrNull(raw: JsonObject): CustomToolEntity? {
        ScriptToolDraftSpec.long(raw, "id")?.let { id ->
            dao.byId(id)?.let { return it }
        }
        val name = ScriptToolDraftSpec.text(raw, "name")?.trim()
        if (!name.isNullOrEmpty()) {
            dao.all().firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    /** 把需要确认的写操作交给 [ToolApproval]；用户关掉「写操作需确认」时直接放行 */
    private suspend fun confirm(title: String, detail: String): Boolean {
        if (settings.scriptToolAutoApprove.value) return true
        return ToolApproval.request(
            toolName = ScriptToolDev.NAME,
            displayName = ScriptToolDev.DISPLAY_NAME,
            request = ApprovalRequest(title = title, detail = detail),
        )
    }

    private fun notFound(raw: JsonObject): String {
        val id = ScriptToolDraftSpec.long(raw, "id")
        val name = ScriptToolDraftSpec.text(raw, "name")?.trim()
        val which = when {
            id != null -> "id=$id"
            !name.isNullOrEmpty() -> "函数名「$name」"
            else -> "（未提供 id 或 name）"
        }
        return toolErrorJson("未找到工具 $which；可先 list 查看已有工具")
    }

    private fun denied(action: String): String =
        toolErrorJson("用户拒绝了这次「$action」操作，未做任何修改。可以询问用户想怎么调整，或改用只读动作（list/get/validate）。")

    private fun validationError(errors: List<String>): String = buildJsonObject {
        put("error", "参数校验未通过，未做任何修改")
        put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    /** 确认弹窗里的代码/配置预览（截断，避免弹窗被长脚本撑爆） */
    private fun previewOf(entity: CustomToolEntity): String? = when (entity.kind) {
        CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(entity.configJson) }.getOrNull()?.let { cfg ->
            "${cfg.method} ${cfg.url.take(200)}"
        }

        else -> runCatching { JsToolConfig.parse(entity.configJson) }.getOrNull()?.let { cfg ->
            "代码（${cfg.code.length} 字符）：\n" + cfg.code.take(PREVIEW_CHARS) +
                if (cfg.code.length > PREVIEW_CHARS) "\n…（仅预览前 $PREVIEW_CHARS 字符）" else ""
        }
    }

    private fun kindLabel(kind: String): String =
        if (kind == CustomToolKind.HTTP) "HTTP 请求" else "JS 脚本（QuickJS 沙箱）"

    private fun createdHint(enabled: Boolean): String =
        if (enabled) {
            "已创建并启用：已注册进工具列表；当前会话可在输入栏扳手按钮里勾选它后直接调用"
        } else {
            "已创建（禁用状态）：需要时用 enable 动作启用后才会出现在工具列表"
        }

    /** 对比既有实体与本轮草稿，给出人类可读的变更点（确认弹窗 + 返回结果都用） */
    private fun changedFields(before: CustomToolEntity, after: CustomToolEntity): List<String> = buildList {
        if (before.name != after.name) add("函数名")
        if (before.displayName != after.displayName) add("展示名")
        if (before.description != after.description) add("说明")
        if (before.parametersJson != after.parametersJson) add("参数 Schema")
        if (before.kind != after.kind) add("执行方式")
        if (before.configJson != after.configJson) add("配置/代码")
        if (before.needsApproval != after.needsApproval) add("执行前需确认")
        if (before.enabled != after.enabled) add("启用状态")
    }

    /** validate/test 允许省略 name：补一个不会被当作真实工具名的占位名 */
    private fun withPlaceholderName(raw: JsonObject): JsonObject =
        if (!ScriptToolDraftSpec.text(raw, "name").isNullOrBlank()) {
            raw
        } else {
            JsonObject(raw + ("name" to JsonPrimitive(PLACEHOLDER_NAME)))
        }

    companion object {
        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_DELETE = "delete"
        const val ACTION_LIST = "list"
        const val ACTION_GET = "get"
        const val ACTION_VALIDATE = "validate"
        const val ACTION_TEST = "test"
        const val ACTION_ENABLE = "enable"
        const val ACTION_DISABLE = "disable"

        val ACTIONS = listOf(
            ACTION_CREATE, ACTION_UPDATE, ACTION_DELETE, ACTION_LIST,
            ACTION_GET, ACTION_VALIDATE, ACTION_TEST, ACTION_ENABLE, ACTION_DISABLE,
        )

        /** 需要用户确认的动作（enable/disable 默认允许） */
        val CONFIRMING_ACTIONS = setOf(ACTION_CREATE, ACTION_UPDATE, ACTION_DELETE)

        /** 试运行总超时：JS 沙箱自身 10s，HTTP 配置最长 60s，这里统一兜到 30s */
        private const val TEST_TIMEOUT_MS = 30_000L

        private const val PREVIEW_CHARS = 600

        private const val PLACEHOLDER_NAME = "draft_tool"

        @Volatile
        private var instance: ToolManager? = null

        /** App 级单例（DAO/设置都来自同一个 Application） */
        fun get(context: Context): ToolManager = instance ?: synchronized(this) {
            instance ?: ToolManager(context).also { instance = it }
        }
    }
}

/** `script_tool_dev` 的登记信息（ToolManager 与内置工具定义共用，避免两处漂移） */
object ScriptToolDev {
    const val NAME = "script_tool_dev"
    const val DISPLAY_NAME = "脚本开发工具"
}
