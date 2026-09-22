package cn.luoluna.sweven.ai.ui.chat.timeline

import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.ui.chat.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折叠规则 + 摘要 + 实时节点：全部纯函数，不碰 Compose。 */
class TimelineViewTest {

    private fun thinking(id: String, at: Long) =
        ExecutionNode.Thinking(nodeId = id, createdAt = at, text = "想一下")

    private fun call(id: String, at: Long, state: ToolState = ToolState.SUCCESS) =
        ExecutionNode.ToolCall(nodeId = id, createdAt = at, name = "web_search", arguments = "{}", state = state)

    private fun result(id: String, at: Long, failed: Boolean = false) =
        ExecutionNode.ToolResult(nodeId = id, createdAt = at, name = "web_search", content = "{}", failed = failed)

    private fun answer(id: String, at: Long) = ExecutionNode.Answer(
        nodeId = id,
        createdAt = at,
        text = "答复",
        reasoning = null,
        message = MessageEntity(id = 1L, conversationId = 1L, role = "assistant", content = "答复"),
    )

    @Test
    fun `执行中只显示最新一个节点 其余折进摘要`() {
        val nodes = listOf(thinking("t1", 100), call("c1", 200), result("r1", 300))
        val view = resolveTimelineView(nodes, expanded = false, turnRunning = true)
        assertEquals(listOf("r1"), view.visibleNodes.map { it.nodeId })
        assertEquals(2, view.hiddenCount)
        assertTrue(view.headline.contains("已完成"))
    }

    @Test
    fun `结束后中间过程全部折叠 只留最终答复`() {
        val nodes = listOf(thinking("t1", 100), call("c1", 200), result("r1", 300), answer("a1", 5000))
        val view = resolveTimelineView(nodes, expanded = false, turnRunning = false)
        assertEquals(listOf("a1"), view.visibleNodes.map { it.nodeId })
        assertEquals(3, view.hiddenCount)
        assertTrue(view.headline.contains("调用 1 个工具"))
        assertTrue(view.headline.contains("耗时"))
    }

    @Test
    fun `展开后全部节点可见 不折叠`() {
        val nodes = listOf(thinking("t1", 100), call("c1", 200), result("r1", 300), answer("a1", 5000))
        val view = resolveTimelineView(nodes, expanded = true, turnRunning = false)
        assertEquals(4, view.visibleNodes.size)
        assertEquals(0, view.hiddenCount)
    }

    @Test
    fun `摘要含失败提示`() {
        val nodes = listOf(call("c1", 100, ToolState.FAILED), result("r1", 200, failed = true))
        assertTrue(resolveTimelineView(nodes, expanded = false, turnRunning = false).headline.contains("失败"))
    }

    @Test
    fun `耗时来自首末节点时间差`() {
        val summary = summarizeTimeline(listOf(thinking("t1", 1_000), answer("a1", 9_400)), turnRunning = false)
        assertEquals(8_400L, summary.durationMs)
        assertTrue(summary.headline.contains("8.4s"))
    }

    @Test
    fun `没有工具调用时不写调用数`() {
        val summary = summarizeTimeline(listOf(thinking("t1", 0), answer("a1", 100)), turnRunning = false)
        assertFalse(summary.headline.contains("个工具"))
    }

    @Test
    fun `实时调用合成为 RUNNING 节点并排在最后`() {
        val nodes = listOf(thinking("t1", 100), call("c1", 200))
        val merged = withLiveNodes(nodes, listOf("web_search", "fetch_url"), running = true)
        assertEquals(4, merged.size)
        val live = merged.last() as ExecutionNode.ToolCall
        assertEquals(ToolState.RUNNING, live.state)
        assertEquals("fetch_url", live.name)
        // 执行中只露最新节点 → 露出的正是 RUNNING 节点
        val view = resolveTimelineView(merged, expanded = false, turnRunning = true)
        assertEquals(listOf(live.nodeId), view.visibleNodes.map { it.nodeId })
    }

    @Test
    fun `未在执行时不合成实时节点`() {
        val nodes = listOf(call("c1", 200))
        assertEquals(nodes, withLiveNodes(nodes, listOf("web_search"), running = false))
        assertEquals(nodes, withLiveNodes(nodes, emptyList(), running = true))
    }

    @Test
    fun `同名工具的实时节点 id 不重复`() {
        val merged = withLiveNodes(emptyList(), listOf("web_search", "web_search"), running = true)
        assertEquals(merged.size, merged.map { it.nodeId }.toSet().size)
    }

    @Test
    fun `轮次是否在执行中 由 streaming 与是否有答复共同决定`() {
        val running = ChatItem.ToolTurn(steps = emptyList(), answer = null)
        val finished = ChatItem.ToolTurn(
            steps = emptyList(),
            answer = MessageEntity(id = 1L, conversationId = 1L, role = "assistant", content = "答复"),
        )
        assertTrue(isTurnRunning(running, streaming = true))
        // 达到轮数上限/中断的历史轮次：没有答复但已不在流式中 → 不算执行中
        assertFalse(isTurnRunning(running, streaming = false))
        assertFalse(isTurnRunning(finished, streaming = true))
    }
}
