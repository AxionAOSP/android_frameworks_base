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
package com.android.systemui.edgelight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val EDGE_WIDTH = 12.dp

@Composable
fun EdgeLight(
    state: EdgeLightUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isEnabled || !state.isVisible) return

    val density = LocalDensity.current
    val edgeWidthPx = with(density) { EDGE_WIDTH.toPx() }
    
    val edgeColor = remember(state.color, state.pulseAlpha) {
        Color(state.color).copy(alpha = state.pulseAlpha)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val halfStroke = edgeWidthPx / 2

        // Left edge
        drawLine(
            color = edgeColor,
            start = Offset(halfStroke, 0f),
            end = Offset(halfStroke, size.height),
            strokeWidth = edgeWidthPx,
            cap = StrokeCap.Butt
        )

        // Right edge
        drawLine(
            color = edgeColor,
            start = Offset(size.width - halfStroke, 0f),
            end = Offset(size.width - halfStroke, size.height),
            strokeWidth = edgeWidthPx,
            cap = StrokeCap.Butt
        )
    }
}
