package cn.luoluna.sweven.ai.net

/**
 * 上下文 token 预算的粗估与裁剪。
 * 不接真 tokenizer，用启发式估算（中日韩 1 字符 ≈ 1 token，其余约 4 字符 ≈ 1 token），
 * 只服务于「长会话防撞模型窗口」的用量显示与截断，量级正确即可。
 */

/** 每条消息的固定开销（role 等包裹结构） */
private const val PER_MESSAGE_OVERHEAD = 4

/** 单张图片的粗估 token（1024px JPEG patch 化的常见量级） */
private const val TOKENS_PER_IMAGE = 1024

/** 文本的粗估 token 数 */
fun estimateTextTokens(text: String): Int {
    var cjk = 0
    var other = 0
    for (ch in text) {
        val code = ch.code
        if (code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
        ) {
            cjk++
        } else {
            other++
        }
    }
    return cjk + (other + 3) / 4
}

/** 一条聊天消息（角色, 内容, 图片列表）的粗估 token */
fun estimateMessageTokens(content: String, imageCount: Int): Int =
    PER_MESSAGE_OVERHEAD + estimateTextTokens(content) + imageCount * TOKENS_PER_IMAGE

/** 裁剪结果：截断后的历史 + 被整丢弃的消息条数 + 被降级为占位文本的图片条数 */
data class TrimmedHistory(
    val items: List<ChatMessageInput>,
    val droppedCount: Int,
    val strippedImageCount: Int,
)

/**
 * 把历史裁剪进 token 预算，尽量保留最新消息：
 * 1. 从最早的消息开始把图片降级为「[图片]」占位文本（图片是开销大头，先砍图再砍话）；
 * 2. 仍超预算则从头部整条丢弃最早的消息。
 * 最后一条（用户刚发的）永远保留，其图片也不降级。
 */
fun trimToBudget(
    history: List<ChatMessageInput>,
    budgetTokens: Int,
): TrimmedHistory {
    fun total(items: List<ChatMessageInput>): Int =
        items.sumOf { estimateMessageTokens(it.content, it.images.size) }

    if (total(history) <= budgetTokens) return TrimmedHistory(history, 0, 0)

    // 第一步：早期消息的图片降级为占位文本
    val stripped = history.toMutableList()
    var strippedImages = 0
    for (i in stripped.indices) {
        if (total(stripped) <= budgetTokens) break
        if (i >= stripped.lastIndex) break
        val item = stripped[i]
        if (item.images.isNotEmpty()) {
            val note = if (item.content.isBlank()) {
                "[图片×${item.images.size}]"
            } else {
                "${item.content}\n[图片×${item.images.size}]"
            }
            stripped[i] = item.copy(content = note, images = emptyList())
            strippedImages += item.images.size
        }
    }
    if (total(stripped) <= budgetTokens) return TrimmedHistory(stripped, 0, strippedImages)

    // 第二步：从头部整条丢弃最早的消息
    val rest = stripped.toMutableList()
    var dropped = 0
    while (rest.size > 1 && total(rest) > budgetTokens) {
        rest.removeAt(0)
        dropped++
    }
    return TrimmedHistory(rest, dropped, strippedImages)
}
