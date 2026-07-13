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

package com.android.systemui.qs.ax.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsGridLayout
import com.android.systemui.qs.ax.shared.model.AxQsGridPosition
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsLayout
import com.android.systemui.qs.ax.shared.model.AxQsPanelMode
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderKey
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@SysUISingleton
class AxQsSettingsRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val userRepository: UserRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    val qsOrder: Flow<List<String>?> = stringSetting(QS_ORDER).map(::parseOrder)
    val qqsOrder: Flow<List<String>?> = stringSetting(QQS_ORDER).map(::parseOrder)
    val qsSpans: Flow<Map<String, AxQsSpan>> =
        stringSetting(QS_SPANS).map(::parseSpans)
    val qqsSpans: Flow<Map<String, AxQsSpan>> =
        stringSetting(QQS_SPANS).map(::parseSpans)
    val landscapeOrder: Flow<List<String>?> =
        stringSetting(LANDSCAPE_ORDER).map(::parseOrder)
    val qqsControlOrder: Flow<List<String>?> =
        stringSetting(QQS_CONTROL_ORDER).map(::parseOrder)
    val qqsTileOrder: Flow<List<String>?> =
        stringSetting(QQS_TILE_ORDER).map(::parseOrder)
    val qsControlOrder: Flow<List<String>?> =
        stringSetting(QS_CONTROL_ORDER).map(::parseOrder)
    val qsTileOrder: Flow<List<String>?> =
        stringSetting(QS_TILE_ORDER).map(::parseOrder)
    val landscapeControlOrder: Flow<List<String>?> =
        stringSetting(LANDSCAPE_CONTROL_ORDER).map(::parseOrder)
    val landscapeTileOrder: Flow<List<String>?> =
        stringSetting(LANDSCAPE_TILE_ORDER).map(::parseOrder)
    val qqsControlPositions: Flow<Map<String, AxQsGridPosition>> =
        stringSetting(QQS_CONTROL_POSITIONS).map(::parsePositions)
    val qsControlPositions: Flow<Map<String, AxQsGridPosition>> =
        stringSetting(QS_CONTROL_POSITIONS).map(::parsePositions)
    val landscapeControlPositions: Flow<Map<String, AxQsGridPosition>> =
        stringSetting(LANDSCAPE_CONTROL_POSITIONS).map(::parsePositions)
    val landscapeSpans: Flow<Map<String, AxQsSpan>> =
        stringSetting(LANDSCAPE_SPANS).map(::parseSpans)
    val panelMode: Flow<AxQsPanelMode> =
        intSetting(PANEL_MODE, AxQsPanelMode.TOGETHER.settingValue)
            .map(AxQsPanelMode::fromSetting)
    val quickPanelOnLeft: Flow<Boolean> = boolSetting(QUICK_PANEL_ON_LEFT, false)
    val verticalSliderStyles: Flow<Map<AxQsVerticalSliderKey, AxQsVerticalSliderStyle>> =
        verticalSliderStyleSettings()
    val portraitQqsColumns: Flow<Int?> = optionalIntSetting(PORTRAIT_QQS_COLUMNS)
    val portraitQsColumns: Flow<Int?> = optionalIntSetting(PORTRAIT_QS_COLUMNS)
    val landscapeQqsColumns: Flow<Int?> = optionalIntSetting(LANDSCAPE_QQS_COLUMNS)
    val landscapeQsColumns: Flow<Int?> = optionalIntSetting(LANDSCAPE_QS_COLUMNS)
    val gridColumns: Flow<Map<AxQsGridLayout, Int>> =
        gridSettings(AxQsGridLayout.entries, ::gridColumnsKey)
    val gridRows: Flow<Map<AxQsGridLayout, Int>> =
        gridSettings(GRID_ROW_KEYS.keys) { layout -> GRID_ROW_KEYS.getValue(layout) }
    val tileLabels: Flow<Map<AxQsGridLayout, Boolean>> = tileLabelSettings()

    fun setOrder(order: List<String>, layout: AxQsLayout, section: AxQsGridSection) {
        putString(sectionOrderKey(layout, section), order.distinct())
    }

    fun setSpan(id: String, span: AxQsSpan, layout: AxQsLayout) {
        val userId = userRepository.getSelectedUserInfo().id
        val key = spansKey(layout)
        val spans = parseSpans(secureSettings.getStringForUser(key, userId)).toMutableMap()
        spans[id] = span
        val value = spans.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        putString(key, value, userId)
    }

    fun setControlPositions(positions: Map<String, AxQsGridPosition>, layout: AxQsLayout) {
        val value =
            positions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        putString(controlPositionsKey(layout), value)
    }

    fun setPanelMode(mode: AxQsPanelMode) {
        putInt(PANEL_MODE, mode.settingValue)
    }

    fun setQuickPanelOnLeft(onLeft: Boolean) {
        putInt(QUICK_PANEL_ON_LEFT, onLeft.toSetting())
    }

    fun setVerticalSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
        style: AxQsVerticalSliderStyle,
    ) {
        putInt(verticalSliderStyleKey(AxQsVerticalSliderKey(layout, control)), style.settingValue)
    }

    fun setColumns(layout: AxQsGridLayout, columns: Int) {
        putInt(gridColumnsKey(layout), columns)
    }

    fun setRows(layout: AxQsGridLayout, rows: Int) {
        putInt(GRID_ROW_KEYS.getValue(layout), rows)
    }

    fun setTileLabels(layout: AxQsGridLayout, showLabels: Boolean) {
        putInt(TILE_LABEL_KEYS.getValue(layout), showLabels.toSetting())
    }

    private fun stringSetting(key: String): Flow<String?> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(user.id, key)
                    .onStart { emit(Unit) }
                    .map { secureSettings.getStringForUser(key, user.id) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun intSetting(key: String, default: Int): Flow<Int> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(user.id, key)
                    .onStart { emit(Unit) }
                    .map { secureSettings.getIntForUser(key, default, user.id) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun boolSetting(key: String, default: Boolean): Flow<Boolean> {
        return intSetting(key, default.toSetting()).map { it != 0 }
    }

    private fun optionalIntSetting(key: String): Flow<Int?> {
        return intSetting(key, 0).map { it.takeIf { value -> value > 0 } }
    }

    private fun gridSettings(
        layouts: Iterable<AxQsGridLayout>,
        keyForLayout: (AxQsGridLayout) -> String,
    ): Flow<Map<AxQsGridLayout, Int>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    layouts.map { layout ->
                        val key = keyForLayout(layout)
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map { layout to secureSettings.getIntForUser(key, 0, user.id) }
                    }
                ) { values ->
                    values
                        .mapNotNull { (layout, value) ->
                            value.takeIf { it > 0 }?.let { layout to it }
                        }
                        .toMap()
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun tileLabelSettings(): Flow<Map<AxQsGridLayout, Boolean>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    TILE_LABEL_KEYS.map { (layout, key) ->
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                layout to
                                    (secureSettings.getIntForUser(
                                        key,
                                        layout.showTileLabelsByDefault.toSetting(),
                                        user.id,
                                    ) != 0)
                            }
                    }
                ) { values ->
                    values.toMap()
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun verticalSliderStyleSettings():
        Flow<Map<AxQsVerticalSliderKey, AxQsVerticalSliderStyle>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    VERTICAL_SLIDER_KEYS.map { slider ->
                        val key = verticalSliderStyleKey(slider)
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                slider to
                                    AxQsVerticalSliderStyle.fromSetting(
                                        secureSettings.getIntForUser(
                                            key,
                                            AxQsVerticalSliderStyle.M3_EXPRESSIVE.settingValue,
                                            user.id,
                                        )
                                    )
                            }
                    }
                ) { values -> values.toMap() }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    private fun putString(key: String, values: List<String>) {
        putString(key, values.filter(String::isNotBlank).joinToString(","))
    }

    private fun putString(
        key: String,
        value: String,
        userId: Int = userRepository.getSelectedUserInfo().id,
    ) {
        secureSettings.putStringForUser(key, value, null, false, userId, true)
    }

    private fun putInt(key: String, value: Int) {
        secureSettings.putIntForUser(key, value, userRepository.getSelectedUserInfo().id)
    }

    private fun parseOrder(value: String?): List<String>? {
        return value
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::normalizeControlId)
            ?.distinct()
    }

    private fun parseSpans(value: String?): Map<String, AxQsSpan> {
        if (value.isNullOrBlank()) return emptyMap()
        return buildMap {
            value.split(',').forEach { entry ->
                val separator = entry.lastIndexOf('=')
                if (separator <= 0 || separator == entry.lastIndex) return@forEach
                val savedId = entry.substring(0, separator).trim()
                val id = normalizeControlId(savedId)
                val span = AxQsSpan.parse(entry.substring(separator + 1).trim())
                if (id.isNotEmpty() && span != null && (savedId == id || !containsKey(id))) {
                    put(id, span)
                }
            }
        }
    }

    private fun parsePositions(value: String?): Map<String, AxQsGridPosition> {
        if (value.isNullOrBlank()) return emptyMap()
        return buildMap {
            value.split(',').forEach { entry ->
                val separator = entry.lastIndexOf('=')
                if (separator <= 0 || separator == entry.lastIndex) return@forEach
                val id = normalizeControlId(entry.substring(0, separator).trim())
                val position = AxQsGridPosition.parse(entry.substring(separator + 1).trim())
                if (id.isNotEmpty() && position != null) put(id, position)
            }
        }
    }

    private fun normalizeControlId(id: String): String {
        return when (id) {
            LEGACY_BRIGHTNESS_VERTICAL_ID -> AxQsControl.BRIGHTNESS.id
            LEGACY_VOLUME_VERTICAL_ID -> AxQsControl.VOLUME.id
            LEGACY_RINGER_TILE_ID -> AxQsControl.RINGER.id
            else -> id
        }
    }

    private fun Boolean.toSetting(): Int = if (this) 1 else 0

    private fun spansKey(layout: AxQsLayout): String {
        return when (layout) {
            AxQsLayout.QQS -> QQS_SPANS
            AxQsLayout.QS -> QS_SPANS
            AxQsLayout.LANDSCAPE -> LANDSCAPE_SPANS
        }
    }

    private fun controlPositionsKey(layout: AxQsLayout): String {
        return when (layout) {
            AxQsLayout.QQS -> QQS_CONTROL_POSITIONS
            AxQsLayout.QS -> QS_CONTROL_POSITIONS
            AxQsLayout.LANDSCAPE -> LANDSCAPE_CONTROL_POSITIONS
        }
    }

    private fun verticalSliderStyleKey(slider: AxQsVerticalSliderKey): String {
        val prefix =
            when (slider.layout) {
                AxQsLayout.QQS -> "ax_qqs"
                AxQsLayout.QS -> "ax_qs"
                AxQsLayout.LANDSCAPE -> "ax_qs_landscape"
            }
        return when (slider.control) {
            AxQsControl.BRIGHTNESS -> "${prefix}_brightness_vertical_slider_style"
            AxQsControl.VOLUME -> "${prefix}_volume_vertical_slider_style"
            else -> error("Only vertical slider controls have style settings")
        }
    }

    private fun sectionOrderKey(layout: AxQsLayout, section: AxQsGridSection): String {
        return when (layout) {
            AxQsLayout.QQS ->
                if (section == AxQsGridSection.CONTROLS) {
                    QQS_CONTROL_ORDER
                } else {
                    QQS_TILE_ORDER
                }
            AxQsLayout.QS ->
                if (section == AxQsGridSection.CONTROLS) {
                    QS_CONTROL_ORDER
                } else {
                    QS_TILE_ORDER
                }
            AxQsLayout.LANDSCAPE ->
                if (section == AxQsGridSection.CONTROLS) {
                    LANDSCAPE_CONTROL_ORDER
                } else {
                    LANDSCAPE_TILE_ORDER
                }
        }
    }

    private fun gridColumnsKey(layout: AxQsGridLayout): String {
        return when (layout) {
            AxQsGridLayout.PORTRAIT_QQS_CONTROLS -> PORTRAIT_QQS_CONTROL_COLUMNS
            AxQsGridLayout.PORTRAIT_QQS_TILES -> PORTRAIT_QQS_TILE_COLUMNS
            AxQsGridLayout.PORTRAIT_QS_CONTROLS -> PORTRAIT_QS_CONTROL_COLUMNS
            AxQsGridLayout.PORTRAIT_QS_TILES -> PORTRAIT_QS_TILE_COLUMNS
            AxQsGridLayout.LANDSCAPE_QQS_CONTROLS -> LANDSCAPE_QQS_CONTROL_COLUMNS
            AxQsGridLayout.LANDSCAPE_QQS_TILES -> LANDSCAPE_QQS_TILE_COLUMNS
            AxQsGridLayout.LANDSCAPE_QS_CONTROLS -> LANDSCAPE_QS_CONTROL_COLUMNS
            AxQsGridLayout.LANDSCAPE_QS_TILES -> LANDSCAPE_QS_TILE_COLUMNS
        }
    }

    private companion object {
        const val QS_ORDER = "ax_qs_order"
        const val QQS_ORDER = "ax_qqs_order"
        const val QS_SPANS = "ax_qs_spans"
        const val QQS_SPANS = "ax_qqs_spans"
        const val LANDSCAPE_ORDER = "ax_qs_landscape_order"
        const val LANDSCAPE_SPANS = "ax_qs_landscape_spans"
        const val QQS_CONTROL_ORDER = "ax_qqs_control_order"
        const val QQS_TILE_ORDER = "ax_qqs_tile_order"
        const val QS_CONTROL_ORDER = "ax_qs_control_order"
        const val QS_TILE_ORDER = "ax_qs_tile_order"
        const val LANDSCAPE_CONTROL_ORDER = "ax_qs_landscape_control_order"
        const val LANDSCAPE_TILE_ORDER = "ax_qs_landscape_tile_order"
        const val QQS_CONTROL_POSITIONS = "ax_qqs_control_positions"
        const val QS_CONTROL_POSITIONS = "ax_qs_control_positions"
        const val LANDSCAPE_CONTROL_POSITIONS = "ax_qs_landscape_control_positions"
        const val PANEL_MODE = "ax_qs_panel_mode"
        const val QUICK_PANEL_ON_LEFT = "ax_qs_quick_panel_on_left"
        const val PORTRAIT_QQS_COLUMNS = "ax_qqs_columns"
        const val PORTRAIT_QS_COLUMNS = "ax_qs_columns"
        const val LANDSCAPE_QQS_COLUMNS = "ax_qqs_landscape_columns"
        const val LANDSCAPE_QS_COLUMNS = "ax_qs_landscape_columns"
        const val PORTRAIT_QQS_CONTROL_COLUMNS = "ax_qqs_control_columns"
        const val PORTRAIT_QQS_TILE_COLUMNS = "ax_qqs_tile_columns"
        const val PORTRAIT_QS_CONTROL_COLUMNS = "ax_qs_control_columns"
        const val PORTRAIT_QS_TILE_COLUMNS = "ax_qs_tile_columns"
        const val LANDSCAPE_QQS_CONTROL_COLUMNS = "ax_qqs_landscape_control_columns"
        const val LANDSCAPE_QQS_TILE_COLUMNS = "ax_qqs_landscape_tile_columns"
        const val LANDSCAPE_QS_CONTROL_COLUMNS = "ax_qs_landscape_control_columns"
        const val LANDSCAPE_QS_TILE_COLUMNS = "ax_qs_landscape_tile_columns"
        const val PORTRAIT_QQS_TILE_ROWS = "ax_qqs_tile_rows"
        const val PORTRAIT_QS_TILE_ROWS = "ax_qs_tile_rows"
        const val LANDSCAPE_QS_TILE_ROWS = "ax_qs_landscape_tile_rows"
        const val PORTRAIT_QS_TILE_LABELS = "ax_qs_show_tile_labels"
        val GRID_ROW_KEYS =
            mapOf(
                AxQsGridLayout.PORTRAIT_QQS_TILES to PORTRAIT_QQS_TILE_ROWS,
                AxQsGridLayout.PORTRAIT_QS_TILES to PORTRAIT_QS_TILE_ROWS,
                AxQsGridLayout.LANDSCAPE_QS_TILES to LANDSCAPE_QS_TILE_ROWS,
            )
        val VERTICAL_SLIDER_KEYS =
            AxQsLayout.entries.flatMap { layout ->
                listOf(AxQsControl.BRIGHTNESS, AxQsControl.VOLUME).map { control ->
                    AxQsVerticalSliderKey(layout, control)
                }
            }
        val TILE_LABEL_KEYS =
            mapOf(AxQsGridLayout.PORTRAIT_QS_TILES to PORTRAIT_QS_TILE_LABELS)
        const val LEGACY_BRIGHTNESS_VERTICAL_ID = "control:brightness_vertical"
        const val LEGACY_VOLUME_VERTICAL_ID = "control:volume_vertical"
        const val LEGACY_RINGER_TILE_ID = "sound"
    }
}
