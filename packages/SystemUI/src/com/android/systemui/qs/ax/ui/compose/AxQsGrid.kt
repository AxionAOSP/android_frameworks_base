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

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.padding
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalTileScale

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun <T> AxQsGrid(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    rowHeight: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    maxRows: Int? = null,
    squareCells: Boolean = false,
    minimumRows: Int = 0,
    animateItemBounds: Boolean = false,
    staticItemId: String? = null,
    onItemBounds: (String, Rect) -> Unit = { _, _ -> },
    onCells: ((List<AxQsGridCell>) -> Unit)? = null,
    content: @Composable (AxQsGridItem<T>) -> Unit,
) {
    require(columns > 0)
    val itemSnapshot = items.toList()
    val placements =
        remember(itemSnapshot, columns, maxRows) { packItems(itemSnapshot, columns, maxRows) }
    LookaheadScope {
        val lookaheadScope = this
        Layout(
            content = {
                placements.forEach { placement ->
                    key(placement.item.id) {
                        val itemModifier =
                            if (animateItemBounds && placement.item.id != staticItemId) {
                                Modifier.animateBounds(lookaheadScope)
                            } else {
                                Modifier
                            }
                        Box(itemModifier.fillMaxSize()) { content(placement.item) }
                    }
                }
            },
            modifier = modifier,
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val horizontalSpacingPx = spacing.roundToPx()
            val verticalSpacingPx = horizontalSpacingPx
            val availableWidth = (width - horizontalSpacingPx * (columns - 1)).coerceAtLeast(0)
            val rowHeightPx = if (squareCells) availableWidth / columns else rowHeight.roundToPx()

            fun columnStart(column: Int): Int {
                return availableWidth * column / columns + horizontalSpacingPx * column
            }

            val placeables =
                measurables.mapIndexed { index, measurable ->
                    val placement = placements[index]
                    val start = columnStart(placement.column)
                    val end =
                        columnStart(placement.column + placement.item.span.columns) -
                            horizontalSpacingPx
                    val itemWidth = (end - start).coerceAtLeast(0)
                    val itemHeight =
                        rowHeightPx * placement.item.span.rows +
                            verticalSpacingPx * (placement.item.span.rows - 1)
                    val top = placement.row * (rowHeightPx + verticalSpacingPx)
                    onItemBounds(
                        placement.item.id,
                        Rect(
                            left = start.toFloat(),
                            top = top.toFloat(),
                            right = (start + itemWidth).toFloat(),
                            bottom = (top + itemHeight).toFloat(),
                        ),
                    )
                    measurable.measure(Constraints.fixed(itemWidth, itemHeight))
                }
            val rows =
                maxOf(placements.maxOfOrNull { it.row + it.item.span.rows } ?: 0, minimumRows)
            onCells?.invoke(
                buildList {
                    repeat(rows) { row ->
                        repeat(columns) { column ->
                            val left = columnStart(column)
                            val top = row * (rowHeightPx + verticalSpacingPx)
                            add(
                                AxQsGridCell(
                                    position = AxQsGridPosition(column, row),
                                    bounds =
                                        Rect(
                                            left = left.toFloat(),
                                            top = top.toFloat(),
                                            right =
                                                (columnStart(column + 1) - horizontalSpacingPx)
                                                    .toFloat(),
                                            bottom = (top + rowHeightPx).toFloat(),
                                        ),
                                )
                            )
                        }
                    }
                }
            )
            val height =
                (rowHeightPx * rows + verticalSpacingPx * (rows - 1).coerceAtLeast(0)).coerceIn(
                    constraints.minHeight,
                    constraints.maxHeight,
                )

            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    val placement = placements[index]
                    placeable.placeRelative(
                        x = columnStart(placement.column),
                        y = placement.row * (rowHeightPx + verticalSpacingPx),
                    )
                }
            }
        }
    }
}

internal data class AxQsGridCell(val position: AxQsGridPosition, val bounds: Rect)

internal fun axQsGridCellWidth(gridWidth: Dp, columns: Int, spacing: Dp): Dp =
    (gridWidth - spacing * (columns - 1)) / columns

private data class AxQsPlacement<T>(val item: AxQsGridItem<T>, val column: Int, val row: Int)

