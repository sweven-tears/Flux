package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义 JS 工具执行器测试：`args` 注入、IIFE 包装下的 `return` 语义、
 * 错误形态与内置 JS 执行器一致。跑在 quickjs-kt-jvm（桌面原生库）上。
 */
class JsToolExecutorTest {

    private fun run(code: String, args: JsonObject = buildJsonObject { }): JsonObject = runBlocking {
        ChatJson.json.parseToJsonElement(JsToolExecutor.run(code, args)).let { it as JsonObject }
    }

    @Test
    fun `args 注入并在脚本内可用`() {
        val out = run("return args.a + args.b;", buildJsonObject {
            put("a", 1)
            put("b", 2)
        })
        assertEquals("3", out["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `支持多语句与对象参数`() {
        val args = ChatJson.json.parseToJsonElement("""{"items":[3,4]}""") as JsonObject
        val out = run(
            """
            const total = args.items.reduce((s, v) => s + v, 0);
            return JSON.stringify({ total, count: args.items.length });
            """.trimIndent(),
            args,
        )
        assertEquals("""{"total":7,"count":2}""", out["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `console 输出仍被捕获到 logs`() {
        val out = run("console.log('trace'); return 'ok';")
        assertEquals("ok", out["result"]?.jsonPrimitive?.content)
        assertTrue(out["logs"]?.jsonPrimitive?.content.orEmpty().contains("trace"))
    }

    @Test
    fun `脚本抛错返回 error 而不是崩溃`() {
        val out = run("throw new Error('bad tool');")
        assertTrue(out["error"]?.jsonPrimitive?.content.orEmpty().contains("bad tool"))
    }

    @Test
    fun `空脚本给出明确错误`() {
        val out = run("   ")
        assertTrue(out["error"]?.jsonPrimitive?.content.orEmpty().contains("脚本为空"))
    }
}
