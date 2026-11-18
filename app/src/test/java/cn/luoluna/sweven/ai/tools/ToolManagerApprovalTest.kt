package cn.luoluna.sweven.ai.tools

import cn.luoluna.sweven.ai.data.db.CustomToolDao
import cn.luoluna.sweven.ai.data.db.CustomToolEntity
import cn.luoluna.sweven.ai.data.db.CustomToolKind
import cn.luoluna.sweven.ai.tools.custom.ScriptToolDraftSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolManagerApprovalTest {
    @Test fun testAlwaysRequiresTicketAndRejectedScriptDoesNotExecuteWhenWritesAutoApproved() = runBlocking {
        val dao = MemoryCustomToolDao()
        val manager = ToolManager(dao) { true }
        val sentinel = "APPROVAL_TEST_SCRIPT_EXECUTED"
        val operation = async {
            manager.handle(
                ToolManager.ACTION_TEST,
                buildJsonObject {
                    put("name", "approval_probe")
                    put("code", "return \"$sentinel\";")
                },
            )
        }

        val ticket = pendingTicket("试运行工具")
        assertTrue(ToolApproval.reject(ticket.id, "test denied"))
        val result = operation.await()

        assertTrue(result.contains("test denied"))
        assertFalse("Rejected test must not execute its inline JS payload", result.contains(sentinel))
        assertEquals(0, dao.writeCount)
    }

    @Test fun enableAndDisableRequireTicketsAndDenialsDoNotWriteEvenWhenWritesAutoApproved() = runBlocking {
        val disabled = MemoryCustomToolDao(tool(enabled = false))
        val enable = async {
            ToolManager(disabled) { true }.handle(ToolManager.ACTION_ENABLE, idRequest())
        }
        val enableTicket = pendingTicket("启用工具")
        assertTrue(ToolApproval.reject(enableTicket.id, "enable denied"))
        assertTrue(enable.await().contains("enable denied"))
        assertEquals(0, disabled.writeCount)
        assertFalse(disabled.row!!.enabled)

        val enabled = MemoryCustomToolDao(tool(enabled = true))
        val disable = async {
            ToolManager(enabled) { true }.handle(ToolManager.ACTION_DISABLE, idRequest())
        }
        val disableTicket = pendingTicket("禁用工具")
        assertTrue(ToolApproval.reject(disableTicket.id, "disable denied"))
        assertTrue(disable.await().contains("disable denied"))
        assertEquals(0, enabled.writeCount)
        assertTrue(enabled.row!!.enabled)
    }

    @Test fun enableAndDisableWriteOnlyAfterTheirTicketsAreApproved() = runBlocking {
        val disabled = MemoryCustomToolDao(tool(enabled = false))
        val enable = async {
            ToolManager(disabled) { true }.handle(ToolManager.ACTION_ENABLE, idRequest())
        }
        val enableTicket = pendingTicket("启用工具")
        assertTrue(ToolApproval.approve(enableTicket.id))
        assertTrue(enable.await().contains("\"ok\":true"))
        assertEquals(1, disabled.writeCount)
        assertTrue(disabled.row!!.enabled)

        val enabled = MemoryCustomToolDao(tool(enabled = true))
        val disable = async {
            ToolManager(enabled) { true }.handle(ToolManager.ACTION_DISABLE, idRequest())
        }
        val disableTicket = pendingTicket("禁用工具")
        assertTrue(ToolApproval.approve(disableTicket.id))
        assertTrue(disable.await().contains("\"ok\":true"))
        assertEquals(1, enabled.writeCount)
        assertFalse(enabled.row!!.enabled)
    }

    @Test fun newToolDraftRequiresApprovalByDefault() {
        val draft = ScriptToolDraftSpec.merge(
            null,
            buildJsonObject {
                put("name", "default_approval_probe")
                put("code", "return 1;")
            },
        )

        assertTrue(draft.needsApproval)
    }

    private suspend fun pendingTicket(titlePrefix: String): ToolApproval.Pending = withTimeout(5_000) {
        ToolApproval.pending.first { it?.title?.startsWith(titlePrefix) == true }!!
    }

    private fun idRequest(): JsonObject = buildJsonObject { put("id", 1) }

    private fun tool(enabled: Boolean) = CustomToolEntity(
        id = 1,
        name = "approval_probe",
        displayName = "Approval probe",
        description = "",
        kind = CustomToolKind.JS,
        configJson = """{"code":"return 1;"}""",
        enabled = enabled,
    )

    private class MemoryCustomToolDao(initial: CustomToolEntity? = null) : CustomToolDao {
        var row: CustomToolEntity? = initial
        var writeCount: Int = 0
            private set

        override fun observeAll(): Flow<List<CustomToolEntity>> = flowOf(listOfNotNull(row))
        override suspend fun all(): List<CustomToolEntity> = listOfNotNull(row)
        override suspend fun byId(id: Long): CustomToolEntity? = row?.takeIf { it.id == id }
        override suspend fun countNameExcluding(name: String, excludeId: Long): Int =
            if (row?.let { it.name == name && it.id != excludeId } == true) 1 else 0

        override suspend fun insert(e: CustomToolEntity): Long {
            writeCount++
            row = e.copy(id = if (e.id == 0L) 1L else e.id)
            return row!!.id
        }

        override suspend fun insertAll(list: List<CustomToolEntity>) {
            writeCount++
            row = list.firstOrNull()
        }

        override suspend fun update(e: CustomToolEntity) {
            writeCount++
            row = e
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean, at: Long) {
            writeCount++
            row = row?.takeIf { it.id == id }?.copy(enabled = enabled, updatedAt = at)
        }

        override suspend fun deleteById(id: Long) {
            writeCount++
            if (row?.id == id) row = null
        }

        override suspend fun clearAll() {
            writeCount++
            row = null
        }
    }
}
