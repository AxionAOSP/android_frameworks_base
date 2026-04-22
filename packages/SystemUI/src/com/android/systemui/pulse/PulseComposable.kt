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
package com.android.systemui.pulse

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.exp
import kotlinx.coroutines.isActive

private fun mirrorCenterOut(heights: FloatArray): FloatArray {
    val n = heights.size
    if (n <= 2) return heights
    val half = n / 2
    val result = FloatArray(n)
    for (i in 0 until half) {
        result[half - 1 - i] = heights[i]
        result[half + i] = heights[i]
    }
    if (n % 2 != 0) {
        result[n - 1] = heights[half]
    }
    return result
}

private class BarHeightsState(barCount: Int) {
    var currentHeights = FloatArray(barCount) { 2f }
    var targetHeights = FloatArray(barCount) { 2f }
    var lastFrameNanos = 0L

    fun updateTargets(newTargets: FloatArray, mirror: Boolean) {
        val incoming = if (mirror) mirrorCenterOut(newTargets) else newTargets
        if (targetHeights.size != incoming.size) {
            currentHeights = FloatArray(incoming.size) { i ->
                if (i < currentHeights.size) currentHeights[i] else 2f
            }
            targetHeights = FloatArray(incoming.size)
        }
        for (i in incoming.indices) {
            targetHeights[i] = incoming[i]
        }
    }

    fun applySmoothing(frameNanos: Long) {
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameNanos = frameNanos

        val alpha = (1f - exp(-PulseConstants.SMOOTHING_SPEED * dt)).coerceIn(0.01f, 1f)
        for (i in currentHeights.indices) {
            val target = if (i < targetHeights.size) targetHeights[i] else 2f
            currentHeights[i] = currentHeights[i] + alpha * (target - currentHeights[i])
        }
    }
}

@Composable
fun PulseVisualizer(
    state: PulseUiState,
    modifier: Modifier = Modifier
) {
    if (!state.isEnabled || !state.isVisible) return

    val density = LocalDensity.current
    val gapPx = with(density) { PulseConstants.BAR_GAP.toPx() }
    val maxHeightPx = with(density) { PulseConstants.MAX_HEIGHT.toPx() }

    val barHeights = remember { BarHeightsState(state.barCount) }

    barHeights.updateTargets(state.barHeights, mirror = state.style == PulseStyle.CENTER_MIRROR)

    var frameCount by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.isVisible) {
        barHeights.lastFrameNanos = 0L
        while (isActive && state.isVisible) {
            withFrameNanos { nanos ->
                barHeights.applySmoothing(nanos)
                frameCount++
            }
        }
    }

    val currentTime = remember(frameCount) { System.currentTimeMillis() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val barCount = state.barCount
        if (barCount <= 0) return@Canvas

        val barColor = when (state.colorMode) {
            PulseColorMode.LAVALAMP -> {
                val hue = (currentTime / 50) % 360
                val hsv = floatArrayOf(hue.toFloat(), 1f, 1f)
                Color(AndroidColor.HSVToColor((PulseConstants.BAR_ALPHA * 255).toInt(), hsv))
            }
            else -> Color(state.barColor).copy(alpha = PulseConstants.BAR_ALPHA)
        }

        when (state.style) {
            PulseStyle.FADING_BLOCKS -> drawFadingBlocks(state, barHeights.currentHeights, barColor, gapPx, maxHeightPx, currentTime)
            PulseStyle.SOLID_LINE -> drawSolidLine(state, barHeights.currentHeights, barColor, maxHeightPx)
            else -> drawBars(state, barHeights.currentHeights, barColor, gapPx, maxHeightPx)
        }
    }
}

private fun DrawScope.drawBars(
    state: PulseUiState,
    heights: FloatArray,
    barColor: Color,
    gapPx: Float,
    maxHeightPx: Float
) {
    val barCount = state.barCount
    val totalGap = (barCount - 1) * gapPx
    val barWidth = (size.width - totalGap) / barCount
    val fullBarWidth = barWidth + gapPx
    val baseY = size.height

    for (i in 0 until barCount) {
        val rawHeight = if (i < heights.size) heights[i] else 2f
        val height = rawHeight.coerceIn(2f, maxHeightPx)

        val left = i * fullBarWidth
        val top = baseY - height

        if (state.roundedBars) {
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(PulseConstants.CORNER_RADIUS, PulseConstants.CORNER_RADIUS)
            )
        } else {
            drawRect(
                color = barColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, height)
            )
        }
    }
}

private fun DrawScope.drawSolidLine(
    state: PulseUiState,
    heights: FloatArray,
    barColor: Color,
    maxHeightPx: Float
) {
    val barCount = state.barCount
    if (barCount < 2) return
    val stepX = size.width / (barCount - 1).toFloat()
    val baseY = size.height
    val strokeWidthPx = 6f

    val topPath = Path()
    val botPath = Path()
    for (i in 0 until barCount) {
        val rawHeight = if (i < heights.size) heights[i] else 2f
        val height = rawHeight.coerceIn(2f, maxHeightPx)
        val x = i * stepX
        val yTop = baseY - height
        val yBot = baseY - (height * 0.35f)
        if (i == 0) {
            topPath.moveTo(x, yTop)
            botPath.moveTo(x, yBot)
        } else {
            topPath.lineTo(x, yTop)
            botPath.lineTo(x, yBot)
        }
    }

    drawPath(
        path = topPath,
        color = barColor,
        style = Stroke(width = strokeWidthPx)
    )
    drawPath(
        path = botPath,
        color = barColor.copy(alpha = barColor.alpha * 0.5f),
        style = Stroke(width = strokeWidthPx * 0.6f)
    )
}

private fun DrawScope.drawFadingBlocks(
    state: PulseUiState,
    heights: FloatArray,
    barColor: Color,
    gapPx: Float,
    maxHeightPx: Float,
    currentTime: Long
) {
    val barCount = state.barCount
    val totalGap = (barCount - 1) * gapPx
    val barWidth = (size.width - totalGap) / barCount
    val fullBarWidth = barWidth + gapPx
    val baseY = size.height

    val blockHeight = 12f
    val blockGap = 4f
    val maxBlocks = (maxHeightPx / (blockHeight + blockGap)).toInt()

    for (i in 0 until barCount) {
        val rawHeight = if (i < heights.size) heights[i] else 0f
        val normalizedHeight = (rawHeight / 500f).coerceIn(0f, 1f)
        val activeBlocks = (normalizedHeight * maxBlocks).toInt().coerceAtLeast(1)
        val left = i * fullBarWidth

        for (block in 0 until activeBlocks) {
            val blockBottom = baseY - (block * (blockHeight + blockGap))
            val blockTop = blockBottom - blockHeight

            val blockRatio = block.toFloat() / maxBlocks
            val fadeAlpha = (1f - blockRatio * 0.7f) * PulseConstants.BAR_ALPHA

            val phaseOffset = (currentTime / 100f + i * 0.5f) % 1f
            val pulse = 1f - (blockRatio - phaseOffset).coerceIn(0f, 0.3f)

            val blockColor = barColor.copy(alpha = fadeAlpha * pulse)

            if (state.roundedBars) {
                drawRoundRect(
                    color = blockColor,
                    topLeft = Offset(left, blockTop),
                    size = Size(barWidth, blockHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            } else {
                drawRect(
                    color = blockColor,
                    topLeft = Offset(left, blockTop),
                    size = Size(barWidth, blockHeight)
                )
            }
        }
    }
}
