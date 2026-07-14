package com.briqt.moke.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** 一个分组：稳定的组键 + 组内有序条目。 */
data class ReorderGroup<T>(val key: String, val items: List<T>)

/**
 * 可折叠 + 两级长按拖动的分组列表（无第三方库，适配少量卡片场景）。
 *
 * 交互：
 *  - 长按**分组头**的 `handle` 拖动 → 调整分组之间的顺序（整块移动，松手回调 [onReorderGroups] 交新组键序）。
 *  - 长按**组内条目**的 `handle` 拖动 → 仅在本组内重排（不跨组；松手回调 [onReorderItems] 交该组新条目序）。
 *  - [collapsed] 内的组只渲染组头、隐藏条目。
 *
 * 拖动中在本地工作副本即时重排（视觉跟手），松手落库。外部数据变化时（非拖动中）同步到工作副本。
 */
@Composable
fun <T> GroupedReorderableList(
    groups: List<ReorderGroup<T>>,
    itemKey: (T) -> Any,
    collapsed: Set<String>,
    onReorderGroups: (List<String>) -> Unit,
    onReorderItems: (group: String, items: List<T>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 10.dp,
    header: @Composable (group: String, dragging: Boolean, handle: Modifier) -> Unit,
    itemContent: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    var working by remember { mutableStateOf(groups) }
    var dragKey by remember { mutableStateOf<Any?>(null) }
    var dragIsGroup by remember { mutableStateOf(false) }
    var dragGroup by remember { mutableStateOf<String?>(null) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    fun reset() { dragKey = null; dragIsGroup = false; dragGroup = null; accumulated = 0f }

    LaunchedEffect(groups) { if (dragKey == null) working = groups }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        working.forEach { group ->
            // LazyColumn 的 key 必须可进 Bundle（String/Int/Parcelable）→ 分组头用带前缀的 String，
            // 与条目键（主机 id 的 UUID）区分；不可用 data class 作 key（会抛 IllegalArgumentException）。
            val headerKey = headerKeyOf(group.key)
            item(key = headerKey) {
                val isDragging = headerKey == dragKey
                val dragMod = if (isDragging) Modifier.zIndex(1f).graphicsLayer { translationY = accumulated } else Modifier
                Box(modifier = dragMod) {
                    val handle = Modifier.pointerInput(headerKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragKey = headerKey; dragIsGroup = true; dragGroup = group.key; accumulated = 0f },
                            onDragEnd = { onReorderGroups(working.map { it.key }); reset() },
                            onDragCancel = { reset() },
                            onDrag = { change, amount ->
                                change.consume()
                                accumulated += amount.y
                                val info = listState.layoutInfo
                                val dragInfo = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
                                    ?: return@detectDragGesturesAfterLongPress
                                val middle = dragInfo.offset + accumulated + dragInfo.size / 2f
                                // 只在「其他分组头」之间寻找落点（整块移动）。
                                val target = info.visibleItemsInfo.firstOrNull { vi ->
                                    isHeaderKey(vi.key) && vi.key != dragKey && middle.toInt() in vi.offset..(vi.offset + vi.size)
                                } ?: return@detectDragGesturesAfterLongPress
                                val targetGroup = groupOfHeaderKey(target.key)
                                val from = working.indexOfFirst { it.key == dragGroup }
                                val to = working.indexOfFirst { it.key == targetGroup }
                                if (from != -1 && to != -1 && from != to) {
                                    working = working.toMutableList().apply { add(to, removeAt(from)) }
                                    accumulated += (dragInfo.offset - target.offset)
                                }
                            },
                        )
                    }
                    header(group.key, isDragging, handle)
                }
            }
            if (group.key !in collapsed) {
                items(group.items, key = { itemKey(it) }) { item ->
                    val itemK = itemKey(item)
                    val isDragging = itemK == dragKey
                    val dragMod = if (isDragging) Modifier.zIndex(1f).graphicsLayer { translationY = accumulated } else Modifier
                    Box(modifier = dragMod) {
                        val handle = Modifier.pointerInput(itemK) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragKey = itemK; dragIsGroup = false; dragGroup = group.key; accumulated = 0f },
                                onDragEnd = {
                                    val g = working.firstOrNull { it.key == dragGroup }
                                    if (g != null) onReorderItems(g.key, g.items)
                                    reset()
                                },
                                onDragCancel = { reset() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    accumulated += amount.y
                                    val info = listState.layoutInfo
                                    val dragInfo = info.visibleItemsInfo.firstOrNull { it.key == dragKey }
                                        ?: return@detectDragGesturesAfterLongPress
                                    val gi = working.indexOfFirst { it.key == dragGroup }
                                    if (gi == -1) return@detectDragGesturesAfterLongPress
                                    val g = working[gi]
                                    val siblingKeys = g.items.map { itemKey(it) }.toSet()
                                    val middle = dragInfo.offset + accumulated + dragInfo.size / 2f
                                    // 只在「同组其他条目」之间寻找落点 → 保证仅组内重排、不跨组。
                                    val target = info.visibleItemsInfo.firstOrNull { vi ->
                                        vi.key != dragKey && vi.key in siblingKeys && middle.toInt() in vi.offset..(vi.offset + vi.size)
                                    } ?: return@detectDragGesturesAfterLongPress
                                    val from = g.items.indexOfFirst { itemKey(it) == dragKey }
                                    val to = g.items.indexOfFirst { itemKey(it) == target.key }
                                    if (from != -1 && to != -1 && from != to) {
                                        val newItems = g.items.toMutableList().apply { add(to, removeAt(from)) }
                                        working = working.toMutableList().apply { set(gi, g.copy(items = newItems)) }
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
    }
}

// 分组头在 LazyColumn 里的稳定 String 键：带控制符前缀，绝不与条目键（主机 id 的 UUID）冲突，且可进 Bundle。
private const val GROUP_HEADER_PREFIX = " moke-hdr "
private fun headerKeyOf(group: String): String = GROUP_HEADER_PREFIX + group
private fun isHeaderKey(key: Any?): Boolean = key is String && key.startsWith(GROUP_HEADER_PREFIX)
private fun groupOfHeaderKey(key: Any?): String = (key as String).removePrefix(GROUP_HEADER_PREFIX)
