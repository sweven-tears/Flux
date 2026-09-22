package cn.luoluna.sweven.ai.ui.chat

import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用链的逻辑轮次聚合测试：单轮/多轮/无答复/孤儿结果/老数据（无工具）都覆盖。
 * 聚合只影响渲染，不改变库里任何一条消息。
 */
class ToolChainTest {

    private var nextId = 1L

    private fun user(content: String = "问题") = MessageEntity(
        id = nextId++,
        conversationId = 1L,
        role = Role.USER,
        content = content,
    )

    private fun assistant(content: String = "", toolCalls: List<Pair<String, String>> = emptyList()) = MessageEntity(
        id = nextId++,
        conversationId = 1L,
        role = Role.ASSISTANT,
        content = content,
        toolCallsJson = toolCalls.takeIf { it.isNotEmpty() }?.let { calls ->
            calls.joinToString(prefix = "[", postfix = "]") { (id, name) ->
                """{"id":"$id","type":"function","function":{"name":"$name","arguments":"{\"q\":\"x\"}"}}"""
            }
        },
    )

    private fun toolResult(callId: String, content: String = """{"ok":true}""") = MessageEntity(
        id = nextId++,
        conversationId = 1L,
        role = Role.TOOL,
        content = content,
        toolCallId = callId,
    )

    @Test
    fun `无工具的历史原样通过`() {
        val messages = listOf(user(), assistant("普通回答"))
        val items = buildChatTurns(messages)
        assertEquals(2, items.size)
        assertTrue(items.all { it is ChatItem.Single })
    }

    @Test
    fun `单轮工具调用与其后答复聚合成一个轮次`() {
        val ask = assistant("我先查一下", listOf("c1" to "web_search"))
        val answer = assistant("查到了：……")
        val items = buildChatTurns(listOf(user(), ask, toolResult("c1"), answer))

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.Single)
        val turn = items[1] as ChatItem.ToolTurn
        assertEquals(1, turn.steps.size)
        assertEquals("web_search", turn.steps[0].calls[0].name)
        assertEquals(1, turn.steps[0].results.size)
        assertEquals(answer.id, turn.answer?.id)
        // 答复消息不再单独占一项
        assertEquals(2, items.size)
    }

    @Test
    fun `连续三轮工具调用收进同一个轮次`() {
        val messages = listOf(
            user(),
            assistant("", listOf("c1" to "get_time_info")),
            toolResult("c1"),
            assistant("", listOf("c2" to "web_search", "c3" to "fetch_url")),
            toolResult("c2"),
            toolResult("c3"),
            assistant("", listOf("c4" to "web_search")),
            toolResult("c4"),
            assistant("最终答复"),
        )
        val turn = buildChatTurns(messages).last() as ChatItem.ToolTurn
        assertEquals(3, turn.steps.size)
        assertEquals(2, turn.steps[1].calls.size)
        assertEquals(2, turn.steps[1].results.size)
        assertEquals("最终答复", turn.answer?.content)
    }

    @Test
    fun `达到轮数上限或中断时没有答复也不丢工具链`() {
        val messages = listOf(
            user(),
            assistant("", listOf("c1" to "web_search")),
            toolResult("c1"),
            assistant("", listOf("c2" to "web_search")),
            toolResult("c2"),
        )
        val items = buildChatTurns(messages)
        val turn = items.last() as ChatItem.ToolTurn
        assertEquals(2, turn.steps.size)
        assertNull(turn.answer)
    }

    @Test
    fun `孤儿工具结果单独成条不丢内容`() {
        val orphan = toolResult("ghost", """{"text":"旧数据"}""")
        val items = buildChatTurns(listOf(user(), assistant("回答"), orphan))
        assertEquals(3, items.size)
        val single = items.last() as ChatItem.Single
        assertEquals(orphan.id, single.message.id)
    }

    @Test
    fun `工具结果按 toolCallId 归属到对应步`() {
        val messages = listOf(
            user(),
            assistant("", listOf("c1" to "a")),
            toolResult("c1", "first"),
            assistant("", listOf("c2" to "b")),
            toolResult("c2", "second"),
            assistant("done"),
        )
        val turn = buildChatTurns(messages).last() as ChatItem.ToolTurn
        assertEquals("first", turn.steps[0].results[0].content)
        assertEquals("second", turn.steps[1].results[0].content)
    }

    @Test
    fun `toolCallsJson 非法时仍视作工具轮且不崩`() {
        val broken = MessageEntity(
            id = nextId++,
            conversationId = 1L,
            role = Role.ASSISTANT,
            content = "坏数据",
            toolCallsJson = "{not json",
        )
        val turn = buildChatTurns(listOf(user(), broken, assistant("答复"))).last() as ChatItem.ToolTurn
        assertEquals(1, turn.steps.size)
        assertTrue(turn.steps[0].calls.isEmpty())
        assertEquals("坏数据", turn.steps[0].assistant.content)
    }

    @Test
    fun `两个独立提问各自成轮`() {
        val messages = listOf(
            user("问1"),
            assistant("", listOf("c1" to "a")),
            toolResult("c1"),
            assistant("答1"),
            user("问2"),
            assistant("", listOf("c2" to "b")),
            toolResult("c2"),
            assistant("答2"),
        )
        val items = buildChatTurns(messages)
        // user1 / 轮次1 / user2 / 轮次2
        assertEquals(4, items.size)
        assertEquals(2, items.filterIsInstance<ChatItem.ToolTurn>().size)
    }

    @Test
    fun `工具名预计算表覆盖全部调用`() {
        val messages = listOf(
            assistant("", listOf("c1" to "web_search")),
            toolResult("c1"),
            assistant("", listOf("c2" to "get_time_info")),
            toolResult("c2"),
        )
        val names = toolCallNamesOf(messages)
        assertEquals("web_search", names["c1"])
        assertEquals("get_time_info", names["c2"])
    }

    @Test
    fun `解析容错`() {
        assertTrue(parseToolCalls(null).isEmpty())
        assertTrue(parseToolCalls("[]").isEmpty())
        assertTrue(parseToolCalls("nonsense").isEmpty())
        val calls = parseToolCalls("""[{"id":"x","function":{"name":"f","arguments":"{}"}}]""")
        assertEquals(1, calls.size)
        assertEquals("f", calls[0].name)
        assertEquals("{}", calls[0].arguments)
        // 缺 name 时回落「未知工具」，但调用本身保留
        assertEquals("未知工具", parseToolCalls("""[{"id":"y"}]""")[0].name)
    }

    @Test
    fun `列表 key 稳定且唯一`() {
        val messages = listOf(
            user(),
            assistant("", listOf("c1" to "a")),
            toolResult("c1"),
            assistant("答"),
        )
        val items = buildChatTurns(messages)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }
}
