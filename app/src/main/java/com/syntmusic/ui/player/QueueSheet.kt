package com.syntmusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.syntmusic.playback.PlayerState
import com.syntmusic.playback.QueueItem
import com.syntmusic.ui.TrackRow
import com.syntmusic.ui.theme.Palette

/**
 * Tap to play, swipe sideways to remove, drag the handle to reorder.
 * Reordering is off while shuffling: the shown order is then the shuffle order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: PlayerState,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${state.queue.size} songs", color = Palette.Secondary, style = MaterialTheme.typography.labelLarge)
        }

        val haptics = LocalHapticFeedback.current
        val reorderable = !state.shuffleEnabled
        // Local copy so rows can move under the finger before the player confirms the move.
        var items by remember(state.queue) { mutableStateOf(state.queue) }
        var draggedKey by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragFrom by remember { mutableIntStateOf(-1) }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = state.queue.indexOfFirst { it.index == state.currentIndex }.coerceAtLeast(0),
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            itemsIndexed(items, key = { _, item -> item.key }) { position, item ->
                val dragged = item.key == draggedKey
                val isCurrent = item.index == state.currentIndex
                val currentPosition by rememberUpdatedState(position)
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled && !isCurrent) {
                            onRemove(item.index)
                            true
                        } else {
                            false
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = !isCurrent && !dragged,
                    enableDismissFromEndToStart = !isCurrent && !dragged,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(RemoveColor).padding(horizontal = 24.dp),
                            contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            },
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = Palette.Primary)
                        }
                    },
                    modifier = Modifier
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) dragOffset else 0f
                            val scale = if (dragged) 1.03f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .then(if (dragged) Modifier else Modifier.animateItem()),
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(if (dragged) Palette.SurfaceHigh else Palette.Surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(
                            track = item.track,
                            isCurrent = isCurrent,
                            onClick = { onSelect(item.index) },
                            modifier = Modifier.weight(1f),
                        )
                        if (reorderable) {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "Reorder",
                                tint = Palette.Secondary,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(40.dp)
                                    .padding(8.dp)
                                    .pointerInput(item.key) {
                                        detectDragGestures(
                                            onDragStart = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggedKey = item.key
                                                dragFrom = currentPosition
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                val to = items.indexOfFirst { it.key == draggedKey }
                                                if (dragFrom >= 0 && to >= 0 && to != dragFrom) onMove(dragFrom, to)
                                                draggedKey = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggedKey = null
                                                dragOffset = 0f
                                            },
                                        ) { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            items = swapUnderFinger(items, draggedKey, listState, dragOffset) { shift ->
                                                dragOffset += shift
                                            }
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val RemoveColor = Color(0xFF5A1F1F)

/** Moves the dragged row past the row under its center, compensating the visual offset. */
private fun swapUnderFinger(
    items: List<QueueItem>,
    draggedKey: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    dragOffset: Float,
    onShift: (Float) -> Unit,
): List<QueueItem> {
    val visible = listState.layoutInfo.visibleItemsInfo
    val current = visible.firstOrNull { it.key == draggedKey } ?: return items
    val center = current.offset + dragOffset + current.size / 2f
    val target = visible.firstOrNull {
        it.key != draggedKey && center > it.offset && center < it.offset + it.size
    } ?: return items
    val from = items.indexOfFirst { it.key == draggedKey }
    if (from < 0 || target.index !in items.indices) return items
    onShift((current.offset - target.offset).toFloat())
    return items.toMutableList().apply { add(target.index, removeAt(from)) }
}
