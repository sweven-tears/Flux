package cn.luoluna.sweven.ai.tools.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import cn.luoluna.sweven.ai.tools.FluxTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 剪贴板工具：读/写纯文本。
 * 涉及用户隐私与设备状态变更，标记 needsApproval（审批 UI 接入前默认不勾选使用）。
 * 注：Android 10+ 读取剪贴板要求 App 处于前台，本工具仅在聊天页可见时可用。
 */
internal fun buildClipboardTool(context: Context): FluxTool = FluxTool(
    name = "clipboard",
    displayName = "剪贴板",
    description = "读写设备剪贴板的纯文本。action=read 读取；action=write 需附 text。非用户明确要求不要写入剪贴板",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", buildJsonArray { add(JsonPrimitive("read")); add(JsonPrimitive("write")) })
                        put("description", JsonPrimitive("操作类型：read 或 write"))
                    },
                )
                put(
                    "text",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("写入剪贴板的文本（write 时必填）"))
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    },
    needsApproval = true,
) { args ->
    val action = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull
        ?: error("action 不能为空")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    when (action) {
        "read" -> buildJsonObject {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            put("text", text.orEmpty())
        }.toString()

        "write" -> {
            val text = args.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?: error("write 操作需要 text 参数")
            cm.setPrimaryClip(ClipData.newPlainText("flux", text))
            buildJsonObject {
                put("success", true)
                put("length", text.length)
            }.toString()
        }

        else -> error("未知 action：$action（可选 read / write）")
    }
}
