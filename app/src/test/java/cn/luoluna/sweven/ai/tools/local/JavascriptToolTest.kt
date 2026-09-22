package cn.luoluna.sweven.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 执行沙箱（eval_javascript 工具）行为测试。
 * 单元测试通过依赖替换使用 quickjs-kt-jvm（桌面原生库），与 App 内的 Android 实现同一份 Kotlin 代码。
 */
class JavascriptToolTest {

    private fun run(code: String, timeoutMillis: Long = 5_000L): JsonObject = runBlocking {
        cn.luoluna.sweven.ai.net.ChatJson.json
            .parseToJsonElement(evaluateJavascript(code, timeoutMillis))
            .let { it as JsonObject }
    }

    @Test
    fun `基本算术返回最后一条表达式的值`() {
        val out = run("1 + 2 * 3")
        assertEquals("7", out["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `支持现代 JS 语法（箭头函数、模板串、可选链、展开、类）`() {
        val out = run(
            """
            const o = { a: { b: 2 } };
            class A { constructor(x) { this.x = x } get v() { return this.x * 2 } }
            const arr = [...[1, 2, 3]].map(v => v * 2);
            JSON.stringify({ opt: o?.a?.b ?? 0, cls: new A(3).v, tpl: `sum=${'$'}{arr.join('+')}` })
            """.trimIndent()
        )
        assertEquals("""{"opt":2,"cls":6,"tpl":"sum=2+4+6"}""", out["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `console 输出被捕获到 logs`() {
        val out = run("console.log('hi', 42); console.warn('w'); 'done'")
        assertEquals("done", out["result"]?.jsonPrimitive?.content)
        val logs = out["logs"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("logs=${logs.map { it.code }}", logs.contains("hi 42"))
        assertTrue("logs=${logs.map { it.code }}", logs.contains("WARN") && logs.contains("w"))
    }

    @Test
    fun `死循环被超时中断并返回错误`() {
        val out = run("(function(){ while(true){} })()", timeoutMillis = 1_000L)
        val error = out["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(error, error.contains("超时"))
        assertNull(out["result"])
    }

    @Test
    fun `沙箱内无法访问 Java 类`() {
        val out = run("typeof java")
        assertEquals("undefined", out["result"]?.jsonPrimitive?.content)
        val blocked = run("java.lang.System.exit(1)")
        assertTrue(blocked["error"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())
    }

    @Test
    fun `异常返回错误对象而不是崩溃`() {
        val out = run("throw new Error('boom')")
        val error = out["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(error, error.contains("boom"))
    }
}
