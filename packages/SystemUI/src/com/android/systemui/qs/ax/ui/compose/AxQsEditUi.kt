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

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsControlSpans
import com.android.systemui.qs.ax.shared.model.AxQsGridLayout
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsLayout
import com.android.systemui.qs.ax.shared.model.AxQsPanelMode
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import com.android.systemui.qs.ax.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.selection.TileState
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.res.R

private sealed interface AxEditGridValue {
    val section: AxQsGridSection

    data class Tile(
        val viewModel: EditTileViewModel,
        override val section: AxQsGridSection,
    ) : AxEditGridValue

    data class Control(
        val control: AxQsControl,
        override val section: AxQsGridSection,
    ) : AxEditGridValue
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AxQsEditUi(
    editModeViewModel: EditModeViewModel,
    axQsViewModel: AxQsViewModel,
    controlPreview:
        @Composable (AxQsControl, AxQsSpan, Int, AxQsVerticalSliderStyle) -> Unit,
    onOpenPanelSettings: () -> Unit,
    animateItemBounds: Boolean,
    splitShade: Boolean,
    modifier: Modifier = Modifier,
) {
    val allTiles by editModeViewModel.tiles.collectAsStateWithLifecycle(initialValue = null)
    var editQqs by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showQqsEditor =
        !landscape && !splitShade && axQsViewModel.panelMode == AxQsPanelMode.TOGETHER

                var showResetDialog by remember { mutableStateOf(false) }

    BackHandler { editModeViewModel.stopEditing() }
    LaunchedEffect(showQqsEditor) { if (!showQqsEditor) editQqs = false }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(com.android.internal.R.string.reset)) },
            text = { Text("Reset all Quick Settings tiles to default layout?") },
            confirmButton = {
                TextButton(onClick = {
                    editModeViewModel.resetTiles()
                    showResetDialog = false
                }) { Text(stringResource(com.android.internal.R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = QuickSettingsShade.Dimensions.Padding)
                    .onGloballyPositioned { viewportBounds = it.boundsInRoot() }
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (landscape) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        FilledTonalButton(
                            onClick = onOpenPanelSettings,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.ax_qs_panel_settings))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { showResetDialog = true },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(com.android.internal.R.string.reset))
                        }
                        Button(
                            onClick = editModeViewModel::stopEditing,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.quick_settings_done))
                        }
                    }
                }

                if (showQqsEditor) {
                    PrimaryTabRow(
                        selectedTabIndex = if (editQqs) 0 else 1,
                        containerColor = Color.Transparent,
                        indicator = {},
                        divider = {},
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AxQsEditTab(
                            title = stringResource(R.string.ax_qs_top_quick_settings),
                            selected = editQqs,
                            onClick = { editQqs = true },
                        )
                        AxQsEditTab(
                            title = stringResource(R.string.ax_qs_full_quick_settings),
                            selected = !editQqs,
                            onClick = { editQqs = false },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.ax_qs_reorder_education),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }

            val layout =
                when {
                    splitShade -> AxQsLayout.SPLIT_SHADE
                    editQqs -> AxQsLayout.QQS
                    else -> AxQsLayout.QS
                }
            allTiles?.let { tiles ->
                key(layout) {
                    AxEditableGrid(
                        allTiles = tiles,
                        editModeViewModel = editModeViewModel,
                        axQsViewModel = axQsViewModel,
                        controlPreview = controlPreview,
                        layout = layout,
                        scrollState = scrollState,
                        viewportBounds = viewportBounds,
                        animateItemBounds = animateItemBounds,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AxQsEditTab(title: String, selected: Boolean, onClick: () -> Unit) {
    val containerColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    AxTileDefaults.backgroundColor()
                },
            label = "AxQsEditTabContainer",
        )
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = MaterialTheme.colorScheme.onPrimary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AxEditableGrid(
    allTiles: List<EditTileViewModel>,
    editModeViewModel: EditModeViewModel,
    axQsViewModel: AxQsViewModel,
    controlPreview:
        @Composable (AxQsControl, AxQsSpan, Int, AxQsVerticalSliderStyle) -> Unit,
    layout: AxQsLayout,
    scrollState: ScrollState,
    viewportBounds: Rect,
    animateItemBounds: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentTiles = allTiles.filter(EditTileViewModel::isCurrent)
    val qqs = layout == AxQsLayout.QQS
    val splitShade = layout == AxQsLayout.SPLIT_SHADE
    val boundedRows = qqs || splitShade
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controlGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.CONTROLS)
    val tileGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.TILES)
    val controlColumns = axQsViewModel.columns(controlGridLayout)
    val tileColumns = axQsViewModel.columns(tileGridLayout)
    val pickerControlColumns = controlColumns
    val pickerTileColumns = tileColumns
    val verticalSliderStyle: (AxQsControl) -> AxQsVerticalSliderStyle = { control ->
        axQsViewModel.verticalSliderStyle(layout, control)
    }
    val savedControlOrder = axQsViewModel.order(layout, AxQsGridSection.CONTROLS)
    val savedTileOrder = axQsViewModel.order(layout, AxQsGridSection.TILES)
    val savedSpans = axQsViewModel.spans(layout)
    val savedControlPositions = axQsViewModel.controlPositions(layout)
    val sourceItems =
        remember(
            currentTiles,
            layout,
            controlColumns,
            tileColumns,
            savedControlOrder,
            savedTileOrder,
            savedSpans,
            savedControlPositions,
        ) {
            buildEditItems(
                currentTiles = currentTiles,
                layout = layout,
                controlColumns = controlColumns,
                tileColumns = tileColumns,
                controlPositions = savedControlPositions,
                viewModel = axQsViewModel,
            )
        }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var pendingControlOrder by remember { mutableStateOf<List<String>?>(null) }
    var pendingTileOrder by remember { mutableStateOf<List<String>?>(null) }
    var pendingSpans by remember { mutableStateOf<Map<String, AxQsSpan>>(emptyMap()) }
    val listState = remember { AxQsEditListState(sourceItems) }
    val hapticFeedback = LocalHapticFeedback.current
    val saveSpan: (String, AxQsSpan) -> Unit = { id, span ->
        pendingSpans = pendingSpans + (id to span)
        axQsViewModel.setSpan(id, span, layout, controlColumns)
    }
    val saveOrders = {
        val positions = listState.positions()
        val controlOrder =
            listState.items.filter { it.section == AxQsGridSection.CONTROLS }.map { it.id }
        val tileOrder = listState.items.filter { it.section == AxQsGridSection.TILES }.map { it.id }
        val controlIds = controlOrder.toSet()
        listState.items
            .filter { it.section == AxQsGridSection.CONTROLS }
            .forEach { item ->
                if (savedSpans[item.id] != item.span && pendingSpans[item.id] != item.span) {
                    saveSpan(item.id, item.span)
                }
            }
        pendingControlOrder = controlOrder
        pendingTileOrder = tileOrder
        axQsViewModel.setOrder(controlOrder, layout, AxQsGridSection.CONTROLS)
        axQsViewModel.setOrder(tileOrder, layout, AxQsGridSection.TILES)
        axQsViewModel.setControlPositions(positions.filterKeys(controlIds::contains), layout)
    }

    LaunchedEffect(
        sourceItems,
        listState.draggedId,
        pendingControlOrder,
        pendingTileOrder,
        savedControlOrder,
        savedTileOrder,
        pendingSpans,
        savedSpans,
    ) {
        if (pendingSpans.any { (id, span) -> savedSpans[id] != span }) {
            return@LaunchedEffect
        }
        if (pendingControlOrder != null && savedControlOrder != pendingControlOrder) {
            return@LaunchedEffect
        }
        if (pendingTileOrder != null && savedTileOrder != pendingTileOrder) {
            return@LaunchedEffect
        }
        pendingSpans = emptyMap()
        pendingControlOrder = null
        pendingTileOrder = null
        if (!listState.dragInProgress && listState.items != sourceItems) {
            listState.updateItems(sourceItems)
            selectedId = selectedId?.takeIf { id -> sourceItems.any { it.id == id } }
        }
    }

    val scale = LocalTileScale.current
    val rowHeight = CommonTileDefaults.TileHeight * scale
    val spacing = CommonTileDefaults.TileSpacing * scale
    val fitsLayout: (List<AxQsGridItem<AxEditGridValue>>) -> Boolean = { items ->
        val controls = items.filter { it.section == AxQsGridSection.CONTROLS }
        val tileCount = items.count { it.section == AxQsGridSection.TILES }
        if (boundedRows) {
            canFitAxQsSharedGrid(controls, controlColumns, tileCount, tileColumns)
        } else {
            canFitAxQsGridItems(controls, controlColumns, AX_QS_CONTROL_MAX_ROWS)
        }
    }
    val fitsAfterControlRepack: (List<AxQsGridItem<AxEditGridValue>>) -> Boolean = { items ->
        fitsLayout(items) ||
            fitsLayout(
                items.map {
                    if (it.section == AxQsGridSection.CONTROLS) {
                        it.copy(position = null)
                    } else {
                        it
                    }
                }
            )
    }
    val transformSection:
        (AxQsGridItem<AxEditGridValue>, AxQsGridSection) -> AxQsGridItem<AxEditGridValue>? =
        { item, section ->
            val value = item.value
            val allowed =
                when (value) {
                    is AxEditGridValue.Tile -> true
                    is AxEditGridValue.Control ->
                        section == AxQsGridSection.CONTROLS || value.control.canUseTileGrid
                }
            if (!allowed) {
                null
            } else {
                val spans =
                    when {
                        section == AxQsGridSection.TILES ->
                            TileGridSpans
                        value is AxEditGridValue.Control -> value.control.spans(controlColumns)
                        else ->
                            AxQsControlSpans(
                                item.span.coerceForControlTile(controlColumns),
                                AxQsSpan.ControlTileMin,
                                AxQsSpan.controlTileMax(controlColumns),
                            )
                    }
                val moved =
                    item.copy(
                        span =
                            if (section == AxQsGridSection.TILES) {
                                AxQsSpan.TileDefault
                            } else if (value is AxEditGridValue.Control) {
                                value.control.coerceSpan(item.span, controlColumns)
                            } else {
                                item.span.coerceForControlTile(controlColumns)
                            },
                        minSpan = spans.min,
                        maxSpan = spans.max,
                        value = value.inSection(section),
                        position = null,
                    )
                val candidates = listState.items.filter { it.id != item.id } + moved
                moved.takeIf { fitsAfterControlRepack(candidates) }
            }
        }

    AxQsDragAutoScroll(listState, scrollState, viewportBounds)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            val controlCapacityRows =
                if (
                    boundedRows && listState.items.any { it.section == AxQsGridSection.TILES }
                ) {
                    AX_QS_CONTROL_MAX_ROWS - 1
                } else {
                    AX_QS_CONTROL_MAX_ROWS
                }
            val controlSection: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.ax_qs_controls),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AxEditableGridSection(
                        section = AxQsGridSection.CONTROLS,
                        listState = listState,
                        columns = controlColumns,
                        maxRows = controlCapacityRows,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        animateItemBounds = animateItemBounds,
                        selectedId = selectedId,
                        onSelected = { selectedId = it },
                        onSave = saveOrders,
                        onSaveSpan = saveSpan,
                        onTransformSection = transformSection,
                        controlPreview = controlPreview,
                        verticalSliderStyle = verticalSliderStyle,
                        hapticFeedback = hapticFeedback,
                    )
                }
            }
            val tileSection: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.qs_edit_tiles),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AxEditableGridSection(
                        section = AxQsGridSection.TILES,
                        listState = listState,
                        columns = tileColumns,
                        maxRows = null,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        animateItemBounds = animateItemBounds,
                        selectedId = selectedId,
                        onSelected = { selectedId = it },
                        onSave = saveOrders,
                        onSaveSpan = saveSpan,
                        onTransformSection = transformSection,
                        controlPreview = controlPreview,
                        verticalSliderStyle = verticalSliderStyle,
                        hapticFeedback = hapticFeedback,
                    )
                }
            }
            if (landscape && !splitShade) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(AxQuickSettingsLayoutDefaults.LandscapeGridSpacing),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) { controlSection() }
                    Box(Modifier.weight(1f)) { tileSection() }
                }
            } else {
                controlSection()
                tileSection()
            }
            val addGridItem: (AxAddItem) -> AxQsGridItem<AxEditGridValue>? = { addItem ->
                if (listState.items.any { it.id == addItem.id }) {
                    null
                } else {
                    val section =
                        if (
                            addItem is AxAddItem.Control && !addItem.control.canUseTileGrid
                        ) {
                            AxQsGridSection.CONTROLS
                        } else {
                            AxQsGridSection.TILES
                        }
                    val columns =
                        if (section == AxQsGridSection.CONTROLS) controlColumns else tileColumns
                    addItem
                        .toEditGridItem(columns, section)
                        .takeIf { fitsAfterControlRepack(listState.items + it) }
                }
            }
            val canAddItem: (AxAddItem) -> Boolean = { addGridItem(it) != null }
            AxAvailableControls(
                allTiles = allTiles,
                currentIds = listState.items.mapTo(mutableSetOf()) { it.id },
                controlColumns = pickerControlColumns,
                tileColumns = pickerTileColumns,
                verticalSliderStyle = verticalSliderStyle,
                onVerticalSliderStyleChanged = { control, style ->
                    axQsViewModel.setVerticalSliderStyle(layout, control, style)
                },
                controlPreview = { control, span, style ->
                    controlPreview(control, span, pickerControlColumns, style)
                },
                canAdd = canAddItem,
                onAdd = { addItem ->
                    val item = addGridItem(addItem) ?: return@AxAvailableControls
                    selectedId = null
                    if (addItem is AxAddItem.Tile && !addItem.viewModel.isCurrent) {
                        editModeViewModel.addTile(addItem.viewModel.tileSpec)
                    }
                    if (item.section == AxQsGridSection.CONTROLS) {
                        val controls =
                            listState.items.filter {
                                it.section == AxQsGridSection.CONTROLS
                            }
                        if (
                            !canFitAxQsGridItems(
                                controls + item,
                                controlColumns,
                                if (
                                    boundedRows &&
                                        listState.items.any {
                                            it.section == AxQsGridSection.TILES
                                        }
                                ) {
                                    AX_QS_CONTROL_MAX_ROWS - 1
                                } else {
                                    AX_QS_CONTROL_MAX_ROWS
                                },
                            )
                        ) {
                            listState.repack(controls.mapTo(mutableSetOf()) { it.id })
                        }
                    }
                    listState.add(item)
                    if (item.section == AxQsGridSection.CONTROLS) {
                        saveSpan(addItem.id, item.span)
                    }
                    saveOrders()
                },
                settings = {
                    AxQsGridSettings(
                        controlLayout = controlGridLayout,
                        tileLayout = tileGridLayout,
                        viewModel = axQsViewModel,
                    )
                },
            )
        }
}

