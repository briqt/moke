package com.briqt.moke.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 长按拖动重排列表（无第三方库，适配少量卡片场景）。
 *
 * 交互：长按 [itemContent] 提供的 `handle` 修饰符所在元素开始拖动；拖动中在本地即时重排（视觉跟手），
 * 松手时回调 [onReorder] 交最终顺序。外部 [items] 变化时（非拖动中）同步到内部工作副本。
 *
 * 目标索引：以被拖项当前布局偏移 + 累计位移求其中线，命中哪个可见项就换到那一格（逐格相邻交换，
 * 松手前不落库）。换格后校正累计位移，使被拖项保持在手指下。
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 10.dp,
    itemContent: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    var working by remember { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    // 外部数据变化时同步（拖动中不打断，避免跟手抖动）。
    LaunchedEffect(items) { if (draggingKey == null) working = items }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        itemsIndexed(working, key = { _, it -> key(it) }) { _, item ->
            val itemKey = key(item)
            val isDragging = itemKey == draggingKey
            val dragMod = if (isDragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = accumulated }
            } else Modifier
            Box(modifier = dragMod) {
                val handle = Modifier.pointerInput(itemKey) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggingKey = itemKey; accumulated = 0f },
                        onDragEnd = {
                            draggingKey = null
                            accumulated = 0f
                            onReorder(working)
                        },
                        onDragCancel = { draggingKey = null; accumulated = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            accumulated += amount.y
                            val info = listState.layoutInfo
                            val dragInfo = info.visibleItemsInfo.firstOrNull { it.key == draggingKey }
                                ?: return@detectDragGesturesAfterLongPress
                            val middle = dragInfo.offset + accumulated + dragInfo.size / 2f
                            val target = info.visibleItemsInfo.firstOrNull { vi ->
                                vi.key != draggingKey && middle.toInt() in vi.offset..(vi.offset + vi.size)
                            } ?: return@detectDragGesturesAfterLongPress
                            val from = working.indexOfFirst { key(it) == draggingKey }
                            val to = target.index
                            if (from != -1 && to != -1 && from != to) {
                                working = working.toMutableList().apply { add(to, removeAt(from)) }
                                // 换格后被拖项将落到 target 的位置：校正累计位移保持跟手连续。
                                accumulated += (dragInfo.offset - target.offset)
                            }
                        },
                    )
                }
                itemContent(item, isDragging, handle)
            }
        }
    }
}
