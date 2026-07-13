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
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SliderPreference
import com.android.axion.compose.preferences.SwitchPreference
import com.android.systemui.qs.ax.shared.model.AxQsGridLayout
import com.android.systemui.qs.ax.shared.model.AxQsGridSection
import com.android.systemui.qs.ax.shared.model.AxQsPanelMode
import com.android.systemui.qs.ax.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import kotlin.math.roundToInt

@Composable
internal fun AxQsPanelSettings(
    viewModel: AxQsViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    QuickSettingsTheme {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
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
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.accessibility_back),
                            )
                        }
                        Text(
                            text = stringResource(R.string.ax_qs_panel_settings),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (!landscape) {
                        Text(
                            text = stringResource(R.string.ax_qs_panel_mode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        PanelModeRow(
                            selected = viewModel.panelMode == AxQsPanelMode.TOGETHER,
                            title = stringResource(R.string.ax_qs_together),
                            summary = stringResource(R.string.ax_qs_together_summary),
                        ) {
                            viewModel.setPanelMode(AxQsPanelMode.TOGETHER)
                        }
                        PanelModeRow(
                            selected = viewModel.panelMode == AxQsPanelMode.SEPARATE,
                            title = stringResource(R.string.ax_qs_separate),
                            summary =
                                stringResource(
                                    if (viewModel.quickPanelOnLeft) {
                                        R.string.ax_qs_separate_summary_left
                                    } else {
                                        R.string.ax_qs_separate_summary_right
                                    }
                                ),
                        ) {
                            viewModel.setPanelMode(AxQsPanelMode.SEPARATE)
                        }
                        if (viewModel.panelMode == AxQsPanelMode.SEPARATE) {
                            SettingSwitchRow(
                                label = stringResource(R.string.ax_qs_quick_panel_left),
                                checked = viewModel.quickPanelOnLeft,
                                onCheckedChange = viewModel::setQuickPanelOnLeft,
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PanelModeRow(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(AxTileDefaults.backgroundColor(), RoundedCornerShape(24.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (selected) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun AxQsGridSettings(
    controlLayout: AxQsGridLayout,
    tileLayout: AxQsGridLayout,
    viewModel: AxQsViewModel,
) {
    val colorScheme = MaterialTheme.colorScheme.copy(surfaceBright = Color.Transparent)
    MaterialTheme(colorScheme = colorScheme) {
        PreferenceGroup {
            if (tileLayout == AxQsGridLayout.PORTRAIT_QS_TILES) {
                item {
                    SwitchPreference(
                        title = stringResource(R.string.ax_qs_show_tile_labels),
                        checked = viewModel.showTileLabels(tileLayout),
                        onCheckedChange = { viewModel.setTileLabels(tileLayout, it) },
                    )
                }
            }
            item { GridColumnSlider(layout = controlLayout, viewModel = viewModel) }
            item { GridColumnSlider(layout = tileLayout, viewModel = viewModel) }
            if (viewModel.rowRange(tileLayout) != null) {
                item { GridRowSlider(layout = tileLayout, viewModel = viewModel) }
            }
        }
    }
}

@Composable
private fun GridColumnSlider(layout: AxQsGridLayout, viewModel: AxQsViewModel) {
    val label =
        when (layout.section) {
            AxQsGridSection.CONTROLS -> stringResource(R.string.ax_qs_control_grid_columns)
            AxQsGridSection.TILES -> stringResource(R.string.ax_qs_tile_grid_columns)
        }
    GridSizeSlider(
        label = label,
        savedValue = viewModel.columns(layout),
        range = viewModel.columnRange(layout),
        valueLabel = R.string.ax_qs_column_count,
        onValueChangeFinished = { viewModel.setColumns(layout, it) },
    )
}

@Composable
private fun GridRowSlider(layout: AxQsGridLayout, viewModel: AxQsViewModel) {
    val range = viewModel.rowRange(layout) ?: return
    GridSizeSlider(
        label = stringResource(R.string.ax_qs_tile_grid_rows),
        savedValue = viewModel.rows(layout),
        range = range,
        valueLabel = R.string.ax_qs_row_count,
        onValueChangeFinished = { viewModel.setRows(layout, it) },
    )
}

@Composable
private fun GridSizeSlider(
    label: String,
    savedValue: Int,
    range: IntRange,
    @StringRes valueLabel: Int,
    onValueChangeFinished: (Int) -> Unit,
) {
    var sliderValue by remember(savedValue, range) { mutableFloatStateOf(savedValue.toFloat()) }
    val selectedValue = sliderValue.roundToInt().coerceIn(range.first, range.last)
    SliderPreference(
        title = label,
        summary = "",
        value = sliderValue,
        onValueChange = {
            sliderValue = it.roundToInt().coerceIn(range.first, range.last).toFloat()
        },
        onValueChangeFinished = {
            onValueChangeFinished(sliderValue.roundToInt().coerceIn(range.first, range.last))
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.count() - 2).coerceAtLeast(0),
        displayValue = stringResource(valueLabel, selectedValue),
    )
}
