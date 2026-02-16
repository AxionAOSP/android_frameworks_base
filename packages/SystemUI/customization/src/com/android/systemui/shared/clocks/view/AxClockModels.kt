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

import android.graphics.Bitmap

data class ClockMediaState(
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    val artistName: String = "",
    val packageName: String = "",
)

data class ClockUiState(
    val time: String = "",
    val date: String = "",
    val isDoze: Boolean = false,
    val screenOff: Boolean = false,
    val regionDark: Boolean = false,
    val icon: Bitmap? = null,
    val tintIcon: Boolean = true,
    val display: DateDisplay = DateDisplay.DateOnly(""),
    val colorOverride: Int? = null,
)
