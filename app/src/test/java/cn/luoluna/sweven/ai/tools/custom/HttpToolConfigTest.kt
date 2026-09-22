package cn.luoluna.sweven.ai.tools.custom

import cn.luoluna.sweven.ai.net.ChatJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义工具（HTTP）的纯函数测试：模板替换/编码、结果路径抽取、截断、请求头解析、配置与函数名校验。
 * 全部不联网——执行器只在这些纯函数之上做 IO。
 */
class HttpToolConfigTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ---------------- 函数名 / 参数 Schema 校验 ----------------

    @Test
    fun `函数名只允许字母数字下划线`() {
        assertTrue(CustomToolSpec.isValidName("get_weather"))
        assertTrue(CustomToolSpec.isValidName("MyTool2"))
        assertTrue(CustomToolSpec.isValidName("a".repeat(64)))
        assertFalse(CustomToolSpec.isValidName("has-dash"))
        assertFalse(CustomToolSpec.isValidName("有中文"))
        assertFalse(CustomToolSpec.isValidName("has space"))
        assertFalse(CustomToolSpec.isValidName("a".repeat(65)))
        assertFalse(CustomToolSpec.isValidName(""))
    }

    @Test
    fun `函数名重复时给出占用提示`() {
        assertNull(CustomToolSpec.nameError("my_tool", listOf("get_time_info")))
        assertEquals("函数名不能为空", CustomToolSpec.nameError("", emptyList()))
        val taken = CustomToolSpec.nameError("my_tool", listOf("my_tool"))
        assertTrue(taken.orEmpty(), taken.orEmpty().contains("已被占用"))
    }

    @Test
    fun `参数 Schema 空白即无参数非法即报错`() {
        assertNull(CustomToolSpec.parseParameters(null))
        assertNull(CustomToolSpec.parseParameters("   "))
        assertNull(CustomToolSpec.parseParameters("{}"))
        val schema = CustomToolSpec.parseParameters("""{"type":"object"}""")
        assertEquals("object", schema?.get("type")?.jsonPrimitive?.content)
        assertTrue(CustomToolSpec.parametersError("[1,2]").orEmpty().contains("JSON 对象"))
        assertTrue(CustomToolSpec.parametersError("{oops}").orEmpty().contains("合法 JSON"))
        assertNull(CustomToolSpec.parametersError(CustomToolSpec.SAMPLE_PARAMETERS))
    }

    // ---------------- 模板渲染 ----------------

    @Test
    fun `占位符按出现顺序去重`() {
        assertEquals(listOf("q", "page"), placeholdersOf("https://x/{q}?p={page}&q={q}"))
        assertTrue(placeholdersOf("no placeholder").isEmpty())
    }

    @Test
    fun `URL 占位替换做 percent 编码空格转义`() {
        val rendered = renderTemplate("https://x/search?q={q}", args("q" to "hello world/中文"), encode = true)
        assertEquals("https://x/search?q=hello%20world%2F%E4%B8%AD%E6%96%87", rendered)
    }

    @Test
    fun `非编码场景原样替换且缺失占位保留`() {
        assertEquals("Bearer abc", renderTemplate("Bearer {token}", args("token" to "abc"), encode = false))
        assertEquals("Bearer {token}", renderTemplate("Bearer {token}", args(), encode = false))
    }

    @Test
    fun `对象型参数序列化为紧凑 JSON`() {
        val args = ChatJson.json.parseToJsonElement("""{"filter":{"tag":"a"}}""") as JsonObject
        assertEquals("""{"tag":"a"}""", renderTemplate("{filter}", args, encode = false))
    }

    // ---------------- 结果路径抽取 ----------------

    @Test
    fun `路径抽取支持点与下标并可省略美元前缀`() {
        val body = """{"data":{"items":[{"text":"hi"},{"text":"yo"}]}}"""
        assertEquals("hi", pickByPath(body, "data.items[0].text"))
        assertEquals("yo", pickByPath(body, "$.data.items[1].text"))
        assertEquals("""{"text":"hi"}""", pickByPath(body, "data.items[0]"))
    }

    @Test
    fun `路径取不到时回退整段文本`() {
        val body = """{"data":1}"""
        assertEquals(body, pickByPath(body, "data.missing.deep"))
        assertEquals(body, pickByPath(body, null))
        assertEquals(body, pickByPath(body, "   "))
        // 非 JSON 响应：路径无从解析，原样返回（模型自己读）
        assertEquals("not json at all", pickByPath("not json at all", "data"))
    }

    // ---------------- 截断 ----------------

    @Test
    fun `超长结果截断并标注原长`() {
        assertEquals("abc", truncateText("abc", 10))
        val truncated = truncateText("a".repeat(50), 10)
        assertTrue(truncated, truncated.startsWith("a".repeat(10)))
        assertTrue(truncated, truncated.contains("原长 50"))
    }

    // ---------------- 请求头 ----------------

    @Test
    fun `请求头支持 JSON 对象与占位符`() {
        val headers = parseHeaders("""{"Authorization":"Bearer {token}","X-N":1}""", args("token" to "t1"))
        assertEquals("Bearer t1", headers["Authorization"])
        assertEquals("1", headers["X-N"])
    }

    @Test
    fun `请求头支持 Key Value 逐行文本并忽略空行与注释`() {
        val headers = parseHeaders(
            """
            # 注释行
            Authorization: Bearer t2

            Accept: application/json
            """.trimIndent(),
            args(),
        )
        assertEquals("Bearer t2", headers["Authorization"])
        assertEquals("application/json", headers["Accept"])
        assertEquals(2, headers.size)
    }

    @Test
    fun `请求头缺冒号报错`() {
        val error = runCatching { parseHeaders("BadHeader", args()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("冒号"))
    }

    // ---------------- 配置解析 ----------------

    @Test
    fun `HTTP 配置缺 url 或协议非法时报错`() {
        assertTrue(
            runCatching { HttpToolConfig.parse("""{"method":"GET"}""") }.exceptionOrNull()
                ?.message.orEmpty().contains("必须填写 url"),
        )
        assertTrue(
            runCatching { HttpToolConfig.parse("""{"url":"ftp://x"}""") }.exceptionOrNull()
                ?.message.orEmpty().contains("http://"),
        )
        assertTrue(
            runCatching { HttpToolConfig.parse("not json") }.exceptionOrNull()
                ?.message.orEmpty().contains("合法 JSON"),
        )
    }

    @Test
    fun `HTTP 配置默认值与裁剪`() {
        val config = HttpToolConfig.parse("""{"url":"https://x/y"}""")
        assertEquals("GET", config.method)
        assertEquals(HttpToolConfig.DEFAULT_TIMEOUT_MS, config.timeoutMs)
        assertEquals(HttpToolConfig.DEFAULT_MAX_CHARS, config.maxChars)

        val lower = HttpToolConfig.parse("""{"url":"https://x/y","method":"post","timeoutMs":10,"maxChars":10}""")
        assertEquals("POST", lower.method)
        assertEquals(1_000L, lower.timeoutMs)
        assertEquals(500, lower.maxChars)

        assertTrue(
            runCatching { HttpToolConfig.parse("""{"url":"https://x/y","method":"TRACE"}""") }
                .exceptionOrNull()?.message.orEmpty().contains("不支持的请求方法"),
        )
    }

    @Test
    fun `配置往返 JSON 保持字段`() {
        val config = HttpToolConfig(
            method = "POST",
            url = "https://x/{q}",
            headersJson = """{"A":"b"}""",
            bodyTemplate = """{"k":"{q}"}""",
            timeoutMs = 20_000,
            maxChars = 4_000,
            resultPath = "data.items[0].text",
        )
        val parsed = HttpToolConfig.parse(config.toJson())
        assertEquals(config, parsed)
    }
}
