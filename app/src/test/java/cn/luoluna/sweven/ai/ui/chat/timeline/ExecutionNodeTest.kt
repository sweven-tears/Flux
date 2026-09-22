package cn.luoluna.sweven.ai.ui.chat.timeline

import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.ui.chat.ChatItem
import cn.luoluna.sweven.ai.ui.chat.ToolCallView
import cn.luoluna.sweven.ai.ui.chat.ToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 执行轨迹派生：一轮工具调用 → 有序节点列表（不改库、不改协议历史，纯派生）。 */
class ExecutionNodeTest {

    private fun assistant(id: Long, content: String = "", toolCallsJson: String? = null, reasoning: String? = null) =
        MessageEntity(
            id = id,
            conversationId = 1L,
            role = Role.ASSISTANT,
            content = content,
            reasoning = reasoning,
            toolCallsJson = toolCallsJson,
            createdAt = 1_000L * id,
        )

    private fun toolResult(id: Long, callId: String, content: String) = MessageEntity(
        id = id,
        conversationId = 1L,
        role = Role.TOOL,
        content = content,
        toolCallId = callId,
        createdAt = 1_000L * id,
    )

    @Test
    fun `工具结果里的 error 字段判定为失败`() {
        assertTrue(toolResultFailed("""{"error":"未找到工具 web_search"}"""))
        assertTrue(toolResultFailed("""{"error":{"code":1}}"""))
        assertFalse(toolResultFailed("""{"ok":true,"items":[]}"""))
        assertFalse(toolResultFailed("""{"errors":0,"ok":true}"""))
        assertFalse(toolResultFailed("""not json"""))
    }

    @Test
    fun `一轮两调用 节点顺序为 思考 调用 结果 调用 结果 答复`() {
        val ask = assistant(
            id = 1,
            content = "我先查两件事",
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"get_time_info","arguments":"{}"}},{"id":"c2","type":"function","function":{"name":"web_search","arguments":"{\"q\":\"x\"}"}}]""",
        )
        val turn = ChatItem.ToolTurn(
            steps = listOf(
                ToolStep(
                    assistant = ask,
                    calls = listOf(
                        ToolCallView("c1", "get_time_info", "{}"),
                        ToolCallView("c2", "web_search", """{"q":"x"}"""),
                    ),
                    results = listOf(
                        toolResult(2, "c1", """{"time":"12:00"}"""),
                        toolResult(3, "c2", """{"error":"搜索超时"}"""),
                    ),
                )
            ),
            answer = assistant(id = 4, content = "答复"),
        )

        val nodes = buildExecutionNodes(turn)
        assertEquals(6, nodes.size)
        assertTrue(nodes[0] is ExecutionNode.Thinking)
        assertTrue((nodes[1] as ExecutionNode.ToolCall).nodeId.endsWith("c1"))
        assertEquals("get_time_info", (nodes[1] as ExecutionNode.ToolCall).name)
        assertEquals(ToolState.SUCCESS, (nodes[1] as ExecutionNode.ToolCall).state)
        assertEquals(ToolState.FAILED, (nodes[3] as ExecutionNode.ToolCall).state)
        assertTrue(nodes[4] is ExecutionNode.ToolResult)
        assertTrue((nodes[4] as ExecutionNode.ToolResult).failed)
        assertEquals("答复", (nodes[5] as ExecutionNode.Answer).text)
    }

    @Test
    fun `无答复时节点里没有 Answer`() {
        val ask = assistant(
            id = 1,
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"a","arguments":"{}"}}]""",
        )
        val turn = ChatItem.ToolTurn(
            steps = listOf(ToolStep(ask, listOf(ToolCallView("c1", "a", "{}")), listOf(toolResult(2, "c1", "{}")))),
            answer = null,
        )
        assertTrue(buildExecutionNodes(turn).none { it is ExecutionNode.Answer })
    }

    @Test
    fun `孤儿工具结果保留成结果节点 内容不丢`() {
        val ask = assistant(
            id = 1,
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"a","arguments":"{}"}}]""",
        )
        val turn = ChatItem.ToolTurn(
            steps = listOf(ToolStep(ask, listOf(ToolCallView("c1", "a", "{}")), listOf(toolResult(9, "ghost", """{"x":1}""")))),
            answer = null,
        )
        val nodes = buildExecutionNodes(turn)
        val orphan = nodes.last() as ExecutionNode.ToolResult
        assertEquals("""{"x":1}""", orphan.content)
    }

    @Test
    fun `节点 id 唯一 保证列表键与展开状态不串台`() {
        val ask = assistant(
            id = 1,
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"a","arguments":"{}"}}]""",
        )
        val turn = ChatItem.ToolTurn(
            steps = listOf(ToolStep(ask, listOf(ToolCallView("c1", "a", "{}")), listOf(toolResult(2, "c1", "{}")))),
            answer = assistant(3, "答复"),
        )
        val ids = buildExecutionNodes(turn).map { it.nodeId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `该轮助手的 reasoning 与文本合并成思考节点`() {
        val ask = assistant(
            id = 1,
            content = "我先想",
            reasoning = "推理过程",
            toolCallsJson = """[{"id":"c1","type":"function","function":{"name":"a","arguments":"{}"}}]""",
        )
        val turn = ChatItem.ToolTurn(
            steps = listOf(ToolStep(ask, listOf(ToolCallView("c1", "a", "{}")), emptyList())),
            answer = null,
        )
        val thinking = buildExecutionNodes(turn).first() as ExecutionNode.Thinking
        assertEquals("推理过程\n我先想", thinking.text)
    }
}
