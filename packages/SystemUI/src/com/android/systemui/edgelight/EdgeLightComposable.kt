/*
 * Copyright (C) 2025-2026 AxionOS
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

@Composable
fun EdgeLight(
    state: EdgeLightUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.isEnabled || !state.isVisible) return

    val color = Color(state.color)

    Canvas(modifier = modifier.fillMaxSize()) {
        drawEdgeGlow(color, state.pulseAlpha, state.spread, state.intensity, fromStart = true)
        drawEdgeGlow(color, state.pulseAlpha, state.spread, state.intensity, fromStart = false)
    }
}

private fun DrawScope.drawEdgeGlow(
    color: Color,
    alpha: Float,
    spread: Float,
    intensity: Float,
    fromStart: Boolean,
) {
    val a = (alpha * intensity).coerceIn(0f, 1f)
    val s = spread.coerceIn(0.05f, 1f)

    val p0 = 0.00f
    val p1 = s * 0.10f
    val p2 = s * 0.30f
    val p3 = s * 0.60f
    val p4 = s

    val brush = if (fromStart) {
        Brush.horizontalGradient(
            p0 to color.copy(alpha = a),
            p1 to color.copy(alpha = a * 0.65f),
            p2 to color.copy(alpha = a * 0.28f),
            p3 to color.copy(alpha = a * 0.08f),
            p4 to Color.Transparent,
            startX = 0f,
            endX = size.width,
        )
    } else {
        Brush.horizontalGradient(
            (1f - p4) to Color.Transparent,
            (1f - p3) to color.copy(alpha = a * 0.08f),
            (1f - p2) to color.copy(alpha = a * 0.28f),
            (1f - p1) to color.copy(alpha = a * 0.65f),
            (1f - p0) to color.copy(alpha = a),
            startX = 0f,
            endX = size.width,
        )
    }
    drawRect(brush = brush)
}
