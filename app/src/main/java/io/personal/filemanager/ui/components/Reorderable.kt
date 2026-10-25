package io.personal.filemanager.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex

/**
 * Небольшой список с перетаскиванием за «ручку». Подходит для коротких списков (плитки, вкладки).
 * Для TalkBack на ручке есть действия «Переместить выше/ниже».
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, index: Int, dragHandle: Modifier, isDragging: Boolean) -> Unit,
) {
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentKeyOf by rememberUpdatedState(keyOf)

    Column(modifier) {
        items.forEachIndexed { index, item ->
            val itemKey = keyOf(item)
            key(itemKey) {
                val dragging = draggingKey == itemKey
                val handle = Modifier
                    .pointerInput(itemKey) {
                        detectDragGestures(
                            onDragStart = {
                                draggingKey = itemKey
                                offset = 0f
                            },
                            onDragEnd = {
                                draggingKey = null
                                offset = 0f
                            },
                            onDragCancel = {
                                draggingKey = null
                                offset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val list = currentItems
                                val i = list.indexOfFirst { currentKeyOf(it) == itemKey }
                                if (i >= 0 && offset > 0 && i < list.lastIndex) {
                                    val h = heights[currentKeyOf(list[i + 1])] ?: 0
                                    if (h > 0 && offset > h / 2f) {
                                        currentOnMove(i, i + 1)
                                        offset -= h
                                    }
                                } else if (i > 0 && offset < 0) {
                                    val h = heights[currentKeyOf(list[i - 1])] ?: 0
                                    if (h > 0 && -offset > h / 2f) {
                                        currentOnMove(i, i - 1)
                                        offset += h
                                    }
                                }
                            },
                        )
                    }
                    .semantics {
                        customActions = buildList {
                            if (index > 0) {
                                add(CustomAccessibilityAction("Переместить выше") { currentOnMove(index, index - 1); true })
                            }
                            if (index < items.lastIndex) {
                                add(CustomAccessibilityAction("Переместить ниже") { currentOnMove(index, index + 1); true })
                            }
                        }
                    }
                Box(
                    Modifier
                        .onSizeChanged { heights[itemKey] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) offset else 0f
                            shadowElevation = if (dragging) 16f else 0f
                        },
                ) {
                    itemContent(item, index, handle, dragging)
                }
            }
        }
    }
}

/** Перемещение элемента списка (общая логика для плиток и вкладок). */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val result = toMutableList()
    val item = result.removeAt(from)
    result.add(to, item)
    return result
}
