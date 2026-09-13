package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * LazyColumn 长按拖拽排序状态。条目 key 必须唯一且稳定（如实体 id）。
 * onMove 收到「被拖条目 key → 目标条目 key」，由调用方在数据里换位并持久化；
 * onDragStart（可选）在拖动开始时回调，可用于拖动前的预处理（如展开条目先折叠）。
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDragStarted: ((key: Any) -> Unit)? = null,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set

    // 拖拽起点布局 offset 与累计位移，二者相减得到跟手 translationY
    private var startOffset = 0
    private var totalDelta = 0f

    private val draggingItem
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** 拖拽条目相对当前布局位置的偏移 */
    val draggingOffset: Float
        get() = draggingItem?.let { startOffset + totalDelta - it.offset } ?: 0f

    internal fun onDragStart(key: Any) {
        draggingKey = key
        totalDelta = 0f
        startOffset = draggingItem?.offset ?: 0
        onDragStarted?.invoke(key)
    }

    internal fun onDrag(delta: Float) {
        if (draggingKey == null) return
        totalDelta += delta
        val item = draggingItem ?: return
        val visualTop = startOffset + totalDelta
        val middle = visualTop + item.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            info.key != item.key && middle.toInt() in info.offset until (info.offset + info.size)
        } ?: return
        onMove(item.key, target.key)
    }

    internal fun onDragEnd() {
        draggingKey = null
        totalDelta = 0f
        startOffset = 0
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    /** 拖动开始回调（可选）：如展开中的条目先折叠再拖动 */
    onDragStart: ((key: Any) -> Unit)? = null,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
): ReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    return remember(listState) {
        ReorderState(listState, { from, to -> currentOnMove(from, to) }) { key ->
            currentOnDragStart?.invoke(key)
        }
    }
}

/** 拖拽手柄手势：长按后拖动。加在手柄图标所在的 Modifier 上 */
fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = composed {
    pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.onDragStart(key) },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount.y)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragEnd() },
        )
    }
}

/** 拖拽中的条目跟手位移并置顶显示。加在 LazyColumn item 根 Modifier 上 */
fun Modifier.reorderDragged(state: ReorderState, key: Any): Modifier = composed {
    if (state.draggingKey == key) {
        zIndex(1f).graphicsLayer { translationY = state.draggingOffset }
    } else {
        this
    }
}
