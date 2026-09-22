package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.tools.FluxTool
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 自定义工具装载器：把 `custom_tools` 表同步进 [ToolRegistry]。
 *
 * - 只装载 `enabled = true` 的条目；禁用/删除/改名会即时 `unregister`（注册表是 mutableStateMap，
 *   UI 立刻响应，聊天勾选侧按名字取用，不存在的名字自然被忽略）。
 * - 脏数据（函数名非法、与内置重名、互相重名、配置解析失败）一律跳过而不是崩溃：
 *   编辑页已有同一套校验（[CustomToolSpec]），这里是最后一道防线。
 * - [enabledNames] 供聊天侧计算「新会话默认勾选」用。
 */
object CustomToolLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _enabledNames = MutableStateFlow<Set<String>>(emptySet())

    /** 当前已装载（启用且合法）的自定义工具函数名 */
    val enabledNames: StateFlow<Set<String>> = _enabledNames

    @Volatile
    private var started = false

    private var loaded: Set<String> = emptySet()

    /** App.onCreate 调用一次（幂等）；需在 [ToolRegistry.init] 之后 */
    fun start(app: App) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val dao = app.database.customToolDao()
        scope.launch {
            dao.observeAll().collect { entities -> sync(entities) }
        }
    }

    private fun sync(entities: List<CustomToolEntity>) {
        val next = mutableSetOf<String>()
        entities.forEach { entity ->
            if (!entity.enabled) return@forEach
            if (!CustomToolSpec.isValidName(entity.name)) return@forEach
            if (ToolRegistry.isBuiltIn(entity.name)) return@forEach
            if (!next.add(entity.name)) return@forEach
            val tool = buildTool(entity) ?: return@forEach
            ToolRegistry.register(tool)
        }
        (loaded - next).forEach { ToolRegistry.unregister(it) }
        loaded = next
        _enabledNames.value = next
    }

    /**
     * 实体 → [FluxTool]；配置非法返回 null（装载时跳过，编辑页用同一函数做「试运行」）。
     * 展示名/说明为空时回落到函数名，避免模型看到一个没名字的工具。
     */
    fun buildTool(entity: CustomToolEntity): FluxTool? {
        if (!CustomToolKind.isKnown(entity.kind)) return null
        val parameters: JsonObject? = try {
            CustomToolSpec.parseParameters(entity.parametersJson)
        } catch (e: Exception) {
            return null
        }
        // 配置在装载时先解析一次做体检；执行时按最新配置再解析（编辑后无需重启）
        val configOk = when (entity.kind) {
            CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(entity.configJson) }.isSuccess
            else -> runCatching { JsToolConfig.parse(entity.configJson) }.isSuccess
        }
        if (!configOk) return null

        return FluxTool(
            name = entity.name,
            displayName = entity.displayName.ifBlank { entity.name },
            description = entity.description.ifBlank { entity.displayName.ifBlank { entity.name } },
            parameters = parameters,
            needsApproval = entity.needsApproval,
            defaultEnabled = false,
        ) { args ->
            when (entity.kind) {
                CustomToolKind.HTTP -> runCatching { HttpToolConfig.parse(entity.configJson) }
                    .fold(
                        onSuccess = { HttpToolExecutor.run(it, args) },
                        onFailure = { toolErrorJson("HTTP 配置无效：${it.message?.take(200).orEmpty()}") },
                    )

                else -> runCatching { JsToolConfig.parse(entity.configJson) }
                    .fold(
                        onSuccess = { JsToolExecutor.run(it.code, args) },
                        onFailure = { toolErrorJson("JS 配置无效：${it.message?.take(200).orEmpty()}") },
                    )
            }
        }
    }
}
