/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.shared.clocks.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

class AxClockViewModel(
    private val state: AxClockState,
    private val quickLook: QuickLookController,
) {

    @Composable
    fun rememberClockState(): ClockUiState {
        val time by state.timeState
        val isDoze by state.dozeFlow.collectAsState()
        val screenOff by state.screenOffFlow.collectAsState()
        val regionDark by state.regionDarkFlow.collectAsState()
        val colorOverride by state.clockColorOverrideState
        val date by state.dateStrFlow.collectAsState()
        val display = quickLook.rememberResolvedDisplay(date)
        val displayDate = when (display) {
            is DateDisplay.Weather -> {
                if (display.temp.isNotEmpty()) "${display.date} ${display.temp}" else display.date
            }
            is DateDisplay.IconText -> display.text
            is DateDisplay.DateOnly -> display.text
        }
        return ClockUiState(
            time, displayDate, isDoze, screenOff, regionDark,
            display.icon, display.tintIcon, display, colorOverride,
        )
    }

    @Composable
    fun tintColor(isDoze: Boolean, screenOff: Boolean, regionDark: Boolean): Color {
        val override by state.clockColorOverrideState
        if (isDoze || screenOff) return Color.White
        override?.let { return Color(it) }
        return if (regionDark) Color.White else Color.Black
    }

    @Composable
    fun rememberResolvedDisplay(): DateDisplay {
        val date by state.dateStrFlow.collectAsState()
        return quickLook.rememberResolvedDisplay(date)
    }
}
