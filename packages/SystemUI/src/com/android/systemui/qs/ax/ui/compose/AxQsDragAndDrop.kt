/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.ax.ui.compose

import android.content.ClipData
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import kotlin.math.abs

internal class AxQsEditListState<T>(initialItems: List<AxQsGridItem<T>>) {
    private val itemBounds = mutableMapOf<String, Pair<AxQsGridSection, Rect>>()
    private val gridCells = mutableMapOf<AxQsGridSection, List<AxQsGridCell>>()
    private val gridOrigins = mutableMapOf<AxQsGridSection, Offset>()
    private val _items: SnapshotStateList<AxQsGridItem<T>> = initialItems.toMutableStateList()
    private var dragSnapshot: List<AxQsGridItem<T>>? = null
    private var dragAnchor = Offset.Zero

    val items: List<AxQsGridItem<T>> = _items

    var draggedId by mutableStateOf<String?>(null)
        private set

    var draggedPosition by mutableStateOf(Offset.Unspecified)
        private set

    val dragInProgress: Boolean
        get() = draggedId != null

    var resizeInProgress by mutableStateOf(false)
        private set

    fun updateItems(items: List<AxQsGridItem<T>>) {
        _items.clear()
        _items.addAll(items)
        itemBounds.keys.retainAll(items.mapTo(mutableSetOf()) { it.id })
    }

    fun item(id: String): AxQsGridItem<T>? {
        return _items.firstOrNull { it.id == id }
    }

    fun add(item: AxQsGridItem<T>) {
        _items.add(item)
    }

    fun repack(ids: Set<String>) {
        _items.indices.forEach { index ->
            val item = _items[index]
            if (item.id in ids && item.position != null) {
                _items[index] = item.copy(position = null)
            }
        }
        ids.forEach { id -> itemBounds.remove(id) }
    }

    fun beginResize() {
        resizeInProgress = true
    }

    fun endResize() {
        resizeInProgress = false
    }

    fun remove(id: String): Boolean {
        itemBounds.remove(id)
        return _items.removeAll { it.id == id }
    }

    fun update(id: String, transform: (AxQsGridItem<T>) -> AxQsGridItem<T>): Boolean {
        val index = indexOf(id)
        if (index < 0) return false
        _items[index] = transform(_items[index])
        return true
    }

