package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.tools.local.evaluateJavascript
import cn.luoluna.sweven.ai.tools.toolErrorJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * JS 自定义工具配置（`custom_tools.configJson` 的解析结果）。
 * [code] 为用户写的脚本：参数以 `args` 注入，返回值为最后一条表达式的值。
 */
data class JsToolConfig(val code: String) {
    fun toJson(): String = buildJsonObject { put("code", code) }.toString()

    companion object {
        fun parse(json: String): JsToolConfig {
            val obj = try {
                ChatJson.json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("JS 配置不是合法 JSON：${e.message?.take(120).orEmpty()}")
            }
            val code = (obj["code"]?.toString() ?: "")
                .removeSurrounding("\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            if (code.isBlank()) throw IllegalArgumentException("JS 工具必须填写脚本代码")
            return JsToolConfig(code)
        }
    }
}

/**
 * JS 自定义工具执行器：复用 `eval_javascript` 的 QuickJS 沙箱（10s 超时 / 64MB 内存 /
 * 无网络 / 无 DOM）。
 *
 * 用户写的是**函数体**：参数以 `const args = {...}` 注入，脚本内用 `return` 返回结果
 * （例：`return args.a + args.b;`）。包装成 IIFE 执行，避免顶层 `return` 触发语法错误；
 * 返回结构与内置 JS 执行器一致（`{"logs":..., "result":...}`），便于模型理解。
 */
object JsToolExecutor {

    suspend fun run(code: String, args: JsonObject): String {
        if (code.isBlank()) return toolErrorJson("JS 工具脚本为空")
        val wrapped = buildString {
            append("const args = ")
            append(args.toString())
            append(";\n(function(){\n")
            append(code)
            append("\n})()")
        }
        return evaluateJavascript(wrapped)
    }
}
