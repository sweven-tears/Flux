package cn.luoluna.sweven.ai.tools

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import cn.luoluna.sweven.ai.tools.local.buildClipboardTool
import cn.luoluna.sweven.ai.tools.local.buildJavascriptTool
import cn.luoluna.sweven.ai.tools.local.buildTimeInfoTool

/**
 * 本地工具注册表：mutableStateMap 支撑，运行时注册即触发 UI 更新（与 ChatUiRegistry 同构）。
 *
 * 内置工具在 [init]（App.onCreate）静态注册；后续 MCP / 外部插件工具只需往这里注册，
 * 聊天链路与工具中心零改动。工具名以 `mcp__<server>__<tool>` 命名空间预留。
 */
object ToolRegistry {
    private val tools: SnapshotStateMap<String, FluxTool> = mutableStateMapOf()

    @Volatile
    private var initialized = false

    /** 注册内置工具（幂等，App.onCreate 调用一次） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            register(buildTimeInfoTool())
            register(buildClipboardTool(context.applicationContext))
            register(buildJavascriptTool())
            initialized = true
        }
    }

    fun register(tool: FluxTool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun all(): List<FluxTool> = tools.values.sortedBy { it.name }

    fun byName(name: String): FluxTool? = tools[name]

    /** 按勾选的工具名取工具（顺序保持注册顺序，忽略不存在的名字） */
    fun byNames(names: Collection<String>): List<FluxTool> =
        all().filter { it.name in names }
}
