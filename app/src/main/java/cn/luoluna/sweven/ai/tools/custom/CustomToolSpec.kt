package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.serialization.json.JsonObject

/**
 * 自定义工具的校验与解析：全部纯函数（JVM 单测覆盖）。
 *
 * 编辑页校验与装载器（[CustomToolLoader]）共用同一套规则，
 * 保证「编辑页能保存的，装载器一定能注册」；反过来装载器仍会兜底跳过脏数据。
 */
object CustomToolSpec {

    /** 暴露给模型的函数名形态：字母/数字/下划线，1-64 字符 */
    private val NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,64}$")

    fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)

    /**
     * 函数名校验错误文案；合法返回 null。
     * [taken] 为已被占用的函数名（内置工具 + 其它自定义工具），重名一律拒绝
     * （重名会让 ToolRegistry 互相覆盖，模型也分不清该调哪个）。
     */
    fun nameError(name: String, taken: Collection<String>): String? = when {
        name.isBlank() -> "函数名不能为空"
        !isValidName(name) -> "函数名只能包含字母、数字、下划线，长度 1-64"
        name in taken -> "函数名「$name」已被占用，请换一个"
        else -> null
    }

    /**
     * 参数 Schema 文本 → JsonObject；空白或 `{}` = 无参数（null）。
     * 非法 JSON / 非对象 → 抛 [IllegalArgumentException]。
     */
    fun parseParameters(json: String?): JsonObject? {
        val text = json?.trim().orEmpty()
        if (text.isEmpty() || text == "{}") return null
        val element = try {
            ChatJson.json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("参数 Schema 不是合法 JSON：${e.message?.take(120).orEmpty()}")
        }
        return element as? JsonObject
            ?: throw IllegalArgumentException("参数 Schema 必须是 JSON 对象（type=object 的 schema）")
    }

    /** 参数 Schema 校验错误文案；合法返回 null */
    fun parametersError(json: String?): String? = runCatching { parseParameters(json) }
        .fold(onSuccess = { null }, onFailure = { it.message ?: "参数 Schema 不合法" })

    /** 新建工具时给用户预填的参数 Schema 模板（带一个示例参数） */
    const val SAMPLE_PARAMETERS = """{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "查询关键词"
    }
  },
  "required": ["query"]
}"""
}
