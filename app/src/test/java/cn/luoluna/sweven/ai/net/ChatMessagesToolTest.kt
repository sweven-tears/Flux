package cn.luoluna.sweven.ai.net

import cn.luoluna.sweven.ai.data.db.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具调用链路的消息组装与流式解析测试（OpenAI 协议形态） */
class ChatMessagesToolTest {

    private fun frame(deltaJson: String): String =
        """{"choices":[{"index":0,"delta":$deltaJson}]}"""

    @Test
    fun `流式 tool_calls 分片聚合为完整调用`() {
        val acc = ToolCallAccumulator()
        // 首帧带 id 与函数名，参数分两片下发（真实厂商形态）
        acc.accept(parseToolCallDeltas(frame("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time_info","arguments":""}}]}""")))
        acc.accept(parseToolCallDeltas(frame("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\":"}}]}""")))
        acc.accept(parseToolCallDeltas(frame("""{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}""")))
        assertTrue(!acc.isEmpty)
        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("get_time_info", calls[0].name)
        assertEquals("call_1", calls[0].id)
        assertEquals("""{"a":1}""", calls[0].arguments)
    }

    @Test
    fun `普通帧不产生工具调用`() {
        assertTrue(parseToolCallDeltas(frame("""{"content":"hi"}""")).isEmpty())
        assertTrue(ToolCallAccumulator().isEmpty)
    }

    @Test
    fun `带工具调用的助手消息与工具结果按配对发出且不插入占位`() {
        val messages = buildMessagesJsonEx(
            listOf(
                ChatMessageInput(Role.USER, "现在几点"),
                ChatMessageInput(
                    role = Role.ASSISTANT,
                    content = "",
                    toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"get_time_info","arguments":"{}"}}]""",
                ),
                ChatMessageInput(Role.TOOL, """{"time":"12:00"}""", toolCallId = "call_1"),
                ChatMessageInput(Role.ASSISTANT, "现在是 12:00"),
            )
        )
        assertEquals("messages=$messages", 4, messages.size)
        val roles = messages.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertEquals("messages=$messages", listOf("user", "assistant", "tool", "assistant"), roles)
        // 工具结果携带 tool_call_id，内容原样
        val toolMsg = messages[2].jsonObject
        assertEquals("call_1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("""{"time":"12:00"}""", toolMsg["content"]?.jsonPrimitive?.content)
        // 助手工具调用以 tool_calls 数组发出，content 用空格占位避免空内容被网关丢弃
        val assistantMsg = messages[1].jsonObject
        assertEquals(1, (assistantMsg["tool_calls"] as JsonArray).size)
        assertEquals(" ", assistantMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `普通消息保持原有的同角色占位补齐行为`() {
        val messages = buildMessagesJsonEx(
            listOf(
                ChatMessageInput(Role.USER, "你好"),
                ChatMessageInput(Role.USER, "在吗"),
            )
        )
        val roles = messages.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun `工具结果消息的图片不随工具结果回传`() {
        // buildRequestBody 侧对 assistant 消息剥离图片；此处验证工具消息构造不会带上图片字段
        val messages = buildMessagesJsonEx(
            listOf(ChatMessageInput(Role.TOOL, "result", toolCallId = "c1"))
        )
        val obj = messages[0] as JsonObject
        assertEquals("tool", obj["role"]?.jsonPrimitive?.content)
        assertEquals("result", obj["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `本地工具转换为 OpenAI tools 数组`() {
        val body = buildChatBody(
            modelName = "m",
            messages = JsonArray(emptyList()),
            stream = true,
        )
        val tool = cn.luoluna.sweven.ai.tools.ToolRegistry.let { registry ->
            cn.luoluna.sweven.ai.tools.FluxTool(
                name = "demo_tool",
                displayName = "演示",
                description = "演示工具",
                parameters = null,
            ) { "ok" }
        }
        val withTools = applyLocalTools(body, listOf(tool))
        val arr = withTools["tools"] as JsonArray
        assertEquals(1, arr.size)
        val fn = (arr[0] as JsonObject)["function"] as JsonObject
        assertEquals("demo_tool", fn["name"]?.jsonPrimitive?.content)
        // 已有 tools 键时不覆盖（尊重模型级 customBody）
        val existing = kotlinx.serialization.json.buildJsonObject {
            put("tools", JsonArray(emptyList()))
        }
        val merged = applyLocalTools(existing, listOf(tool))
        assertEquals(0, (merged["tools"] as JsonArray).size)
    }
}
