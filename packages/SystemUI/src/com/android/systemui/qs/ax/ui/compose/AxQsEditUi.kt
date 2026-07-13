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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import com.android.systemui.res.R

private sealed interface AxEditGridValue {
    data class Tile(val viewModel: EditTileViewModel) : AxEditGridValue

    data class Control(val control: AxQsControl) : AxEditGridValue
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
    modifier: Modifier = Modifier,
) {
    val allTiles by editModeViewModel.tiles.collectAsStateWithLifecycle(initialValue = null)
    var editQqs by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showQqsEditor = !landscape && axQsViewModel.panelMode == AxQsPanelMode.TOGETHER

    BackHandler { editModeViewModel.stopEditing() }
    LaunchedEffect(showQqsEditor) { if (!showQqsEditor) editQqs = false }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sidePadding =
            maxWidth *
                if (landscape) {
                    AxQuickSettingsLayoutDefaults.LANDSCAPE_SIDE_PADDING_FRACTION
                } else {
                    AxQuickSettingsLayoutDefaults.PORTRAIT_SIDE_PADDING_FRACTION
                }
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = sidePadding)
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
                    Button(
                        onClick = editModeViewModel::stopEditing,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.quick_settings_done))
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
                    landscape -> AxQsLayout.LANDSCAPE
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
    val landscape = layout == AxQsLayout.LANDSCAPE
    val controlGridLayout = AxQsGridLayout.from(qqs, landscape, AxQsGridSection.CONTROLS)
    val tileGridLayout = AxQsGridLayout.from(qqs, landscape, AxQsGridSection.TILES)
    val controlColumns = axQsViewModel.columns(controlGridLayout)
    val controlRows = axQsViewModel.defaultRows(controlGridLayout)
    val tileColumns = axQsViewModel.columns(tileGridLayout)
    val tileRows = axQsViewModel.rows(tileGridLayout)
    val defaultControlColumns = axQsViewModel.defaultColumns(controlGridLayout)
    val defaultTileColumns = axQsViewModel.defaultColumns(tileGridLayout)
    val pickerControlColumns = if (landscape) LANDSCAPE_PICKER_COLUMNS else defaultControlColumns
    val pickerTileColumns = if (landscape) LANDSCAPE_PICKER_COLUMNS else defaultTileColumns
    val verticalSliderStyle: (AxQsControl) -> AxQsVerticalSliderStyle = { control ->
        axQsViewModel.verticalSliderStyle(layout, control)
    }
    val allowCircleCells =
        controlColumns >= defaultControlColumns && (landscape || tileColumns >= defaultTileColumns)
    val savedControlOrder = axQsViewModel.order(layout, AxQsGridSection.CONTROLS)
    val savedTileOrder = axQsViewModel.order(layout, AxQsGridSection.TILES)
    val savedSpans = axQsViewModel.spans(layout)
    val savedControlPositions = axQsViewModel.controlPositions(layout)
    val sourceItems =
        remember(
            currentTiles,
            layout,
            controlColumns,
            controlRows,
            tileColumns,
            tileRows,
            savedControlOrder,
            savedTileOrder,
            savedSpans,
            savedControlPositions,
        ) {
            buildEditItems(
                currentTiles = currentTiles,
                layout = layout,
                controlColumns = controlColumns,
                controlRows = controlRows,
                tileColumns = tileColumns,
                tileRows = tileRows,
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
    val transformSection:
        (AxQsGridItem<AxEditGridValue>, AxQsGridSection) -> AxQsGridItem<AxEditGridValue>? =
        { item, section ->
            when (item.value) {
                is AxEditGridValue.Control -> item.takeIf { section == AxQsGridSection.CONTROLS }
                is AxEditGridValue.Tile -> {
                    val minSpan =
                        if (section == AxQsGridSection.CONTROLS) {
                            AxQsSpan.ControlTileMin
                        } else {
                            AxQsSpan.TileDefault
                        }
                    val maxSpan =
                        if (section == AxQsGridSection.CONTROLS) {
                            AxQsSpan.controlTileMax(controlColumns)
                        } else {
                            AxQsSpan.TileDefault
                        }
                    val span =
                        if (section == AxQsGridSection.CONTROLS) {
                            (item.span.takeIf { item.section == AxQsGridSection.CONTROLS }
                                    ?: savedSpans[item.id]
                                    ?: AxQsSpan.TileWideDefault)
                                .coerceForControlTile(controlColumns)
                        } else {
                            AxQsSpan.TileDefault
                        }
                    val moved =
                        item.copy(
                            span = span,
                            minSpan = minSpan,
                            maxSpan = maxSpan,
                            position = null,
                        )
                    val controls =
                        listState.items.filter {
                            it.id != item.id && it.section == AxQsGridSection.CONTROLS
                        }
                    val tiles =
                        listState.items.filter {
                            it.id != item.id && it.section == AxQsGridSection.TILES
                        }
                    moved.takeIf {
                        when (section) {
                            AxQsGridSection.CONTROLS ->
                                canFitAxQsGridItems(controls + moved, controlColumns, controlRows)
                            AxQsGridSection.TILES -> !qqs || tiles.size < tileColumns * tileRows
                        }
                    }
                }
            }
        }

    AxQsDragAutoScroll(listState, scrollState, viewportBounds)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gridWidth =
            if (landscape) {
                (maxWidth - AxQuickSettingsLayoutDefaults.LandscapeGridSpacing) / 2
            } else {
                maxWidth
            }
        val circleCells =
            useAxQsCircleCells(
                gridWidth = gridWidth,
                tileColumns = tileColumns,
                spacing = spacing,
                allowCircles = allowCircleCells,
            )
        val pickerCircleCells =
            useAxQsCircleCells(
                gridWidth = (maxWidth - 32.dp).coerceAtLeast(0.dp),
                tileColumns = pickerTileColumns,
                spacing = spacing,
                allowCircles = allowCircleCells,
            )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                        maxRows = controlRows,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        circleCells = circleCells,
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
                        maxRows = tileRows.takeIf { qqs },
                        rowHeight = rowHeight,
                        spacing = spacing,
                        circleCells = circleCells,
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
            if (landscape) {
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
                    when (addItem) {
                        is AxAddItem.Tile ->
                            addItem.toEditGridItem(tileColumns).takeIf {
                                !qqs ||
                                    listState.items.count { it.section == AxQsGridSection.TILES } <
                                        tileColumns * tileRows
                            }
                        is AxAddItem.Control -> {
                            val item = addItem.toEditGridItem(controlColumns)
                            val controls =
                                listState.items.filter {
                                    it.section == AxQsGridSection.CONTROLS
                                }
                            item.takeIf { candidate ->
                                canFitAxQsGridItems(
                                    controls + candidate,
                                    controlColumns,
                                    controlRows,
                                ) ||
                                    canFitAxQsGridItems(
                                        controls.map { it.copy(position = null) } + candidate,
                                        controlColumns,
                                        controlRows,
                                    )
                            }
                        }
                    }
                }
            }
            val canAddItem: (AxAddItem) -> Boolean = { addGridItem(it) != null }
            AxAvailableControls(
                allTiles = allTiles,
                currentIds = listState.items.mapTo(mutableSetOf()) { it.id },
                controlColumns = pickerControlColumns,
                tileColumns = pickerTileColumns,
                circleCells = pickerCircleCells,
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
                    if (addItem is AxAddItem.Control) {
                        val controls =
                            listState.items.filter {
                                it.section == AxQsGridSection.CONTROLS
                            }
                        if (
                            !canFitAxQsGridItems(
                                controls + item,
                                controlColumns,
                                controlRows,
                            )
                        ) {
                            listState.repack(controls.mapTo(mutableSetOf()) { it.id })
                        }
                    }
                    listState.add(item)
                    if (addItem is AxAddItem.Control) {
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
}

@Composable
private fun AxEditableGridSection(
    section: AxQsGridSection,
    listState: AxQsEditListState<AxEditGridValue>,
    columns: Int,
    maxRows: Int?,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
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
        val measuredRowHeight = if (circleCells) cellWidth else rowHeight
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
                squareCells = circleCells,
                minimumRows = visibleRows,
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
                            if (section == AxQsGridSection.TILES && circleCells) {
                                CircleShape
                            } else {
                                RoundedCornerShape(CommonTileDefaults.InactiveCornerRadius)
                            }
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
                                            circle =
                                                section == AxQsGridSection.TILES &&
                                                    circleCells,
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
    get() =
        when (value) {
            is AxEditGridValue.Control -> AxQsGridSection.CONTROLS
            is AxEditGridValue.Tile ->
                if (span == AxQsSpan.TileDefault) {
                    AxQsGridSection.TILES
                } else {
                    AxQsGridSection.CONTROLS
                }
        }

private fun AxAddItem.toEditGridItem(columns: Int): AxQsGridItem<AxEditGridValue> {
    return when (this) {
        is AxAddItem.Tile ->
            AxQsGridItem(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = AxQsSpan.TileDefault,
                maxSpan = AxQsSpan.TileDefault,
                value = AxEditGridValue.Tile(viewModel),
            )
        is AxAddItem.Control -> {
            val spans = control.spans(columns)
            AxQsGridItem(
                id = id,
                span = spans.default,
                minSpan = spans.min,
                maxSpan = spans.max,
                value = AxEditGridValue.Control(control),
            )
        }
    }
}

private fun buildEditItems(
    currentTiles: List<EditTileViewModel>,
    layout: AxQsLayout,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    controlPositions: Map<String, AxQsGridPosition>,
    viewModel: AxQsViewModel,
): List<AxQsGridItem<AxEditGridValue>> {
    val values = LinkedHashMap<String, AxEditGridValue>()
    currentTiles.forEach { values[it.tileSpec.spec] = AxEditGridValue.Tile(it) }
    AxQsControl.entries.forEach { control -> values[control.id] = AxEditGridValue.Control(control) }
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
            availableIds = currentTiles.map { it.tileSpec.spec },
            defaultIds = currentTiles.map { it.tileSpec.spec },
        )
    val controlItems: List<AxQsGridItem<AxEditGridValue>> =
        controlIds.map { id ->
            when (val value = values.getValue(id)) {
                is AxEditGridValue.Tile ->
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span =
                            viewModel
                                .span(id, layout, AxQsSpan.TileWideDefault)
                                .coerceForControlTile(controlColumns),
                        minSpan = AxQsSpan.ControlTileMin,
                        maxSpan = AxQsSpan.controlTileMax(controlColumns),
                        value = value,
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
                        value = value,
                        position = controlPositions[id],
                    )
                }
            }
        }
    val tileItems: List<AxQsGridItem<AxEditGridValue>> =
        tileIds.mapNotNull { id ->
            (values[id] as? AxEditGridValue.Tile)?.let { value ->
                AxQsGridItem<AxEditGridValue>(
                    id = id,
                    span = AxQsSpan.TileDefault,
                    minSpan = AxQsSpan.TileDefault,
                    maxSpan = AxQsSpan.TileDefault,
                    value = value,
                )
            }
        }
    val fittedControls =
        fitAxQsGridItems<AxEditGridValue>(controlItems, controlColumns, controlRows)
    val fittedTiles =
        if (layout == AxQsLayout.QQS) tileItems.take(tileColumns * tileRows) else tileItems
    return fittedControls + fittedTiles
}

private const val LANDSCAPE_PICKER_COLUMNS = 8
private val SliderSelectionGap = 4.dp
private val EditGridCornerRadius = 28.dp
private val EditGridPadding = 10.dp
