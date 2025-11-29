/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

@Composable
fun DrawerLayout(
    widthProgress: Float,
    collapsedWidth: Dp,
    expandedWidth: Dp,
    isLeftSide: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val expandedWidthPx = expandedWidth.roundToPx()
        val collapsedWidthPx = collapsedWidth.roundToPx()
        
        val childConstraints = constraints.copy(
            minWidth = expandedWidthPx,
            maxWidth = expandedWidthPx
        )
        val placeable = measurables[0].measure(childConstraints)
        
        val currentWidth = collapsedWidthPx + ((expandedWidthPx - collapsedWidthPx) * widthProgress).roundToInt()
        
        layout(currentWidth, placeable.height) {
            val x = if (isLeftSide) 0 else currentWidth - placeable.width
            placeable.place(x, 0)
        }
    }
}
