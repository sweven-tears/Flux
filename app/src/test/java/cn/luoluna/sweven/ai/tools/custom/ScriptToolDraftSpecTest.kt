package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本开发工具的草稿合并逻辑（纯函数）：局部字段更新、配置归一化、校验规则。
 *
 * 这些规则决定「模型通过聊天建出来的工具」与「编辑页手写的工具」是否同构，
 * 因此和编辑页共用同一套断言口径。
 */
class ScriptToolDraftSpecTest {

    private fun obj(json: String): JsonObject =
        ChatJson.json.parseToJsonElement(json) as JsonObject

    private fun existingJs(code: String = "return 1;"): CustomToolEntity = CustomToolEntity(
        id = 7,
        name = "my_tool",
        displayName = "我的工具",
        description = "旧说明",
        parametersJson = null,
        kind = CustomToolKind.JS,
        configJson = JsToolConfig(code).toJson(),
        needsApproval = false,
        enabled = true,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    @Test
    fun `create 只要 name 与 code 就能建出合法实体`() {
        val entity = ScriptToolDraftSpec.merge(
            null,
            obj("""{"name":"weather","code":"return args.city;","description":"查天气"}"""),
        )
        assertEquals("weather", entity.name)
        assertEquals(CustomToolKind.JS, entity.kind)
        // 展示名缺省回落到函数名
        assertEquals("weather", entity.displayName)
        assertTrue(entity.enabled)
        assertEquals(0L, entity.id)
        assertEquals("return args.city;", JsToolConfig.parse(entity.configJson).code)
        assertTrue(ScriptToolDraftSpec.errors(entity, emptyList(), selfName = null).isEmpty())
    }

    @Test
    fun `create 缺少 name 或 code 时给出可操作的错误`() {
        val noName = runCatching { ScriptToolDraftSpec.merge(null, obj("""{"code":"return 1;"}""")) }
        assertTrue(noName.exceptionOrNull()?.message.orEmpty().contains("name"))

        val noCode = runCatching { ScriptToolDraftSpec.merge(null, obj("""{"name":"t"}""")) }
        assertTrue(noCode.exceptionOrNull()?.message.orEmpty().contains("code"))
    }

    @Test
    fun `create http 工具必须给 config 而 code 会被拒绝`() {
        val missing = runCatching {
            ScriptToolDraftSpec.merge(null, obj("""{"name":"t","kind":"http"}"""))
        }
        assertTrue(missing.exceptionOrNull()?.message.orEmpty().contains("config"))

        val wrongField = runCatching {
            ScriptToolDraftSpec.merge(null, obj("""{"name":"t","kind":"http","code":"return 1;"}"""))
        }
        assertTrue(wrongField.exceptionOrNull()?.message.orEmpty().contains("code"))
    }

    @Test
    fun `http 工具 config 走同一套解析并保留可选字段`() {
        val entity = ScriptToolDraftSpec.merge(
            null,
            obj(
                """
                {"name":"weather","kind":"http",
                 "config":{"method":"get","url":"https://api.example.com/w?q={city}","resultPath":"data.text"}}
                """.trimIndent(),
            ),
        )
        val cfg = HttpToolConfig.parse(entity.configJson)
        assertEquals("GET", cfg.method)
        assertEquals("data.text", cfg.resultPath)
        assertTrue(ScriptToolDraftSpec.errors(entity, emptyList(), selfName = null).isEmpty())
    }

    @Test
    fun `update 只改提到过的字段，其余保持原值`() {
        val before = existingJs()
        val after = ScriptToolDraftSpec.merge(
            before,
            obj("""{"id":7,"description":"新说明"}"""),
        )
        assertEquals(7L, after.id)
        assertEquals("我的工具", after.displayName)
        assertEquals("新说明", after.description)
        // 配置没提 = 保持不变
        assertEquals(before.configJson, after.configJson)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
    }

    @Test
    fun `update 用 code 覆盖脚本`() {
        val after = ScriptToolDraftSpec.merge(existingJs(), obj("""{"code":"return args.a * 2;"}"""))
        assertEquals("return args.a * 2;", JsToolConfig.parse(after.configJson).code)
    }

    @Test
    fun `显式 null 的 parameters 表示清空`() {
        val before = existingJs().copy(parametersJson = """{"type":"object"}""")
        val cleared = ScriptToolDraftSpec.merge(before, obj("""{"parameters":null}"""))
        assertNull(cleared.parametersJson)
        // 没提到则保持
        val kept = ScriptToolDraftSpec.merge(before, obj("""{"description":"x"}"""))
        assertNotNull(kept.parametersJson)
    }

    @Test
    fun `换 kind 但不给新配置时报错而不是留下错配配置`() {
        val failure = runCatching {
            ScriptToolDraftSpec.merge(existingJs(), obj("""{"kind":"http"}"""))
        }
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("config"))
    }

