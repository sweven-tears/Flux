package cn.luoluna.sweven.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次「执行前需确认」的描述：标题一行，详情给用户看关键信息（参数/代码摘要/影响面） */
data class ApprovalRequest(
    val title: String,
    val detail: String,
)

/** 结果把拒绝/离开页面/超时的缘由传回工具，避免只返回含糊的 false。 */
data class ApprovalResult(
    val approved: Boolean,
    val reason: String? = null,
)

/**
 * 执行前审批闸门：请求按 ticket 入队，UI 只可对指定 ticket 作答。
 * 每个 owner（通常是一个聊天页面实例）拥有独立请求；取消 owner 只会拒绝它自己的审批。
 */
object ToolApproval {

    data class Owner internal constructor(val id: Long)

    data class Pending(
        val id: Long,
        val ownerId: Long,
        val toolName: String,
        val displayName: String,
        val title: String,
        val detail: String,
        val requestedAt: Long,
    )

    /** Global FIFO queue head; UI renders it only in the matching chat owner. */
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    /** Observable first ticket belonging to this owner, even when another owner's ticket is ahead. */
    fun pending(owner: Owner): StateFlow<Pending?> = synchronized(lock) {
        ownerPending.getOrPut(owner.id) {
            MutableStateFlow(queue.firstOrNull { it.ticket.ownerId == owner.id && !it.gate.isCompleted }?.ticket)
        }
    }

    private data class Outcome(val approved: Boolean, val reason: String? = null)
    private data class Entry(val ticket: Pending, val gate: CompletableDeferred<Outcome>)

    private val lock = Any()
    private val owners = AtomicLong(1)
    private val tickets = AtomicLong(1)
    private val queue = ArrayDeque<Entry>()
    private val fallbackOwner = Owner(0)
    private val activeOwners = mutableSetOf<Long>(fallbackOwner.id)
    private val ownerPending = mutableMapOf<Long, MutableStateFlow<Pending?>>()

    /** 一个工具执行作用域共享的 ticket 集合，供执行预算只豁免自己的审批等待。 */
    class ExecutionScope internal constructor(val owner: Owner) {
        private val waiting = ConcurrentHashMap.newKeySet<Long>()
        val isWaitingForApproval: Boolean get() = waiting.isNotEmpty()
        internal fun add(ticketId: Long) { waiting.add(ticketId) }
        internal fun remove(ticketId: Long) { waiting.remove(ticketId) }
    }

    private class ContextElement(
        val owner: Owner,
        val execution: ExecutionScope?,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ContextElement>
    }

    fun newOwner(): Owner = Owner(owners.getAndIncrement()).also { owner ->
        synchronized(lock) { activeOwners += owner.id }
    }

    /** Mark a chat owner active again when its route is shown. */
    fun activateOwner(owner: Owner) {
        synchronized(lock) { activeOwners += owner.id }
    }

    /** Deactivate an owner and reject its current tickets; later requests fail immediately. */
    fun deactivateOwner(
        owner: Owner,
        reason: String = "聊天页面已离开，审批已取消，操作未执行。",
    ) {
        synchronized(lock) {
            activeOwners -= owner.id
            queue.filter { it.ticket.ownerId == owner.id }.forEach { entry ->
                entry.gate.complete(Outcome(approved = false, reason = reason))
            }
            publishHeadLocked()
        }
    }

    /** Permanently dispose an owner when its ViewModel is cleared. */
    fun disposeOwner(owner: Owner) {
        deactivateOwner(owner, "聊天页面已关闭，审批已取消，操作未执行。")
        synchronized(lock) { ownerPending.remove(owner.id) }
    }

    fun newExecutionScope(owner: Owner): ExecutionScope = ExecutionScope(owner)

    fun ownerContext(owner: Owner): CoroutineContext = ContextElement(owner, null)

    fun executionContext(scope: ExecutionScope): CoroutineContext = ContextElement(scope.owner, scope)

    /** 请求用户确认：挂起直到允许/拒绝/超时；取消 coroutine 时立刻清理 ticket。 */
    suspend fun request(
        toolName: String,
        displayName: String,
        request: ApprovalRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        owner: Owner? = null,
    ): ApprovalResult {
        val contextElement = currentCoroutineContext()[ContextElement]
        val ticketOwner = owner ?: contextElement?.owner ?: fallbackOwner
        val execution = contextElement?.execution?.takeIf { it.owner == ticketOwner }
        val (entry, inactiveOutcome) = synchronized(lock) {
            if (ticketOwner.id !in activeOwners) {
                null to Outcome(false, "聊天页面已离开，审批已取消，操作未执行。")
            } else {
                val created = Entry(
                    ticket = Pending(
                        id = tickets.getAndIncrement(),
                        ownerId = ticketOwner.id,
                        toolName = toolName,
                        displayName = displayName,
                        title = request.title,
                        detail = request.detail,
                        requestedAt = System.currentTimeMillis(),
                    ),
                    gate = CompletableDeferred(),
                )
                queue.addLast(created)
                execution?.add(created.ticket.id)
                publishHeadLocked()
                created to null
            }
        }
        if (entry == null) return ApprovalResult(false, inactiveOutcome?.reason)
        try {
            val result = withTimeoutOrNull(timeoutMs) { entry.gate.await() }
                ?: Outcome(false, "审批等待超时（${timeoutMs / 1000}s），操作未执行。")
            return ApprovalResult(result.approved, result.reason)
        } finally {
            synchronized(lock) {
                queue.remove(entry)
                publishHeadLocked()
            }
            execution?.remove(entry.ticket.id)
        }
    }

    /** 用户点了「允许」；只完成匹配的 ticket。 */
    fun approve(ticketId: Long): Boolean = complete(
        ticketId,
        Outcome(approved = true),
    )

    /** 用户拒绝/关闭弹窗；只完成匹配的 ticket。 */
    fun reject(ticketId: Long, reason: String = "用户拒绝了这次操作，未执行任何更改。"):
        Boolean = complete(ticketId, Outcome(approved = false, reason = reason))

    /** 真正离开对应聊天路由时，拒绝此 owner 的所有待处理审批。 */
    fun rejectOwner(owner: Owner, reason: String = "聊天页面已离开，审批已取消，操作未执行。") {
        synchronized(lock) {
            queue.filter { it.ticket.ownerId == owner.id }.forEach {
                it.gate.complete(Outcome(approved = false, reason = reason))
            }
            publishHeadLocked()
        }
    }

    /** 供 JVM 测试/调用方确认 ticket 仍属于此 owner，不暴露全队列。 */
    fun hasPending(owner: Owner): Boolean = synchronized(lock) {
        queue.any { it.ticket.ownerId == owner.id && !it.gate.isCompleted }
    }

    private fun complete(ticketId: Long, outcome: Outcome): Boolean = synchronized(lock) {
        val entry = queue.firstOrNull { it.ticket.id == ticketId } ?: return@synchronized false
        val changed = entry.gate.complete(outcome)
        publishHeadLocked()
        changed
    }

    private fun publishHeadLocked() {
        _pending.value = queue.firstOrNull { !it.gate.isCompleted }?.ticket
        ownerPending.forEach { (ownerId, flow) ->
            flow.value = queue.firstOrNull { it.ticket.ownerId == ownerId && !it.gate.isCompleted }?.ticket
        }
    }

    /** 确认弹窗超时时间：足够读完代码摘要，又不至于让整轮永久挂起。 */
    private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
}
