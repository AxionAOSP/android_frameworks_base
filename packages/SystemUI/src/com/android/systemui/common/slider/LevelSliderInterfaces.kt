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
package com.android.systemui.common.slider

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LevelSliderTheme {
    @get:Composable
    val activeBg: Color
    
    @get:Composable
    val neutralBg: Color
    
    @get:Composable
    val activeIcon: Color
    
    @get:Composable
    val neutralIcon: Color
    
    @get:Composable
    val labelColor: Color
    
    val dozeStroke: Dp
}

interface LevelSliderDimens {
    val totalWidth: Dp?
    val height: Dp
    val iconSize: Dp
    val horizontalPadding: Dp
    val labelPadding: Dp
}

interface LevelSliderInteractor {
    val spec: String?
    val level: Flow<Float>
    
    val isActiveFlow: StateFlow<Boolean>
    
    fun getCurrentLevel(): Float
    fun setLevel(level: Float)
    fun isActive(): Boolean
    fun onTap(enabled: Boolean)

    @Composable
    fun getIcon(level: Float): ImageVector

    @Composable
    fun getLabel(level: Float): String
}
