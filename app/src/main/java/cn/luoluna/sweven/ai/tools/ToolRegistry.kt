package cn.luoluna.sweven.ai.tools

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.tools.local.buildClipboardTool
import cn.luoluna.sweven.ai.tools.local.buildFetchUrlTool
import cn.luoluna.sweven.ai.tools.local.buildJavascriptTool
import cn.luoluna.sweven.ai.tools.local.buildTimeInfoTool
import cn.luoluna.sweven.ai.tools.local.buildWebSearchTool

/**
 * 本地工具注册表：mutableStateMap 支撑，运行时注册即触发 UI 更新（与 ChatUiRegistry 同构）。
 *
 * 内置工具在 [init]（App.onCreate）静态注册；后续 MCP / 外部插件工具只需往这里注册，
 * 聊天链路与工具中心零改动。工具名以 `mcp__<server>__<tool>` 命名空间预留。
 */
object ToolRegistry {
    private val tools: SnapshotStateMap<String, FluxTool> = mutableStateMapOf()

    /** 内置工具函数名（init 时登记）：UI 用它区分「内置/自定义」并保护内置项不可删 */
    private val builtInNames = mutableSetOf<String>()

    @Volatile
    private var initialized = false

    /** 注册内置工具（幂等，App.onCreate 调用一次） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            registerBuiltIn(buildTimeInfoTool())
            registerBuiltIn(buildClipboardTool(context.applicationContext))
            registerBuiltIn(buildJavascriptTool())
            // 联网搜索：默认源现读设置（工具中心 → 联网搜索），改配置无需重启
            registerBuiltIn(buildWebSearchTool((context.applicationContext as App).settings))
            registerBuiltIn(buildFetchUrlTool())
            initialized = true
        }
    }

    private fun registerBuiltIn(tool: FluxTool) {
        builtInNames.add(tool.name)
        register(tool)
    }

    fun register(tool: FluxTool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun all(): List<FluxTool> = tools.values.sortedBy { it.name }

    fun byName(name: String): FluxTool? = tools[name]

    fun isBuiltIn(name: String): Boolean = name in builtInNames

    /** 内置工具（工具中心「本地工具」卡第一组） */
    fun builtIns(): List<FluxTool> = all().filter { it.name in builtInNames }

    /** 自定义工具（工具中心「自定义工具」卡；禁用项不在注册表里，需另读 DAO） */
    fun customs(): List<FluxTool> = all().filter { it.name !in builtInNames }

    /** 已被占用的函数名（内置 + 已注册自定义），供编辑页重名校验 */
    fun takenNames(): Set<String> = builtInNames + tools.keys.filter { it !in builtInNames }

    /** 按勾选的工具名取工具（顺序保持注册顺序，忽略不存在的名字） */
    fun byNames(names: Collection<String>): List<FluxTool> =
        all().filter { it.name in names }
}
