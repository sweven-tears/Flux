package cn.luoluna.sweven.ai.tools

import kotlinx.serialization.json.JsonObject

/**
 * 本地工具定义（参考 rikkahub 的 ai/core/Tool.kt，按其能力裁剪为纯本地执行形态）。
 *
 * - [name]：暴露给模型的函数名（OpenAI tools[].function.name），须为字母/数字/下划线
 * - [displayName]：工具中心与聊天页展示名
 * - [description]：给模型的说明（也用于工具中心副标题）
 * - [parameters]：JSON Schema（type=object 的对象）；null 表示无参数
 * - [needsApproval]：为 true 时执行前需要用户确认（本期先记录标记，UI 审批流后续接入）
 * - [execute]：执行入口，入参为模型给出的参数 JSON，返回文本结果（超长由调用方截断）
 */
data class FluxTool(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: JsonObject? = null,
    val needsApproval: Boolean = false,
    val defaultEnabled: Boolean = false,
    val execute: suspend (JsonObject) -> String,
) {
    /** 转为 OpenAI tools 数组中的单个元素 */
    fun toOpenAiSchema(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("function"))
        put(
            "function",
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("description", kotlinx.serialization.json.JsonPrimitive(description))
                parameters?.let { put("parameters", it) }
            },
        )
    }
}