private fun <T> packItems(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    maxRows: Int?,
): List<AxQsPlacement<T>> {
    val occupied = mutableListOf<BooleanArray>()
    val placements = mutableMapOf<String, AxQsPlacement<T>>()

    fun place(item: AxQsGridItem<T>, column: Int, row: Int): AxQsPlacement<T>? {
        val width = item.span.columns.coerceAtMost(columns)
        val height = item.span.rows
        if (column < 0 || column + width > columns || row < 0) return null
        if (maxRows != null && row + height > maxRows) return null
        val fits =
            (row until row + height).all { candidateRow ->
                (column until column + width).all { candidateColumn ->
                    occupied.getOrNull(candidateRow)?.get(candidateColumn) != true
                }
            }
        if (!fits) return null
        while (occupied.size < row + height) {
            occupied.add(BooleanArray(columns))
        }
        for (occupiedRow in row until row + height) {
            for (occupiedColumn in column until column + width) {
                occupied[occupiedRow][occupiedColumn] = true
            }
        }
        return AxQsPlacement(
            item = item.copy(span = AxQsSpan(width, height)),
            column = column,
            row = row,
        )
    }

    items.forEach { item ->
        item.position?.let { position ->
            place(item, position.column, position.row)?.let { placements[item.id] = it }
        }
    }
    items.forEach { item ->
        if (item.id in placements) return@forEach
        val width = item.span.columns.coerceAtMost(columns)
        var row = 0
        while (maxRows == null || row + item.span.rows <= maxRows) {
            val placement =
                (0..columns - width).firstNotNullOfOrNull { column -> place(item, column, row) }
            if (placement != null) {
                placements[item.id] = placement
                break
            }
            row++
        }
    }
    return items.mapNotNull { placements[it.id] }
}

internal fun <T> fitAxQsGridItems(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    maxRows: Int,
): List<AxQsGridItem<T>> = packItems(items, columns, maxRows).map { it.item }

internal fun <T> canFitAxQsGridItems(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    maxRows: Int,
): Boolean = packItems(items, columns, maxRows).size == items.size

internal fun <T> axQsGridRowCount(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    maxRows: Int?,
): Int = packItems(items, columns, maxRows).maxOfOrNull { it.row + it.item.span.rows } ?: 0

@Composable
internal fun <T> AxQsTileGrid(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    rows: Int,
    spacing: Dp,
    showLabels: Boolean,
    circleCells: Boolean,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    content: @Composable (AxQsGridItem<T>) -> Unit,
    label: @Composable (AxQsGridItem<T>) -> Unit = {},
) {
    val pages = remember(items, columns, rows) { items.chunked(columns * rows) }
    val pageCount = pages.size.coerceAtLeast(1)
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage(pageCount - 1)
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = axQsGridCellWidth(maxWidth, columns, spacing)
        val aospTileHeight = CommonTileDefaults.TileHeight * LocalTileScale.current
        val tileHeight = if (circleCells) cellWidth else aospTileHeight
        val itemHeight = tileHeight + if (showLabels) AX_TILE_LABEL_HEIGHT else 0.dp
        val pageHeight = itemHeight * rows + spacing * (rows - 1)
        val pagerPadding = if (pageCount > 1) spacing else 0.dp
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier.fillMaxWidth()
                    .height(pageHeight)
                    .padding(horizontal = { -pagerPadding.roundToPx() }),
            contentPadding = PaddingValues(horizontal = pagerPadding),
            beyondViewportPageCount = 1,
            pageSpacing = pagerPadding,
            verticalAlignment = Alignment.Top,
            overscrollEffect = null,
        ) { page ->
            val pageItems = pages.getOrNull(page).orEmpty()
            AxQsGrid(
                items = pageItems,
                columns = columns,
                rowHeight = itemHeight,
                spacing = spacing,
                squareCells = false,
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(tileHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        content(item)
                    }
                    if (showLabels) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(AX_TILE_LABEL_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            label(item)
                        }
                    }
                }
            }
        }
    }
}

internal fun axQsTileGridPageCount(itemCount: Int, columns: Int, rows: Int): Int {
    return ((itemCount + columns * rows - 1) / (columns * rows)).coerceAtLeast(1)
}

internal fun useAxQsCircleCells(
    gridWidth: Dp,
    tileColumns: Int,
    spacing: Dp,
    allowCircles: Boolean,
): Boolean =
    allowCircles &&
        axQsGridCellWidth(gridWidth, tileColumns, spacing) <= AX_TILE_MAX_SIZE

internal fun axQsTileIconSize(tileSize: Dp): Dp =
    CommonTileDefaults.IconSize * (tileSize / CommonTileDefaults.TileHeight).coerceAtLeast(1f)

private val AX_TILE_LABEL_HEIGHT = 24.dp
private val AX_TILE_MAX_SIZE = 85.dp
