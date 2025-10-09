/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.lockscreen

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf

class WidgetStates {
    private val _activeStates: SnapshotStateMap<WidgetAction, MutableState<Boolean>> =
        mutableStateMapOf()

    fun setActive(action: WidgetAction, active: Boolean) {
        val state = _activeStates.getOrPut(action) { mutableStateOf(active) }
        if (state.value != active) {
            state.value = active
        }
    }

    fun isActive(action: WidgetAction): Boolean = _activeStates[action]?.value ?: false

    fun getState(action: WidgetAction): MutableState<Boolean> =
        _activeStates.getOrPut(action) { mutableStateOf(false) }
}