    fun moveBy(id: String, delta: Int, sectionOf: (AxQsGridItem<T>) -> AxQsGridSection): Boolean {
        val item = item(id) ?: return false
        val sectionItems = _items.filter { sectionOf(it) == sectionOf(item) }
        val from = sectionItems.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, sectionItems.lastIndex)
        if (from == to) return false
        val targetId = sectionItems[to].id
        val sourceIndex = indexOf(id)
        val targetIndex = indexOf(targetId)
        _items.add(targetIndex, _items.removeAt(sourceIndex))
        return true
    }

    fun start(id: String, anchor: Offset) {
        check(!dragInProgress)
        check(indexOf(id) >= 0)
        dragSnapshot = _items.toList()
        capturePositions()
        val bounds = itemBounds[id]?.second
        dragAnchor =
            if (bounds != null) {
                Offset(
                    x = anchor.x.coerceIn(0f, bounds.width),
                    y = anchor.y.coerceIn(0f, bounds.height),
                )
            } else {
                anchor
            }
        draggedId = id
        draggedPosition = Offset.Unspecified
    }

    fun updateItemBounds(id: String, section: AxQsGridSection, bounds: Rect) {
        itemBounds[id] = section to bounds
    }

    fun updateGridOrigin(section: AxQsGridSection, origin: Offset) {
        gridOrigins[section] = origin
    }

    fun updateGridCells(section: AxQsGridSection, cells: List<AxQsGridCell>) {
        gridCells[section] = cells
    }

    fun onMoved(offset: Offset) {
        draggedPosition = offset
    }

    fun findItemAt(offset: Offset, section: AxQsGridSection): Pair<String, Rect>? {
        val localOffset = offsetInGrid(offset, section)
        return itemBounds.entries
            .asSequence()
            .filter {
                it.key != draggedId &&
                    it.value.first == section &&
                    it.value.second.contains(localOffset)
            }
            .map { it.key to it.value.second }
            .minByOrNull { (_, bounds) -> (bounds.center - localOffset).getDistanceSquared() }
    }

    fun offsetInGrid(offset: Offset, section: AxQsGridSection): Offset {
        return offset - gridOrigins.getOrDefault(section, Offset.Zero)
    }

    fun moveToCell(offset: Offset, section: AxQsGridSection): Boolean {
        val id = draggedId ?: return false
        val index = indexOf(id)
        if (index < 0) return false
        val item = _items[index]
        val cells = gridCells[section] ?: return false
        val localOffset = offsetInGrid(offset, section)
        val itemTopLeft = localOffset - dragAnchor
        val columns = cells.maxOfOrNull { it.position.column }?.plus(1) ?: return false
        val rows = cells.maxOfOrNull { it.position.row }?.plus(1) ?: return false
        val start =
            cells
                .asSequence()
                .filter { cell ->
                    cell.position.column + item.span.columns <= columns &&
                        cell.position.row + item.span.rows <= rows
                }
                .minByOrNull { cell ->
                    (cell.bounds.topLeft - itemTopLeft).getDistanceSquared()
                } ?: return false
        if (item.position == start.position) return true
        val sectionIds =
            itemBounds
                .filterValues { (itemSection, _) -> itemSection == section }
                .keys + id
        fun overlapsTarget(current: AxQsGridItem<T>): Boolean {
            val position = current.position ?: return false
            return position.column < start.position.column + item.span.columns &&
                start.position.column < position.column + current.span.columns &&
                position.row < start.position.row + item.span.rows &&
                start.position.row < position.row + current.span.rows
        }
        var movedItems =
            _items.map { current ->
                when {
                    current.id == id -> current.copy(position = start.position)
                    current.id in sectionIds && overlapsTarget(current) ->
                        current.copy(position = null)
                    else -> current
                }
            }
        if (!canFitAxQsGridItems(movedItems.filter { it.id in sectionIds }, columns, rows)) {
            movedItems =
                movedItems.map { current ->
                    if (current.id in sectionIds && current.id != id) {
                        current.copy(position = null)
                    } else {
                        current
                    }
                }
            if (!canFitAxQsGridItems(movedItems.filter { it.id in sectionIds }, columns, rows)) {
                return false
            }
        }
        movedItems.forEachIndexed { itemIndex, movedItem ->
            if (_items[itemIndex] != movedItem) {
                _items[itemIndex] = movedItem
            }
        }
        return true
    }

    fun moveToSection(
        section: AxQsGridSection,
        sectionOf: (AxQsGridItem<T>) -> AxQsGridSection,
        transform: (AxQsGridItem<T>, AxQsGridSection) -> AxQsGridItem<T>?,
    ): Boolean {
        val id = draggedId ?: return false
        val from = indexOf(id)
        if (from < 0) return false
        val item = _items[from]
        if (sectionOf(item) == section) return true
        val moved = transform(item, section) ?: return false
        _items.removeAt(from)
        val destination = _items.indexOfLast { sectionOf(it) == section } + 1
        _items.add(destination.coerceAtLeast(0), moved)
        return true
    }

    fun onTargeting(id: String, insertAfter: Boolean?) {
        val sourceId = draggedId ?: return
        if (sourceId == id) return

        val from = indexOf(sourceId)
        val target = indexOf(id)
        if (from < 0 || target < 0) return

        val sourceItem = _items[from]
        val targetItem = _items[target]
        if (sourceItem.position != null && targetItem.position != null) {
            _items[from] = sourceItem.copy(position = targetItem.position)
            _items[target] = targetItem.copy(position = sourceItem.position)
            return
        }

        val targetAfterRemoval = if (from < target) target - 1 else target
        val destination = targetAfterRemoval + if (insertAfter ?: (from < target)) 1 else 0
        if (destination == from) return
        _items.add(destination.coerceIn(0, _items.lastIndex), _items.removeAt(from))
    }

    fun cancel() {
        dragSnapshot?.let(::updateItems)
        dragSnapshot = null
        dragAnchor = Offset.Zero
        draggedId = null
        draggedPosition = Offset.Unspecified
    }

    fun finish(onDrop: () -> Unit) {
        if (draggedId == null) return
        dragSnapshot = null
        dragAnchor = Offset.Zero
        draggedId = null
        draggedPosition = Offset.Unspecified
        onDrop()
    }

    fun positions(): Map<String, AxQsGridPosition> {
        capturePositions()
        return _items.mapNotNull { item -> item.position?.let { item.id to it } }.toMap()
    }

    private fun indexOf(id: String): Int {
        return _items.indexOfFirst { it.id == id }
    }

    private fun capturePositions() {
        _items.indices.forEach { index ->
            val item = _items[index]
            val (section, bounds) = itemBounds[item.id] ?: return@forEach
            val position =
                gridCells[section]
                    ?.minByOrNull { cell ->
                        (cell.bounds.topLeft - bounds.topLeft).getDistanceSquared()
                    }
                    ?.position ?: return@forEach
            if (item.position != position) {
                _items[index] = item.copy(position = position)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> Modifier.axQsDragSource(
    id: String,
    state: AxQsEditListState<T>,
    onDragStart: () -> Unit,
): Modifier {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    @Suppress("DEPRECATION")
    return dragAndDropSource(
        block = {
            detectDragGesturesAfterLongPress(
                onDrag = { _, _ -> },
                onDragStart = { anchor ->
                    state.start(id, anchor)
                    currentOnDragStart()
                    startTransfer(
                        DragAndDropTransferData(
                            ClipData(
                                AX_QS_DRAG_LABEL,
                                arrayOf(AX_QS_DRAG_MIME_TYPE),
                                ClipData.Item(id),
                            )
                        )
                    )
                },
                onDragEnd = state::cancel,
            )
        }
    )
}

@Composable
internal fun <T> Modifier.axQsDropTarget(
    state: AxQsEditListState<T>,
    section: AxQsGridSection,
    sectionOf: (AxQsGridItem<T>) -> AxQsGridSection,
    transform: (AxQsGridItem<T>, AxQsGridSection) -> AxQsGridItem<T>?,
    onDrop: () -> Unit,
): Modifier {
    val currentOnDrop by rememberUpdatedState(onDrop)
    val layoutDirection = LocalLayoutDirection.current
    val target =
        remember(state, layoutDirection) {
            object : DragAndDropTarget {
                override fun onMoved(event: DragAndDropEvent) {
                    val dragEvent = event.toAndroidDragEvent()
                    val offset = Offset(dragEvent.x, dragEvent.y)
                    state.onMoved(offset)
                    if (!state.moveToSection(section, sectionOf, transform)) return
                    if (state.moveToCell(offset, section)) return
                    val target = state.findItemAt(offset, section)
                    if (target != null) {
                        state.onTargeting(
                            id = target.first,
                            insertAfter =
                                insertAfter(
                                    bounds = target.second,
                                    span = state.item(target.first)?.span,
                                    offset = state.offsetInGrid(offset, section),
                                    layoutDirection = layoutDirection,
                                ),
                        )
                    }
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    state.finish(currentOnDrop)
                    return true
                }

                override fun onEnded(event: DragAndDropEvent) {
                    state.finish(currentOnDrop)
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { AX_QS_DRAG_MIME_TYPE in it.mimeTypes() },
        target = target,
    )
}

@Composable
internal fun <T> AxQsDragAutoScroll(
    state: AxQsEditListState<T>,
    scrollState: ScrollState,
    viewportBounds: Rect,
) {
    val scrollTarget by
        remember(state, scrollState, viewportBounds) {
            derivedStateOf {
                val position = state.draggedPosition
                if (!state.dragInProgress || !position.isSpecified || viewportBounds.isEmpty) {
                    null
                } else {
                    when {
                        position.y < viewportBounds.top + AX_QS_AUTO_SCROLL_DISTANCE &&
                            scrollState.value > 0 -> 0
                        position.y > viewportBounds.bottom - AX_QS_AUTO_SCROLL_DISTANCE &&
                            scrollState.value < scrollState.maxValue -> scrollState.maxValue
                        else -> null
                    }
                }
            }
        }
    LaunchedEffect(scrollTarget) {
        scrollTarget?.let { target ->
            val duration =
                (abs(target - scrollState.value) * AX_QS_AUTO_SCROLL_SPEED).coerceAtLeast(1)
            scrollState.animateScrollTo(
                target,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing),
            )
        }
    }
}

private fun insertAfter(
    bounds: Rect,
    span: AxQsSpan?,
    offset: Offset,
    layoutDirection: LayoutDirection,
): Boolean? {
    span ?: return null
    return when {
        span.rows > 1 -> offset.y > bounds.center.y
        span.columns > 1 ->
            if (layoutDirection == LayoutDirection.Ltr) {
                offset.x > bounds.center.x
            } else {
                offset.x < bounds.center.x
            }
        else -> null
    }
}

private const val AX_QS_DRAG_LABEL = "ax_qs_item"
private const val AX_QS_DRAG_MIME_TYPE = "axqs/item"
private const val AX_QS_AUTO_SCROLL_DISTANCE = 100
private const val AX_QS_AUTO_SCROLL_SPEED = 2