    @Test
    fun `校验拦住重名、非法函数名与坏 Schema`() {
        val base = ScriptToolDraftSpec.merge(null, obj("""{"name":"dup","code":"return 1;"}"""))

        val taken = ScriptToolDraftSpec.errors(base, listOf("dup"), selfName = null)
        assertTrue(taken.any { it.contains("已被占用") })

        // 编辑自身：同名不算占用
        assertTrue(ScriptToolDraftSpec.errors(base, listOf("dup"), selfName = "dup").isEmpty())

        val badName = ScriptToolDraftSpec.errors(base.copy(name = "bad-name"), emptyList(), null)
        assertTrue(badName.any { it.contains("字母") })

        val badSchema = ScriptToolDraftSpec.errors(
            base.copy(parametersJson = "{not json"), emptyList(), null,
        )
        assertTrue(badSchema.isNotEmpty())
    }

    @Test
    fun `空配置与坏配置被校验拦住`() {
        val badJs = existingJs().copy(configJson = """{"code":"   "}""")
        assertTrue(ScriptToolDraftSpec.errors(badJs, emptyList(), null).isNotEmpty())

        val badHttp = existingJs().copy(kind = CustomToolKind.HTTP, configJson = """{"method":"GET"}""")
        assertTrue(ScriptToolDraftSpec.errors(badHttp, emptyList(), null).any { it.contains("url") })
    }

    @Test
    fun `超长脚本被拦住`() {
        val long = "x".repeat(ScriptToolDraftSpec.MAX_CODE_CHARS + 1)
        val entity = existingJs(code = long)
        assertTrue(ScriptToolDraftSpec.errors(entity, emptyList(), null).any { it.contains("过长") })
    }

    @Test
    fun `toJson 保留结构化配置便于模型读回`() {
        val entity = existingJs(code = "return 1 + 1;")
        val json = ScriptToolDraftSpec.toJson(entity)
        assertEquals("my_tool", ScriptToolDraftSpec.text(json, "name"))
        assertEquals("js", ScriptToolDraftSpec.text(json, "kind"))
        // config 以对象形态回给模型（而不是转义后的字符串）
        val config = json["config"] as? JsonObject
        assertNotNull(config)
        assertEquals("return 1 + 1;", ScriptToolDraftSpec.text(config!!, "code"))

        // list 场景可以省掉配置
        assertNull(ScriptToolDraftSpec.toJson(entity, includeConfig = false)["config"])
    }

    @Test
    fun `布尔与整数字段兼容文本形态`() {
        val raw = obj("""{"enabled":"true","needs_approval":"false","id":"12"}""")
        assertEquals(true, ScriptToolDraftSpec.bool(raw, "enabled"))
        assertEquals(false, ScriptToolDraftSpec.bool(raw, "needs_approval"))
        assertEquals(12L, ScriptToolDraftSpec.long(raw, "id"))
        assertNull(ScriptToolDraftSpec.bool(raw, "missing"))
    }
}
