package cn.luoluna.sweven.ai.tools.local

import android.content.Context
import cn.luoluna.sweven.ai.tools.FluxTool
import cn.luoluna.sweven.ai.tools.ScriptToolDev
import cn.luoluna.sweven.ai.tools.ToolManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 脚本开发工具（`script_tool_dev`）：让模型在聊天里**创建/迭代自定义工具**本身。
 *
 * 这是「用聊天造工具」的入口——用户在对话里说「帮我做个查天气的工具」，
 * 模型就调用本工具写脚本、validate、test、create 一条龙，全程不用离开聊天页；
 * 建出来的工具与工具中心手写的是同一张表、同一套执行器（HTTP / JS 沙箱）。
 *
 * 确认策略由 [ToolManager] 执行：create/update/delete 默认弹窗让用户点头，
 * list/get/validate 直接跑，test 在沙箱里跑，enable/disable 默认允许。
 *
 * 默认关闭（会写库、会跑模型生成的代码）：需用户在工具中心主动开启。
 */
internal fun buildScriptToolDevTool(context: Context): FluxTool = FluxTool(
    name = ScriptToolDev.NAME,
    displayName = ScriptToolDev.DISPLAY_NAME,
    description = "创建/修改/删除/查询/校验/试运行自定义工具（用聊天造工具）。" +
        "create/update/delete 默认需用户确认；list/get/validate 直接执行；" +
        "test 在 QuickJS 沙箱（JS）或真实请求（HTTP）里跑一次；enable/disable 直接生效。",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "enum",
                            buildJsonArray {
                                ToolManager.ACTIONS.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                        put(
                            "description",
                            JsonPrimitive(
                                "create 新建 / update 局部更新 / delete 删除 / list 列表 / get 详情 / " +
                                    "validate 静态校验（不落库）/ test 沙箱试运行 / enable 启用 / disable 禁用",
                            ),
                        )
                    },
                )
                put(
                    "id",
                    buildJsonObject {
                        put("type", JsonPrimitive("integer"))
                        put("description", JsonPrimitive("工具 id（update/delete/get/test/enable/disable 用；也可改用 name 定位）"))
                    },
                )
                put(
                    "name",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("函数名（暴露给模型，字母/数字/下划线，不能与内置或其它工具重名）"))
                    },
                )
                put(
                    "display_name",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("展示名（工具中心与聊天里显示的名字，缺省用函数名）"))
                    },
                )
                put(
                    "description",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("给模型看的工具说明：写清「什么时候调用、参数含义、返回什么」"))
                    },
                )
                put(
                    "kind",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray { add(JsonPrimitive("js")); add(JsonPrimitive("http")) })
                        put("description", JsonPrimitive("执行方式：js=QuickJS 沙箱脚本（默认）；http=调用 REST API"))
                    },
                )
                put(
                    "code",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "js 工具的脚本：参数从 args 取，用 return 返回结果（例：return args.a + args.b）；" +
                                    "沙箱无网络、无 DOM、无文件，10 秒超时",
                            ),
                        )
                    },
                )
                put(
                    "config",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "description",
                            JsonPrimitive(
                                "http 工具的配置：{method,url,headersJson,bodyTemplate,timeoutMs,maxChars,resultPath}；" +
                                    "url/body/headers 里可用 {参数名} 占位",
                            ),
                        )
                    },
                )
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("工具的参数 JSON Schema（type=object）；无参数可省略"))
                    },
                )
                put(
                    "needs_approval",
                    buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("标记该工具执行前需用户确认（涉及隐私/状态变更时置 true）"))
                    },
                )
                put(
                    "enabled",
                    buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("create 时是否立即启用（默认 true）；update 时可改"))
                    },
                )
                put(
                    "args",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("test 动作的入参（按该工具自己的 parameters 写）"))
                    },
                )
                put(
                    "filter",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("all"))
                            add(JsonPrimitive("enabled"))
                            add(JsonPrimitive("disabled"))
                            add(JsonPrimitive("js"))
                            add(JsonPrimitive("http"))
                        })
                        put("description", JsonPrimitive("list 动作的过滤条件"))
                    },
                )
                put(
                    "query",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("list 动作的关键词（匹配函数名/展示名/说明）"))
                    },
                )
                put(
                    "include_disabled",
                    buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("description", JsonPrimitive("list 是否包含已禁用工具（默认 true）"))
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    },
    needsApproval = false,
    defaultEnabled = false,
) { args ->
    val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        ?: error("action 不能为空（可选 ${ToolManager.ACTIONS.joinToString("/")}）")
    val objectArgs = try {
        args.jsonObject
    } catch (e: Exception) {
        error("参数必须是 JSON 对象：${e.message?.take(120).orEmpty()}")
    }
    ToolManager.get(context).handle(action, objectArgs)
}
