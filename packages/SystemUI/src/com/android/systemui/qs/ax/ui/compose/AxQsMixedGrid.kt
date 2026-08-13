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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height as layoutHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsGridLayout
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsLayout
import com.android.systemui.qs.ax.shared.model.AxQsPanelMode
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import com.android.systemui.qs.ax.ui.model.AxQsGridValue
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.ax.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.infinitegrid.Tile
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.tiles.ringer.RingerSliderTileContent
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import kotlin.math.abs

@Composable
internal fun ContentScope.AxQsMixedGrid(
    viewModel: QSFragmentComposeViewModel,
    axQsViewModel: AxQsViewModel,
    mediaViewModel: AxMediaViewModel,
    detailsViewModel: DetailsViewModel,
    qqs: Boolean,
    listening: () -> Boolean,
    brightnessSliderViewModel: BrightnessSliderViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val tiles = viewModel.containerViewModel.tileGridViewModel.tileViewModels
    val values = LinkedHashMap<String, AxQsGridValue>()
    tiles.forEach { tile -> values[tile.spec.spec] = AxQsGridValue.Tile(tile) }
    AxQsControl.entries.forEach { control -> values[control.id] = AxQsGridValue.Control(control) }

    val splitShade = viewModel.isInSplitShade
    LaunchedEffect(splitShade) {
        if (splitShade) {
            scrollState.scrollTo(0)
        }
    }
    val layout =
        when {
            splitShade -> AxQsLayout.SPLIT_SHADE
            qqs -> AxQsLayout.QQS
            else -> AxQsLayout.QS
        }
    val controlGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.CONTROLS)
    val tileGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.TILES)
    val controlColumns = axQsViewModel.columns(controlGridLayout)
    val tileColumns = axQsViewModel.columns(tileGridLayout)
    val tileRowLimit = axQsViewModel.rows(tileGridLayout)
    val showTileLabels = axQsViewModel.showTileLabels(tileGridLayout)
    val allowCircleCells =
        controlColumns >= axQsViewModel.defaultColumns(controlGridLayout) &&
            (splitShade || tileColumns >= axQsViewModel.defaultColumns(tileGridLayout))
    val controlPositions = axQsViewModel.controlPositions(layout)
    val controlIds =
        axQsViewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.CONTROLS,
            availableIds = values.keys.toList(),
            defaultIds = tiles.map { it.spec.spec },
        )
    val tileIds =
        axQsViewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.TILES,
            availableIds =
                tiles.map { it.spec.spec } +
                    AxQsControl.entries.filter { it.canUseTileGrid }.map(AxQsControl::id),
            defaultIds = tiles.map { it.spec.spec },
        )
    val controlItems: List<AxQsGridItem<AxQsGridValue>> =
        controlIds.map { id ->
            when (val value = values.getValue(id)) {
                is AxQsGridValue.Tile ->
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span =
                            axQsViewModel
                                .span(id, layout, AxQsSpan.TileDefault)
                                .coerceForControlTile(controlColumns),
                        minSpan = AxQsSpan.ControlTileMin,
                        maxSpan = AxQsSpan.controlTileMax(controlColumns),
                        value = value,
                        position = controlPositions[id],
                    )
                is AxQsGridValue.Control -> {
                    val spans = value.control.spans(controlColumns)
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span =
                            axQsViewModel.span(id, layout, spans.default).let {
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
    val tileItems: List<AxQsGridItem<AxQsGridValue>> =
        tileIds.map { id ->
            AxQsGridItem<AxQsGridValue>(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = AxQsSpan.TileDefault,
                maxSpan = AxQsSpan.TileDefault,
                value = values.getValue(id),
            )
        }
    val rows =
        if (layout == AxQsLayout.QS) {
            AxQsGridRows(
                controls = AX_QS_CONTROL_MAX_ROWS,
                tiles = axQsVisibleTileRows(tileItems.size, tileColumns, tileRowLimit),
            )
        } else {
            axQsSharedGridRows(
                controlItems,
                controlColumns,
                tileItems.size,
                tileColumns,
                maxTileRows = tileRowLimit,
            )
        }
    val fittedControlItems: List<AxQsGridItem<AxQsGridValue>> =
        fitAxQsGridItems<AxQsGridValue>(controlItems, controlColumns, rows.controls)
    val visibleTiles =
        (fittedControlItems + tileItems).mapNotNull {
            (it.value as? AxQsGridValue.Tile)?.viewModel
        }
    val rowHeight = CommonTileDefaults.TileHeight * LocalTileScale.current
    val spacing = CommonTileDefaults.TileSpacing * LocalTileScale.current
    val qsEntranceProgress = {
        ((viewModel.expansionState.progress - QS_ENTRANCE_START) / (1f - QS_ENTRANCE_START))
            .coerceIn(0f, 1f)
    }
    val separateQqs =
        qqs && !splitShade && axQsViewModel.panelMode == AxQsPanelMode.SEPARATE
    val qqsHideBypass = separateQqs && axQsViewModel.isQsBypassingShade
    val controlContent: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        when (val value = item.value) {
            is AxQsGridValue.Tile ->
                this@AxQsMixedGrid.AxLiveTile(
                    tile = value.viewModel,
                    item = item,
                    iconOnly = false,
                    qqs = qqs,
                    separateQqs = separateQqs,
                    listening = listening,
                    detailsViewModel = detailsViewModel,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            is AxQsGridValue.Control ->
                AxLiveControl(
                    control = value.control,
                    span = item.span,
                    mediaViewModel = mediaViewModel,
                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                    brightnessSliderViewModel = brightnessSliderViewModel,
                    volumeSliderViewModel = volumeSliderViewModel,
                    verticalSliderStyle =
                        axQsViewModel.verticalSliderStyle(layout, value.control),
                    entranceProgress = {
                        viewModel.quickQuickSettingsViewModel.squishinessViewModel.squishiness.value
                    },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
    val tileContent: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        when (val value = item.value) {
            is AxQsGridValue.Tile ->
                this@AxQsMixedGrid.AxLiveTile(
                    tile = value.viewModel,
                    item = item,
                    iconOnly = true,
                    qqs = qqs,
                    separateQqs = separateQqs,
                    listening = listening,
                    detailsViewModel = detailsViewModel,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            is AxQsGridValue.Control ->
                AxLiveControl(
                    control = value.control,
                    span = item.span,
                    mediaViewModel = mediaViewModel,
                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                    brightnessSliderViewModel = brightnessSliderViewModel,
                    volumeSliderViewModel = volumeSliderViewModel,
                    verticalSliderStyle =
                        axQsViewModel.verticalSliderStyle(layout, value.control),
                    entranceProgress = {
                        viewModel.quickQuickSettingsViewModel.squishinessViewModel.squishiness.value
                    },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
    val tileLabel: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        (item.value as? AxQsGridValue.Tile)?.let { AxTileGridLabel(it.viewModel) }
    }

    QuickSettingsTheme {
        BoxWithConstraints(modifier) {
            val portraitPadding =
                maxWidth * AxQuickSettingsLayoutDefaults.PORTRAIT_SIDE_PADDING_FRACTION
            val splitShadePadding =
                maxWidth * AxQuickSettingsLayoutDefaults.LANDSCAPE_SIDE_PADDING_FRACTION
            val contentPadding = if (splitShade) splitShadePadding else portraitPadding
            val gridWidth = (maxWidth - contentPadding * 2).coerceAtLeast(0.dp)
            val contentTopPadding =
                if (splitShade) {
                    AxQuickSettingsLayoutDefaults.LandscapeHeaderHeight +
                        AxQuickSettingsLayoutDefaults.LandscapeHeaderContentSpacing
                } else {
                    0.dp
                }
            val circleCells =
                useAxQsCircleCells(
                    gridWidth = gridWidth,
                    tileColumns = tileColumns,
                    spacing = spacing,
                    allowCircles = allowCircleCells,
                )
            when {
                qqs && splitShade -> Unit
                qqs ->
                    Column(
                        Modifier.fillMaxWidth().graphicsLayer {
                            alpha = if (qqsHideBypass) 0f else 1f
                        }
                    ) {
                        AxQQS(
                            toolbarViewModel = viewModel.toolbarViewModel,
                            shadeHeaderViewModel =
                                viewModel.containerViewModel.shadeHeaderViewModel,
                            controlItems = fittedControlItems,
                            tileItems = tileItems,
                            controlColumns = controlColumns,
                            controlRows = rows.controls,
                            tileColumns = tileColumns,
                            tileRows = rows.tiles,
                            showTileLabels = showTileLabels,
                            rowHeight = rowHeight,
                            spacing = spacing,
                            circleCells = circleCells,
                            isFullyVisible = {
                                viewModel.isQsVisibleAndAnyShadeExpanded && !viewModel.isEditing
                            },
                            editButtonProgress = qsEntranceProgress,
                            separateMode = separateQqs,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = contentPadding),
                            controlContent = controlContent,
                            tileContent = tileContent,
                        )
                        if (
                            separateQqs &&
                                mediaViewModel.hasVisibleSessions(AxMediaSurface.SEPARATE_QQS)
                        ) {
                            Spacer(Modifier.layoutHeight(spacing))
                            Box(
                                Modifier.fillMaxWidth()
                                    .padding(
                                        horizontal = dimensionResource(R.dimen.qs_horizontal_margin)
                                    )
                                    .layoutHeight(
                                        dimensionResource(
                                            R.dimen.qs_media_session_height_expanded
                                        )
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                AxMediaPanel(
                                    viewModel = mediaViewModel,
                                    span = AxQsSpan(controlColumns, 2),
                                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                                    modifier = Modifier.fillMaxSize(),
                                    surface = AxMediaSurface.SEPARATE_QQS,
                                )
                            }
                        }
                    }
                splitShade ->
                    AxQS(
                        toolbarViewModel = viewModel.toolbarViewModel,
                        shadeHeaderViewModel = viewModel.containerViewModel.shadeHeaderViewModel,
                        isFullyVisible = { viewModel.isQsFullyExpanded && !viewModel.isEditing },
                        controlItems = fittedControlItems,
                        tileItems = tileItems,
                        controlColumns = controlColumns,
                        controlRows = rows.controls,
                        tileColumns = tileColumns,
                        tileRows = rows.tiles,
                        showTileLabels = showTileLabels,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        editButtonProgress = qsEntranceProgress,
                        scrollState = scrollState,
                        circleCells = circleCells,
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(top = contentTopPadding)
                                .padding(horizontal = contentPadding),
                        controlContent = controlContent,
                        tileContent = tileContent,
                        tileLabel = tileLabel,
                    )
                else ->
                    AxQS(
                        toolbarViewModel = viewModel.toolbarViewModel,
                        shadeHeaderViewModel = viewModel.containerViewModel.shadeHeaderViewModel,
                        isFullyVisible = { viewModel.isQsFullyExpanded && !viewModel.isEditing },
                        controlItems = fittedControlItems,
                        tileItems = tileItems,
                        controlColumns = controlColumns,
                        controlRows = rows.controls,
                        tileColumns = tileColumns,
                        tileRows = rows.tiles,
                        showTileLabels = showTileLabels,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        editButtonProgress = qsEntranceProgress,
                        scrollState = scrollState,
                        circleCells = circleCells,
                        modifier = Modifier.fillMaxSize().padding(horizontal = contentPadding),
                        controlContent = controlContent,
                        tileContent = tileContent,
                        tileLabel = tileLabel,
                    )
            }
    }
    }
    val tilesListening = !separateQqs && !(qqs && splitShade) && listening()
    DisposableEffect(visibleTiles, tilesListening) {
        val token = Any()
        if (tilesListening) {
            visibleTiles.forEach { it.startListening(token) }
        }
        onDispose {
            if (tilesListening) {
                visibleTiles.forEach { it.stopListening(token) }
            }
        }
    }
}

@Composable
private fun ContentScope.AxLiveTile(
    tile: TileViewModel,
    item: AxQsGridItem<AxQsGridValue>,
    iconOnly: Boolean,
    qqs: Boolean,
    separateQqs: Boolean,
    listening: () -> Boolean,
    detailsViewModel: DetailsViewModel,
    viewModel: QSFragmentComposeViewModel,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        this@AxLiveTile.Tile(
            tile = tile,
            iconOnly = iconOnly,
            span = item.span,
            fillHeight = true,
            compactIconSize = axQsTileIconSize(minOf(maxWidth, maxHeight)),
            tileShapeOverride =
                CircleShape.takeIf {
                    item.span == AxQsSpan.TileDefault &&
                        abs(maxWidth.value - maxHeight.value) < 1f
                },
            squishiness = { 1f },
            coroutineScope = coroutineScope,
            bounceableInfo = null,
            tileHapticsViewModelFactoryProvider =
                viewModel.quickQuickSettingsViewModel.tileHapticsViewModelFactoryProvider,
            interactionSource = null,
            modifier = Modifier.fillMaxSize(),
            isVisible = if (separateQqs) ({ false }) else listening,
            detailsViewModel = if (qqs) null else detailsViewModel,
        )
    }
}

@Composable
private fun AxLiveControl(
    control: AxQsControl,
    span: AxQsSpan,
    mediaViewModel: AxMediaViewModel,
    mediaViewModelFactory: MediaViewModel.Factory,
    brightnessSliderViewModel: BrightnessSliderViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel,
    verticalSliderStyle: AxQsVerticalSliderStyle,
    entranceProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (control) {
            AxQsControl.BRIGHTNESS,
            AxQsControl.BRIGHTNESS_HORIZONTAL ->
                AxQsBrightnessControl(
                    vertical = control.isVerticalSlider,
                    verticalStyle = verticalSliderStyle,
                    viewModel = brightnessSliderViewModel,
                    entranceProgress = entranceProgress,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.VOLUME,
            AxQsControl.VOLUME_HORIZONTAL ->
                AxQsVolumeControl(
                    vertical = control.isVerticalSlider,
                    verticalStyle = verticalSliderStyle,
                    viewModel = volumeSliderViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.AUTO_BRIGHTNESS ->
                AxQsBrightnessButton(
                    viewModel = brightnessSliderViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.VOLUME_MUTE ->
                AxQsVolumeMuteButton(
                    viewModel = volumeSliderViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.RINGER ->
                RingerSliderTileContent(
                    shape = axQsControlShape(AxQsControl.RINGER, span),
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.MEDIA ->
                AxMediaPanel(
                    viewModel = mediaViewModel,
                    span = span,
                    mediaViewModelFactory = mediaViewModelFactory,
                    modifier = Modifier.fillMaxSize(),
                    showPlaceholder = true,
                )
        }
    }
}

@Composable
private fun AxTileGridLabel(tile: TileViewModel) {
    val state by tile.state.collectAsStateWithLifecycle(initialValue = tile.currentState)
    Text(
        text = state.label?.toString().orEmpty(),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val QS_ENTRANCE_START = 0.5f
