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

package com.android.systemui.qs.ax.ui.viewmodel

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.ax.data.repository.AxQsSettingsRepository
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsGridLayout
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsLandscapeConfig
import com.android.systemui.qs.ax.shared.model.AxQsLayout
import com.android.systemui.qs.ax.shared.model.AxQsLayoutPadding
import com.android.systemui.qs.ax.shared.model.AxQsPanelMode
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderKey
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.map

class AxQsViewModel
@Inject
constructor(
    private val repository: AxQsSettingsRepository,
    configurationInteractor: ConfigurationInteractor,
    shadeInteractor: ShadeInteractor,
    @ShadeDisplayAware private val context: Context,
) : ExclusiveActivatable() {
    private val hydrator = Hydrator("AxQsViewModel")
    private val defaultTileIds = QSHost.getDefaultSpecs(context.resources)
    private val portraitDefaultColumns by
        hydrator.hydratedStateOf(
            traceName = "portraitDefaultColumns",
            initialValue = portraitResourceColumns(context.resources.configuration),
            source =
                configurationInteractor.configurationValues.map { configuration ->
                    portraitResourceColumns(configuration)
                },
        )
    private val screenWidthDp by
        hydrator.hydratedStateOf(
            traceName = "screenWidthDp",
            initialValue = context.resources.configuration.screenWidthDp,
            source =
                configurationInteractor.configurationValues.map { configuration ->
                    configuration.screenWidthDp
                },
        )
    private val landscapeConfig by
        hydrator.hydratedStateOf(
            traceName = "landscapeConfig",
            initialValue =
                AxQsLandscapeConfig.fromSmallestWidth(
                    context.resources.configuration.smallestScreenWidthDp
                ),
            source =
                configurationInteractor.configurationValues.map { configuration ->
                    AxQsLandscapeConfig.fromSmallestWidth(configuration.smallestScreenWidthDp)
                },
        )

    private val qsOrder by
        hydrator.hydratedStateOf(
            traceName = "qsOrder",
            initialValue = null,
            source = repository.qsOrder,
        )
    private val qqsOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsOrder",
            initialValue = null,
            source = repository.qqsOrder,
        )
    private val qsSpans by
        hydrator.hydratedStateOf(
            traceName = "qsSpans",
            initialValue = emptyMap(),
            source = repository.qsSpans,
        )
    private val qqsSpans by
        hydrator.hydratedStateOf(
            traceName = "qqsSpans",
            initialValue = emptyMap(),
            source = repository.qqsSpans,
        )
    private val landscapeOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeOrder",
            initialValue = null,
            source = repository.landscapeOrder,
        )
    private val qqsControlOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsControlOrder",
            initialValue = null,
            source = repository.qqsControlOrder,
        )
    private val qqsTileOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsTileOrder",
            initialValue = null,
            source = repository.qqsTileOrder,
        )
    private val qsControlOrder by
        hydrator.hydratedStateOf(
            traceName = "qsControlOrder",
            initialValue = null,
            source = repository.qsControlOrder,
        )
    private val qsTileOrder by
        hydrator.hydratedStateOf(
            traceName = "qsTileOrder",
            initialValue = null,
            source = repository.qsTileOrder,
        )
    private val landscapeControlOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeControlOrder",
            initialValue = null,
            source = repository.landscapeControlOrder,
        )
    private val landscapeTileOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeTileOrder",
            initialValue = null,
            source = repository.landscapeTileOrder,
        )
    private val qqsControlPositions by
        hydrator.hydratedStateOf(
            traceName = "qqsControlPositions",
            initialValue = emptyMap(),
            source = repository.qqsControlPositions,
        )
    private val qsControlPositions by
        hydrator.hydratedStateOf(
            traceName = "qsControlPositions",
            initialValue = emptyMap(),
            source = repository.qsControlPositions,
        )
    private val landscapeControlPositions by
        hydrator.hydratedStateOf(
            traceName = "landscapeControlPositions",
            initialValue = emptyMap(),
            source = repository.landscapeControlPositions,
        )
    private val landscapeSpans by
        hydrator.hydratedStateOf(
            traceName = "landscapeSpans",
            initialValue = emptyMap(),
            source = repository.landscapeSpans,
        )
    val panelMode by
        hydrator.hydratedStateOf(
            traceName = "panelMode",
            initialValue = AxQsPanelMode.TOGETHER,
            source = repository.panelMode,
        )
    val isQsBypassingShade by
        hydrator.hydratedStateOf(
            traceName = "isQsBypassingShade",
            initialValue = false,
            source = shadeInteractor.isQsBypassingShade,
        )
    var holdQsSceneDuringCollapse by mutableStateOf(false)
        private set

    val quickPanelOnLeft by
        hydrator.hydratedStateOf(
            traceName = "quickPanelOnLeft",
            initialValue = false,
            source = repository.quickPanelOnLeft,
        )
    private val verticalSliderStyles by
        hydrator.hydratedStateOf(
            traceName = "verticalSliderStyles",
            initialValue = emptyMap(),
            source = repository.verticalSliderStyles,
        )
    private val portraitQqsColumns by
        hydrator.hydratedStateOf(
            traceName = "portraitQqsColumns",
            initialValue = null,
            source = repository.portraitQqsColumns,
        )
    private val portraitQsColumns by
        hydrator.hydratedStateOf(
            traceName = "portraitQsColumns",
            initialValue = null,
            source = repository.portraitQsColumns,
        )
    private val landscapeQqsColumns by
        hydrator.hydratedStateOf(
            traceName = "landscapeQqsColumns",
            initialValue = null,
            source = repository.landscapeQqsColumns,
        )
    private val landscapeQsColumns by
        hydrator.hydratedStateOf(
            traceName = "landscapeQsColumns",
            initialValue = null,
            source = repository.landscapeQsColumns,
        )
    private val gridColumns by
        hydrator.hydratedStateOf(
            traceName = "gridColumns",
            initialValue = emptyMap(),
            source = repository.gridColumns,
        )
    private val gridRows by
        hydrator.hydratedStateOf(
            traceName = "gridRows",
            initialValue = emptyMap(),
            source = repository.gridRows,
        )
    private val tileLabels by
        hydrator.hydratedStateOf(
            traceName = "tileLabels",
            initialValue = emptyMap(),
            source = repository.tileLabels,
        )

    fun columns(layout: AxQsGridLayout): Int {
        val default = defaultColumns(layout)
        return (gridColumns[layout] ?: legacyColumns(layout) ?: default).coerceIn(
            columnRange(layout)
        )
    }

    fun columnRange(layout: AxQsGridLayout): IntRange {
        val configuredRange =
            if (layout.isLandscape) {
                when (layout.section) {
                    AxQsGridSection.CONTROLS ->
                        landscapeConfig.controlMinColumns..landscapeConfig.controlMaxColumns
                    AxQsGridSection.TILES ->
                        landscapeConfig.tileMinColumns..landscapeConfig.tileMaxColumns
                }
            } else {
                layout.columnRange(defaultColumns(layout))
            }
        val safeMax = maxColumnsForWidth(layout)
        val last = minOf(configuredRange.last, safeMax).coerceAtLeast(configuredRange.first)
        return configuredRange.first..last
    }

    fun rows(layout: AxQsGridLayout): Int {
        val default = defaultRows(layout)
        val range = rowRange(layout) ?: return default
        return gridRows[layout]?.coerceIn(range) ?: default
    }

    fun defaultRows(layout: AxQsGridLayout): Int {
        if (layout.isQqs) {
            return if (layout.section == AxQsGridSection.CONTROLS) {
                DEFAULT_PORTRAIT_QQS_CONTROL_ROWS
            } else {
                DEFAULT_PORTRAIT_QQS_TILE_ROWS
            }
        }
        if (!layout.isLandscape) {
            return if (layout.section == AxQsGridSection.CONTROLS) {
                DEFAULT_PORTRAIT_QS_CONTROL_ROWS
            } else {
                DEFAULT_PORTRAIT_QS_TILE_ROWS
            }
        }
        return when (layout.section) {
            AxQsGridSection.CONTROLS -> landscapeConfig.controlRows
            AxQsGridSection.TILES -> landscapeConfig.tileDefaultRows
        }
    }

    fun rowRange(layout: AxQsGridLayout): IntRange? {
        if (!layout.isLandscape) return layout.rowRange
        return if (!layout.isQqs && layout.section == AxQsGridSection.TILES) {
            1..landscapeConfig.tileMaxRows
        } else {
            null
        }
    }

    fun showTileLabels(layout: AxQsGridLayout): Boolean {
        return tileLabels[layout] ?: layout.showTileLabelsByDefault
    }

    fun orderedIds(
        layout: AxQsLayout,
        section: AxQsGridSection,
        availableIds: List<String>,
        defaultIds: List<String>,
    ): List<String> {
        val available = availableIds.toSet()
        val saved = configuredSectionOrder(layout, section)
        if (saved != null) return saved.filter(available::contains)
        val legacy = configuredOrder(layout)
        if (legacy != null) {
            return legacy.filter { id ->
                id in available && sectionForLegacyId(id, layout) == section
            }
        }
        val controlDefaults = defaultControlIds(available)
        return if (section == AxQsGridSection.CONTROLS) {
            controlDefaults
        } else {
            (defaultTileIds + defaultIds).distinct().filter {
                it in available && it !in controlDefaults
            }
        }
    }

    fun order(layout: AxQsLayout, section: AxQsGridSection): List<String>? {
        return configuredSectionOrder(layout, section)
            ?: configuredOrder(layout)?.filter { sectionForLegacyId(it, layout) == section }
    }

    fun isInGrid(id: String, layout: AxQsLayout, section: AxQsGridSection): Boolean {
        val order = order(layout, section)
        if (order != null) return id in order
        return section == AxQsGridSection.CONTROLS && id in DEFAULT_CONTROL_IDS
    }

    private fun configuredOrder(layout: AxQsLayout): List<String>? {
        return when (layout) {
            AxQsLayout.QQS -> qqsOrder
            AxQsLayout.QS -> qsOrder
            AxQsLayout.LANDSCAPE -> landscapeOrder
        }
    }

    private fun configuredSectionOrder(
        layout: AxQsLayout,
        section: AxQsGridSection,
    ): List<String>? {
        return when (layout) {
            AxQsLayout.QQS ->
                if (section == AxQsGridSection.CONTROLS) qqsControlOrder else qqsTileOrder
            AxQsLayout.QS ->
                if (section == AxQsGridSection.CONTROLS) qsControlOrder else qsTileOrder
            AxQsLayout.LANDSCAPE ->
                if (section == AxQsGridSection.CONTROLS) {
                    landscapeControlOrder
                } else {
                    landscapeTileOrder
                }
        }
    }

    fun spans(layout: AxQsLayout): Map<String, AxQsSpan> {
        return when (layout) {
            AxQsLayout.QQS -> qqsSpans
            AxQsLayout.QS -> qsSpans
            AxQsLayout.LANDSCAPE -> landscapeSpans
        }
    }

    fun span(id: String, layout: AxQsLayout, default: AxQsSpan): AxQsSpan {
        return if (
            configuredSectionOrder(layout, AxQsGridSection.CONTROLS) == null &&
                configuredOrder(layout) == null
        ) {
            default
        } else {
            spans(layout)[id] ?: default
        }
    }

    fun setOrder(order: List<String>, layout: AxQsLayout, section: AxQsGridSection) {
        repository.setOrder(order, layout, section)
    }

    fun controlPositions(layout: AxQsLayout): Map<String, AxQsGridPosition> {
        return when (layout) {
            AxQsLayout.QQS -> qqsControlPositions
            AxQsLayout.QS -> qsControlPositions
            AxQsLayout.LANDSCAPE -> landscapeControlPositions
        }
    }

    fun setControlPositions(positions: Map<String, AxQsGridPosition>, layout: AxQsLayout) {
        repository.setControlPositions(positions, layout)
    }

    fun setSpan(id: String, span: AxQsSpan, layout: AxQsLayout, columns: Int) {
        val control = AxQsControl.entries.firstOrNull { it.id == id }
        repository.setSpan(
            id,
            control?.coerceSpan(span, columns) ?: span.coerceForControlTile(columns),
            layout,
        )
    }

    fun setPanelMode(mode: AxQsPanelMode) = repository.setPanelMode(mode)

    fun setForceQsEvent(forceQsEvent: Boolean) {
        holdQsSceneDuringCollapse = forceQsEvent
    }

    fun clearCollapseGuard() {
        holdQsSceneDuringCollapse = false
    }

    fun setQuickPanelOnLeft(onLeft: Boolean) = repository.setQuickPanelOnLeft(onLeft)

    fun verticalSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
    ): AxQsVerticalSliderStyle =
        verticalSliderStyles[AxQsVerticalSliderKey(layout, control)]
            ?: AxQsVerticalSliderStyle.M3_EXPRESSIVE

    fun setVerticalSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
        style: AxQsVerticalSliderStyle,
    ) = repository.setVerticalSliderStyle(layout, control, style)

    fun setColumns(layout: AxQsGridLayout, columns: Int) {
        repository.setColumns(layout, columns.coerceIn(columnRange(layout)))
    }

    fun setRows(layout: AxQsGridLayout, rows: Int) {
        val range = rowRange(layout) ?: return
        repository.setRows(layout, rows.coerceIn(range))
    }

    fun setTileLabels(layout: AxQsGridLayout, showLabels: Boolean) {
        if (layout == AxQsGridLayout.PORTRAIT_QS_TILES) {
            repository.setTileLabels(layout, showLabels)
        }
    }

    override suspend fun onActivated(): Nothing = hydrator.activate()

    private fun legacyColumns(layout: AxQsGridLayout): Int? {
        return when {
            layout.isLandscape && layout.isQqs -> landscapeQqsColumns
            layout.isLandscape -> landscapeQsColumns
            layout.isQqs -> portraitQqsColumns
            else -> portraitQsColumns
        }
    }

    fun defaultColumns(layout: AxQsGridLayout): Int {
        return if (layout.isLandscape) AxQsLandscapeConfig.DEFAULT_COLUMNS
        else portraitDefaultColumns
    }

    private fun defaultControlIds(available: Set<String>): List<String> {
        val network = DEFAULT_NETWORK_IDS.firstOrNull(available::contains)
        return listOfNotNull(
                network,
                BLUETOOTH_TILE_ID,
                AxQsControl.MEDIA.id,
                AxQsControl.BRIGHTNESS.id,
                AxQsControl.VOLUME.id,
            )
            .filter(available::contains)
            .distinct()
    }

    private fun sectionForLegacyId(id: String, layout: AxQsLayout): AxQsGridSection {
        if (AxQsControl.entries.any { it.id == id }) return AxQsGridSection.CONTROLS
        return if ((spans(layout)[id] ?: AxQsSpan.TileDefault) == AxQsSpan.TileDefault) {
            AxQsGridSection.TILES
        } else {
            AxQsGridSection.CONTROLS
        }
    }

    private fun portraitResourceColumns(configuration: Configuration): Int {
        val orientedConfiguration =
            Configuration(configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        val orientedResources = context.createConfigurationContext(orientedConfiguration).resources
        return orientedResources.getInteger(R.integer.quick_settings_infinite_grid_num_columns)
    }

    private fun maxColumnsForWidth(layout: AxQsGridLayout): Int {
        val sideFraction =
            if (layout.isLandscape) AxQsLayoutPadding.LANDSCAPE_SIDE_FRACTION
            else AxQsLayoutPadding.PORTRAIT_SIDE_FRACTION
        val contentWidth = screenWidthDp * (1f - sideFraction * 2f)
        val gridWidth =
            if (layout.isLandscape) {
                (contentWidth - AxQsLayoutPadding.LANDSCAPE_SPLIT_GRID_SPACING_DP) / 2f
            } else {
                contentWidth
            }
        return ((gridWidth + GRID_SPACING_DP) / (MIN_TILE_WIDTH_DP + GRID_SPACING_DP))
            .toInt()
            .coerceAtLeast(1)
    }

    private companion object {
        const val MIN_TILE_WIDTH_DP = 56f
        const val GRID_SPACING_DP = 16f
        const val DEFAULT_PORTRAIT_QQS_CONTROL_ROWS = 3
        const val DEFAULT_PORTRAIT_QQS_TILE_ROWS = 1
        const val DEFAULT_PORTRAIT_QS_CONTROL_ROWS = 6
        const val DEFAULT_PORTRAIT_QS_TILE_ROWS = 3
        val DEFAULT_NETWORK_IDS = listOf("wifi", "internet")
        val DEFAULT_CONTROL_IDS =
            setOf(
                BLUETOOTH_TILE_ID,
                AxQsControl.MEDIA.id,
                AxQsControl.BRIGHTNESS.id,
                AxQsControl.VOLUME.id,
            )
        const val BLUETOOTH_TILE_ID = "bt"
    }
}
