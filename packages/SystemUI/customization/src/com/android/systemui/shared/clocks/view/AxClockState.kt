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

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class AxClockState {

    val dozeFlow = MutableStateFlow(false)
    val screenOffFlow = MutableStateFlow(false)
    val regionDarkFlow = MutableStateFlow(false)
    val dozeAmountFlow = MutableStateFlow(0f)
    val dateStrFlow = MutableStateFlow("")

    val timeState = mutableStateOf("")
    val fidgetTrigger = mutableStateOf(0L)
    val fidgetPosition = mutableStateOf(Offset.Zero)
    val dateBelowState = mutableStateOf(false)
    val alignmentState = mutableStateOf(ClockSettingsRepository.alignment.value)
    val clockColorOverrideState = mutableStateOf(ClockSettingsRepository.clockColorOverride.value)
    val fontVersion = mutableIntStateOf(0)

    var isDoze: Boolean
        get() = dozeFlow.value
        set(value) { dozeFlow.value = value }
    var isScreenOff: Boolean
        get() = screenOffFlow.value
        set(value) { screenOffFlow.value = value }
    var isRegionDark: Boolean
        get() = regionDarkFlow.value
        set(value) { regionDarkFlow.value = value }

    val dateStr: String get() = dateStrFlow.value
}
