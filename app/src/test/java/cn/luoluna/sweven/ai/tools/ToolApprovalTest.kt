package cn.luoluna.sweven.ai.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalTest {
    private fun CoroutineScope.request(owner: ToolApproval.Owner, title: String) = async(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        withContext(ToolApproval.ownerContext(owner)) {
            ToolApproval.request(
                toolName = "test_tool",
                displayName = "Test tool",
                request = ApprovalRequest(title = title, detail = "details for $title"),
                timeoutMs = 30_000,
            )
        }
    }

    private suspend fun pending(owner: ToolApproval.Owner): ToolApproval.Pending =
        withTimeout(2_000) { ToolApproval.pending(owner).first { it != null }!! }

    @Test
    fun concurrentOwnersKeepIndependentTicketsAndStaleAnswersDoNothing() = runBlocking {
        val ownerA = ToolApproval.newOwner()
        val ownerB = ToolApproval.newOwner()
        val requestA = request(ownerA, "A")
        val requestB = request(ownerB, "B")
        try {
            val ticketA = pending(ownerA)
            val ticketB = pending(ownerB)

            assertNotEquals(ticketA.id, ticketB.id)
            assertEquals(ownerA.id, ticketA.ownerId)
            assertEquals(ownerB.id, ticketB.ownerId)
            assertEquals(ticketA.id, ToolApproval.pending.value?.id)
            assertEquals(ticketB.id, ToolApproval.pending(ownerB).value?.id)

            assertTrue(ToolApproval.approve(ticketB.id))
            assertTrue(requestB.await().approved)
            assertEquals(ticketA.id, ToolApproval.pending(ownerA).value?.id)
            assertNull(ToolApproval.pending(ownerB).value)
            assertFalse(ToolApproval.approve(ticketB.id))
            assertFalse(requestA.isCompleted)

            assertTrue(ToolApproval.approve(ticketA.id))
            assertTrue(requestA.await().approved)
        } finally {
            ToolApproval.disposeOwner(ownerA)
            ToolApproval.disposeOwner(ownerB)
            requestA.cancelAndJoin()
            requestB.cancelAndJoin()
        }
    }

    @Test
    fun cancellingRequestRemovesTicketAndExecutionWaitMarker() = runBlocking {
        val owner = ToolApproval.newOwner()
        val execution = ToolApproval.newExecutionScope(owner)
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            withContext(ToolApproval.executionContext(execution)) {
                ToolApproval.request(
                    toolName = "test_tool",
                    displayName = "Test tool",
                    request = ApprovalRequest("cancel", "cancel me"),
                    timeoutMs = 30_000,
                )
            }
        }
        try {
            val ticket = pending(owner)
            assertTrue(execution.isWaitingForApproval)
            request.cancelAndJoin()

            assertFalse(execution.isWaitingForApproval)
            assertFalse(ToolApproval.hasPending(owner))
            assertNull(ToolApproval.pending(owner).value)
            assertFalse(ToolApproval.approve(ticket.id))
        } finally {
            ToolApproval.disposeOwner(owner)
            request.cancelAndJoin()
        }
    }

    @Test
    fun approvalWaitInOneExecutionScopeDoesNotMarkAnotherScopeWaiting() = runBlocking {
        val ownerA = ToolApproval.newOwner()
        val ownerB = ToolApproval.newOwner()
        val executionA = ToolApproval.newExecutionScope(ownerA)
        val executionB = ToolApproval.newExecutionScope(ownerB)
        val requestA = async(start = CoroutineStart.UNDISPATCHED) {
            withContext(ToolApproval.executionContext(executionA)) {
                ToolApproval.request(
                    toolName = "test_tool",
                    displayName = "Test tool",
                    request = ApprovalRequest("A", "A only"),
                    timeoutMs = 30_000,
                )
            }
        }
        try {
            val ticket = pending(ownerA)
            assertTrue(executionA.isWaitingForApproval)
            assertFalse(executionB.isWaitingForApproval)

            assertTrue(ToolApproval.approve(ticket.id))
            assertTrue(requestA.await().approved)
            assertFalse(executionA.isWaitingForApproval)
            assertFalse(executionB.isWaitingForApproval)
        } finally {
            ToolApproval.disposeOwner(ownerA)
            ToolApproval.disposeOwner(ownerB)
            requestA.cancelAndJoin()
        }
    }

    @Test
    fun deactivatingOwnerRejectsOnlyItsTicketsAndLaterRequests() = runBlocking {
        val ownerA = ToolApproval.newOwner()
        val ownerB = ToolApproval.newOwner()
        val requestA = request(ownerA, "A")
        val requestB = request(ownerB, "B")
        try {
            val ticketA = pending(ownerA)
            val ticketB = pending(ownerB)
            ToolApproval.deactivateOwner(ownerA, "A left")

            val resultA = requestA.await()
            assertFalse(resultA.approved)
            assertEquals("A left", resultA.reason)
            assertNull(ToolApproval.pending(ownerA).value)
            assertEquals(ticketB.id, ToolApproval.pending.value?.id)
            assertEquals(ticketB.id, ToolApproval.pending(ownerB).value?.id)
            assertFalse(ToolApproval.approve(ticketA.id))

            val delayed = withTimeout(2_000) {
                withContext(ToolApproval.ownerContext(ownerA)) {
                    ToolApproval.request(
                        toolName = "test_tool",
                        displayName = "Test tool",
                        request = ApprovalRequest("late", "after deactivation"),
                    )
                }
            }
            assertFalse(delayed.approved)
            assertEquals(ticketB.id, ToolApproval.pending(ownerB).value?.id)

            assertTrue(ToolApproval.approve(ticketB.id))
            assertTrue(requestB.await().approved)
        } finally {
            ToolApproval.disposeOwner(ownerA)
            ToolApproval.disposeOwner(ownerB)
            requestA.cancelAndJoin()
            requestB.cancelAndJoin()
        }
    }

    @Test
    fun publishingAfterApprovalSkipsCompletedHeadAndRejectsStaleId() = runBlocking {
        val owner = ToolApproval.newOwner()
        val first = request(owner, "first")
        val second = request(owner, "second")
        try {
            val firstTicket = pending(owner)
            assertTrue(ToolApproval.approve(firstTicket.id))

            val secondTicket = withTimeout(2_000) {
                ToolApproval.pending(owner).first { it?.id != firstTicket.id }!!
            }
            assertNotEquals(firstTicket.id, secondTicket.id)
            assertEquals(secondTicket.id, ToolApproval.pending.value?.id)
            assertFalse(ToolApproval.approve(firstTicket.id))
            assertTrue(ToolApproval.approve(secondTicket.id))

            assertTrue(first.await().approved)
            assertTrue(second.await().approved)
        } finally {
            ToolApproval.disposeOwner(owner)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }
}
