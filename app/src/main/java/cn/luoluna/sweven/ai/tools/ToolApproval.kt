package cn.luoluna.sweven.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 一次「执行前需确认」的描述：标题一行，详情给用户看关键信息（参数/代码摘要/影响面） */
data class ApprovalRequest(
    val title: String,
    val detail: String,
)

/**
 * 执行前审批闸门（原生层）。
 *
 * 工具执行到需要用户点头的时刻调用 [request]，它会**挂起**当前工具调用并对外暴露 [pending]；
 * 聊天页把 [pending] 渲染成确认弹窗，用户点「允许/拒绝」后调用 [approve]/[reject] 完成挂起，
 * 返回 true/false 给工具侧决定继续还是返回错误文本。
 *
 * - 同时只允许一个待确认项（[Mutex] 串行化）：模型同一轮返回多个写操作调用时逐个确认。
 * - 超时（默认 5 分钟）自动视为拒绝，避免用户离开页面后整轮永远卡住。
 * - 无人应答（例如局域网请求不走本地工具）不会出现待确认项，因此不会阻塞。
 */
object ToolApproval {

    data class Pending(
        val toolName: String,
        val displayName: String,
        val title: String,
        val detail: String,
    )

    private val lock = Mutex()

    private val _pending = MutableStateFlow<Pending?>(null)

    /** 待确认项；非空时聊天页显示确认弹窗 */
    val pending: StateFlow<Pending?> = _pending

    @Volatile
    private var waiter: CompletableDeferred<Boolean>? = null

    /** 请求用户确认：挂起直到允许/拒绝/超时（超时按拒绝处理） */
    suspend fun request(
        toolName: String,
        displayName: String,
        request: ApprovalRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = lock.withLock {
        val gate = CompletableDeferred<Boolean>()
        waiter = gate
        _pending.value = Pending(
            toolName = toolName,
            displayName = displayName,
            title = request.title,
            detail = request.detail,
        )
        try {
            withTimeoutOrNull(timeoutMs) { gate.await() } == true
        } finally {
            waiter = null
            _pending.value = null
        }
    }

    /** 用户点了「允许」 */    fun approve() {
        waiter?.complete(true)
    }

    /** 用户点了「拒绝」或关掉弹窗 */
    fun reject() {
        waiter?.complete(false)
    }

    /** 确认弹窗超时时间：足够读完代码摘要，又不至于让整轮永久挂起 */
    private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
}
