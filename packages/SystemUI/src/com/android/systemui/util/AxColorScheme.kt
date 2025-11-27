/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.util

import android.content.Context
import android.content.res.Resources
import android.os.SystemProperties
import android.provider.Settings
import com.android.compose.theme.LocalAndroidColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.android.systemui.res.R
import com.android.settingslib.Utils

@Immutable
data class AxColorScheme(
    val primary: Color,
    val primarySurface: Color,
    val secondary: Color,
    val onPrimary: Color,
    val onSurface: Color,
) {
    companion object {
        @Composable
        @ReadOnlyComposable
        private fun current(): AxColorScheme {
            val context = LocalContext.current
            
            val secondary = LocalAndroidColorScheme.current.surfaceEffect1

            val primary = MaterialTheme.colorScheme.primary
            val primarySurface = primary.copy(alpha = 0.8f)
            val onPrimary = MaterialTheme.colorScheme.onPrimary
            val onSurface = MaterialTheme.colorScheme.onSurface

            return AxColorScheme(
                primary = primary,
                primarySurface = primarySurface,
                secondary = secondary,
                onPrimary = onPrimary,
                onSurface = onSurface,
            )
        }

        val primary: Color
            @Composable
            @ReadOnlyComposable
            get() = current().primary

        val primarySurface: Color
            @Composable
            @ReadOnlyComposable
            get() = current().primarySurface

        val secondary: Color
            @Composable
            @ReadOnlyComposable
            get() = current().secondary

        val onPrimary: Color
            @Composable
            @ReadOnlyComposable
            get() = current().onPrimary
            
        val onSurface: Color
            @Composable
            @ReadOnlyComposable
            get() = current().onSurface
    }
}
