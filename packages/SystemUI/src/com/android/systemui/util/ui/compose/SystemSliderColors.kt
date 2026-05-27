/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.util.ui.compose

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SystemSliderColors {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun colors(): SliderColors =
        SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            activeTickColor = MaterialTheme.colorScheme.onPrimary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledActiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    fun activeIconColor(colors: SliderColors, enabled: Boolean): Color =
        if (enabled) colors.activeTickColor else colors.disabledActiveTickColor

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    fun inactiveIconColor(colors: SliderColors, enabled: Boolean): Color =
        if (enabled) colors.inactiveTickColor else colors.disabledInactiveTickColor
}