@Composable
private fun AxEditableGridSection(
    section: AxQsGridSection,
    listState: AxQsEditListState<AxEditGridValue>,
    columns: Int,
    maxRows: Int?,
    rowHeight: Dp,
    spacing: Dp,
    animateItemBounds: Boolean,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    onSave: () -> Unit,
    onSaveSpan: (String, AxQsSpan) -> Unit,
    onTransformSection:
        (AxQsGridItem<AxEditGridValue>, AxQsGridSection) -> AxQsGridItem<AxEditGridValue>?,
    controlPreview:
        @Composable (AxQsControl, AxQsSpan, Int, AxQsVerticalSliderStyle) -> Unit,
    verticalSliderStyle: (AxQsControl) -> AxQsVerticalSliderStyle,
    hapticFeedback: HapticFeedback,
) {
    val items = listState.items.filter { it.section == section }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gridPadding = if (section == AxQsGridSection.CONTROLS) EditGridPadding else 0.dp
        val availableWidth = (maxWidth - gridPadding * 2).coerceAtLeast(0.dp)
        val cellWidth = axQsGridCellWidth(availableWidth, columns, spacing)
        val measuredRowHeight = rowHeight
        val circleCells =
            section == AxQsGridSection.TILES &&
                useAxQsCircleCells(
                    gridWidth = availableWidth,
                    tileColumns = columns,
                    spacing = spacing,
                    allowCircles = true,
                )
        val contentRows = axQsGridRowCount(items, columns, maxRows)
        val visibleRows =
            if (
                section == AxQsGridSection.CONTROLS &&
                    (listState.dragInProgress || listState.resizeInProgress)
            ) {
                maxRows ?: contentRows.coerceAtLeast(1)
            } else {
                contentRows.coerceAtLeast(1)
            }
        val gridBorder =
            if (section == AxQsGridSection.CONTROLS) {
                Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(EditGridCornerRadius),
                )
            } else {
                Modifier
            }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .then(gridBorder)
                    .pointerInput(selectedId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (waitForUpOrCancellation() != null) onSelected(null)
                        }
                    }
                    .axQsDropTarget(
                        state = listState,
                        section = section,
                        sectionOf = { it.section },
                        transform = onTransformSection,
                        onDrop = onSave,
                    )
                    .padding(gridPadding)
        ) {
            AxQsGrid(
                items = items,
                columns = columns,
                rowHeight = rowHeight,
                spacing = spacing,
                maxRows = maxRows,
                minimumRows = visibleRows,
                squareCells = circleCells,
                animateItemBounds = animateItemBounds,
                staticItemId = listState.draggedId,
                onItemBounds = { id, bounds -> listState.updateItemBounds(id, section, bounds) },
                onCells =
                    if (section == AxQsGridSection.CONTROLS) {
                        { cells -> listState.updateGridCells(section, cells) }
                    } else {
                        null
                    },
                modifier =
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        listState.updateGridOrigin(section, it.positionInRoot())
                    },
            ) { item ->
                val isSelected = selectedId == item.id
                var itemSize by remember(item.id) { mutableStateOf(IntSize.Zero) }
                val moveEarlierLabel = stringResource(R.string.ax_qs_move_earlier)
                val moveLaterLabel = stringResource(R.string.ax_qs_move_later)
                val moveItem: (Int) -> Boolean = { delta ->
                    listState
                        .moveBy(item.id, delta) { it.section }
                        .also { moved -> if (moved) onSave() }
                }
                val removeItem = {
                    listState.remove(item.id)
                    onSave()
                    onSelected(null)
                }
                val value = item.value
                val canResize: (AxQsSpan) -> Boolean = { span ->
                    val spanAllowed =
                        when (value) {
                            is AxEditGridValue.Control -> value.control.isSpanAllowed(span, columns)
                            is AxEditGridValue.Tile ->
                                section == AxQsGridSection.CONTROLS &&
                                    span == span.coerceForControlTile(columns)
                        }
                    spanAllowed &&
                        canFitAxQsGridItems(
                            listState.items
                                .filter { it.section == AxQsGridSection.CONTROLS }
                                .map { current ->
                                    if (current.id == item.id) {
                                        current.copy(span = span)
                                    } else {
                                        current
                                    }
                                },
                            columns,
                            maxRows ?: AxQsSpan.MAX_ROWS,
                        )
                }
                val resizeItem: (AxQsSpan) -> Unit = { span ->
                    if (canResize(span)) listState.update(item.id) { it.copy(span = span) }
                }
                val finishResize: (AxQsSpan) -> Unit = { span ->
                    if (canResize(span)) {
                        onSaveSpan(item.id, span)
                        onSave()
                    }
                }
                val resizable = section == AxQsGridSection.CONTROLS && item.minSpan != item.maxSpan
                val tileState =
                    if (isSelected && resizable) TileState.Selected else TileState.Removable
                val sliderControl =
                    (value as? AxEditGridValue.Control)?.control?.takeIf { it.isSlider }
                val verticalSlider = sliderControl?.isVerticalSlider == true
                val controlStyle =
                    (value as? AxEditGridValue.Control)?.control?.let(verticalSliderStyle)
                        ?: AxQsVerticalSliderStyle.M3_EXPRESSIVE
                val selectionShape =
                    when (value) {
                        is AxEditGridValue.Tile ->
                            RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius)
                        is AxEditGridValue.Control ->
                            axQsControlShape(value.control, item.span, controlStyle)
                    }
                val selectionColor =
                    if (sliderControl != null) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                val sliderSelectionPadding =
                    if (sliderControl != null) {
                        val availableThickness =
                            if (verticalSlider) cellWidth else measuredRowHeight
                        val trackHeight =
                            axQsSliderTrackHeight(availableThickness, verticalSlider)
                        ((availableThickness - trackHeight) / 2 - SliderSelectionGap)
                            .coerceAtLeast(0.dp)
                    } else {
                        0.dp
                    }
                val selectionHorizontalPadding =
                    if (verticalSlider) sliderSelectionPadding else 0.dp
                val selectionVerticalPadding =
                    if (verticalSlider) 0.dp else sliderSelectionPadding
                val resizeHandle =
                    if (resizable) {
                        Modifier.axQsResizeHandle(
                            id = item.id,
                            span = { listState.item(item.id)?.span ?: item.span },
                            itemSize = { itemSize },
                            spacing = spacing,
                            resolveSpan = { startSpan, columnDelta, rowDelta ->
                                when (value) {
                                    is AxEditGridValue.Control ->
                                        value.control.resizeSpan(
                                            startSpan = startSpan,
                                            columnDelta = columnDelta,
                                            rowDelta = rowDelta,
                                            columns = columns,
                                        )
                                    is AxEditGridValue.Tile ->
                                        AxQsSpan(
                                            columns =
                                                (startSpan.columns + columnDelta).coerceIn(
                                                    item.minSpan.columns,
                                                    item.maxSpan.columns,
                                                ),
                                            rows =
                                                (startSpan.rows + rowDelta).coerceIn(
                                                    item.minSpan.rows,
                                                    item.maxSpan.rows,
                                                ),
                                        )
                                }
                            },
                            canResize = canResize,
                            onResizeStarted = listState::beginResize,
                            onResizeStopped = listState::endResize,
                            onResize = resizeItem,
                            onResizeFinished = finishResize,
                        )
                    } else {
                        Modifier
                    }
                val removeDescription =
                    stringResource(R.string.accessibility_qs_edit_remove_tile_action)
                val resizeDescription =
                    stringResource(R.string.accessibility_qs_edit_toggle_tile_size_action)

                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .pointerInput(selectedId, item.id) {
                                awaitEachGesture {
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    if (selectedId != null && selectedId != item.id) {
                                        onSelected(null)
                                    }
                                }
                            }
                            .onSizeChanged { itemSize = it }
                ) {
                    if (listState.draggedId == item.id) {
                        Box(
                            Modifier.fillMaxSize()
                                .padding(
                                    horizontal = selectionHorizontalPadding,
                                    vertical = selectionVerticalPadding,
                                )
                                .border(
                                    width = 3.dp,
                                    color = selectionColor,
                                    shape = selectionShape,
                                )
                        )
                    } else {
                        AxInteractiveTileContainer(
                            tileState = tileState,
                            resizeHandleModifier = resizeHandle,
                            selectionColor = selectionColor,
                            selectionShape = selectionShape,
                            selectionHorizontalPadding = selectionHorizontalPadding,
                            selectionVerticalPadding = selectionVerticalPadding,
                            resizable = resizable,
                            modifier = Modifier.fillMaxSize(),
                            onRemoveClick = removeItem,
                            onResizeClick = {
                                if (resizable) {
                                    listState.item(item.id)?.let { current ->
                                        val nextSpan =
                                            if (sliderControl != null) {
                                                sliderControl.nextSpan(current.span, columns)
                                            } else {
                                                val width =
                                                    if (
                                                        current.span.columns <
                                                            current.maxSpan.columns
                                                    ) {
                                                        current.span.columns + 1
                                                    } else {
                                                        current.minSpan.columns
                                                    }
                                                current.span.copy(columns = width)
                                            }
                                        if (nextSpan != current.span && canResize(nextSpan)) {
                                            resizeItem(nextSpan)
                                            finishResize(nextSpan)
                                        }
                                    }
                                }
                            },
                            removeContentDescription = removeDescription,
                            resizeContentDescription = resizeDescription,
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxSize()
                                        .then(
                                            if (sliderControl != null) {
                                                Modifier
                                            } else {
                                                Modifier.clip(selectionShape)
                                            }
                                        )
                                        .axQsDragSource(
                                            id = item.id,
                                            state = listState,
                                            onDragStart = {
                                                onSelected(item.id)
                                                hapticFeedback.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                            },
                                        )
                                        .clickable { onSelected(item.id) }
                                        .semantics {
                                            customActions =
                                                listOf(
                                                    CustomAccessibilityAction(moveEarlierLabel) {
                                                        moveItem(-1)
                                                    },
                                                    CustomAccessibilityAction(moveLaterLabel) {
                                                        moveItem(1)
                                                    },
                                                )
                                        }
                            ) {
                                when (value) {
                                    is AxEditGridValue.Tile ->
                                        AxQsEditTile(
                                            tile = value.viewModel,
                                            span = item.span,
                                            circleCells = circleCells,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    is AxEditGridValue.Control ->
                                        controlPreview(
                                            value.control,
                                            item.span,
                                            columns,
                                            controlStyle,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val AxQsGridItem<AxEditGridValue>.section: AxQsGridSection
    get() = value.section

private fun AxEditGridValue.inSection(section: AxQsGridSection): AxEditGridValue {
    return when (this) {
        is AxEditGridValue.Tile -> copy(section = section)
        is AxEditGridValue.Control -> copy(section = section)
    }
}

private fun AxAddItem.toEditGridItem(
    columns: Int,
    section: AxQsGridSection,
): AxQsGridItem<AxEditGridValue> {
    return when (this) {
        is AxAddItem.Tile -> {
            val controlGrid = section == AxQsGridSection.CONTROLS
            AxQsGridItem(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = if (controlGrid) AxQsSpan.ControlTileMin else AxQsSpan.TileDefault,
                maxSpan =
                    if (controlGrid) {
                        AxQsSpan.controlTileMax(columns)
                    } else {
                        AxQsSpan.TileDefault
                    },
                value = AxEditGridValue.Tile(viewModel, section),
            )
        }
        is AxAddItem.Control -> {
            val spans =
                if (section == AxQsGridSection.TILES) {
                    TileGridSpans
                } else {
                    control.spans(columns)
                }
            AxQsGridItem(
                id = id,
                span = spans.default,
                minSpan = spans.min,
                maxSpan = spans.max,
                value = AxEditGridValue.Control(control, section),
            )
        }
    }
}

private fun buildEditItems(
    currentTiles: List<EditTileViewModel>,
    layout: AxQsLayout,
    controlColumns: Int,
    tileColumns: Int,
    controlPositions: Map<String, AxQsGridPosition>,
    viewModel: AxQsViewModel,
): List<AxQsGridItem<AxEditGridValue>> {
    val values = LinkedHashMap<String, AxEditGridValue>()
    currentTiles.forEach {
        values[it.tileSpec.spec] = AxEditGridValue.Tile(it, AxQsGridSection.TILES)
    }
    AxQsControl.entries.forEach { control ->
        values[control.id] = AxEditGridValue.Control(control, AxQsGridSection.CONTROLS)
    }
    val controlIds =
        viewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.CONTROLS,
            availableIds = values.keys.toList(),
            defaultIds = currentTiles.map { it.tileSpec.spec },
        )
    val tileIds =
        viewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.TILES,
            availableIds =
                currentTiles.map { it.tileSpec.spec } +
                    AxQsControl.entries.filter { it.canUseTileGrid }.map(AxQsControl::id),
            defaultIds = currentTiles.map { it.tileSpec.spec },
        ).filterNot(controlIds.toSet()::contains)
    val controlItems: List<AxQsGridItem<AxEditGridValue>> =
        controlIds.map { id ->
            when (val value = values.getValue(id)) {
                is AxEditGridValue.Tile ->
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span =
                            viewModel
                                .span(id, layout, AxQsSpan.TileDefault)
                                .coerceForControlTile(controlColumns),
                        minSpan = AxQsSpan.ControlTileMin,
                        maxSpan = AxQsSpan.controlTileMax(controlColumns),
                        value = value.inSection(AxQsGridSection.CONTROLS),
                        position = controlPositions[id],
                    )
                is AxEditGridValue.Control -> {
                    val spans = value.control.spans(controlColumns)
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span =
                            viewModel.span(id, layout, spans.default).let {
                                value.control.coerceSpan(it, controlColumns)
                            },
                        minSpan = spans.min,
                        maxSpan = spans.max,
                        value = value.inSection(AxQsGridSection.CONTROLS),
                        position = controlPositions[id],
                    )
                }
            }
        }
    val tileItems: List<AxQsGridItem<AxEditGridValue>> =
        tileIds.map { id ->
            AxQsGridItem<AxEditGridValue>(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = AxQsSpan.TileDefault,
                maxSpan = AxQsSpan.TileDefault,
                value = values.getValue(id).inSection(AxQsGridSection.TILES),
            )
        }
    val rows =
        if (layout == AxQsLayout.QS) {
            AX_QS_CONTROL_MAX_ROWS
        } else {
            axQsSharedGridRows(controlItems, controlColumns, tileItems.size, tileColumns).controls
        }
    val fittedControls =
        fitAxQsGridItems<AxEditGridValue>(controlItems, controlColumns, rows)
    return fittedControls + tileItems
}

private val SliderSelectionGap = 4.dp
private val EditGridCornerRadius = 28.dp
private val EditGridPadding = 10.dp
private val TileGridSpans =
    AxQsControlSpans(AxQsSpan.TileDefault, AxQsSpan.TileDefault, AxQsSpan.TileDefault)
