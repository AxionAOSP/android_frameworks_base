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
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.fitToWidth(naturalWidth: Float, marginDp: Float = 16f): Float {
    val marginPx = marginDp * density
    val available = (size.width - 2f * marginPx).coerceAtLeast(1f)
    return if (naturalWidth > available) available / naturalWidth else 1f
}

internal fun DrawScope.drawScaledBitmap(
    bitmap: Bitmap,
    x: Float,
    y: Float,
    scale: Float,
    tint: Color,
) {
    val imageBitmap = bitmap.asImageBitmap()
    val dstWidth = (bitmap.width * scale).toInt()
    val dstHeight = (bitmap.height * scale).toInt()
    drawImage(
        image = imageBitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(x.toInt(), y.toInt()),
        dstSize = IntSize(dstWidth, dstHeight),
        colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
    )
}

internal fun DrawScope.drawDotSeparator(
    x: Float,
    baseY: Float,
    digitHeight: Float,
    dotSize: Float,
    dotMargin: Float,
    dotCenterMargin: Float,
    tint: Color,
): Float {
    val centerX = x + dotCenterMargin
    val radius = dotSize / 2
    val dotY = baseY + digitHeight / 2 - dotMargin / 2

    drawOval(
        color = tint,
        topLeft = Offset(centerX - radius, dotY - radius),
        size = Size(dotSize, dotSize),
    )
    drawOval(
        color = tint,
        topLeft = Offset(centerX - radius, dotY + dotMargin - radius),
        size = Size(dotSize, dotSize),
    )

    return dotCenterMargin * 2
}

internal fun DrawScope.drawDigitLine(
    digits: String,
    bitmaps: Map<Char, Bitmap>,
    scale: Float,
    startX: Float,
    y: Float,
    spacing: Float,
    tint: Color,
) {
    var x = startX
    digits.forEach { char ->
        val bitmap = bitmaps[char] ?: return@forEach
        drawScaledBitmap(bitmap, x, y, scale, tint)
        x += bitmap.width * scale + spacing
    }
}

internal fun drawClockHand(
    canvas: android.graphics.Canvas,
    paint: Paint,
    position: Float,
    thickness: Float,
    startMul: Float,
    endMul: Float,
    color: Int,
    cx: Float,
    cy: Float,
    clockSize: Float,
) {
    val angleRad = Math.toRadians((position * 6 - 90).toDouble())
    val startLen = startMul * clockSize
    val endLen = endMul * clockSize
    val sx = cx - cos(angleRad).toFloat() * startLen
    val sy = cy - sin(angleRad).toFloat() * startLen
    val ex = cx + cos(angleRad).toFloat() * endLen
    val ey = cy + sin(angleRad).toFloat() * endLen
    paint.color = color
    paint.strokeWidth = thickness
    canvas.drawLine(sx, sy, ex, ey, paint)
}

internal fun shouldDrawSeparator(config: BitmapFaceConfig, time: String, index: Int): Boolean {
    if (!config.hasSeparator) return false
    return when (time.length) {
        4 -> index == 1
        3 -> index == 0
        else -> false
    }
}

internal fun shouldUseLightVariant(
    config: BitmapFaceConfig,
    time: String,
    index: Int,
    isDoze: Boolean,
    screenOff: Boolean,
): Boolean {
    if (!config.hasLightVariants) return false
    if (isDoze || screenOff) return false
    return when (config.lightVariantRule) {
        BitmapFaceConfig.LightVariantRule.NONE -> false
        BitmapFaceConfig.LightVariantRule.SECOND_HALF -> when (time.length) {
            3 -> index >= 1
            4 -> index >= 2
            else -> false
        }
    }
}

internal fun getCustomSpacing(
    config: BitmapFaceConfig,
    time: String,
    index: Int,
    baseSpacing: Float,
    overlapPadding: Float,
): Float {
    if (config.overlapPairs.isNotEmpty()) {
        return getNTypeCustomSpacing(config, time, index, overlapPadding)
    }
    if (config.lightVariantRule == BitmapFaceConfig.LightVariantRule.SECOND_HALF) {
        return getNDotCustomSpacing(time, index, baseSpacing, overlapPadding)
    }
    return 0f
}

private fun getNTypeCustomSpacing(
    config: BitmapFaceConfig,
    time: String,
    index: Int,
    overlapPadding: Float,
): Float {
    val str = when {
        time.length == 4 && (index == 0 || index == 2) -> time.substring(index, index + 2)
        time.length == 3 && index == 1 -> time.substring(index)
        else -> ""
    }
    val multiplier = config.overlapPairs[str] ?: return 0f
    return -overlapPadding * multiplier
}

private fun getNDotCustomSpacing(
    time: String,
    index: Int,
    clockPadding: Float,
    overlapPadding: Float,
): Float {
    return when (time.length) {
        3 -> when (index) {
            0 -> clockPadding
            1 -> {
                val secondHalf = time.substring(1)
                if ('1' !in secondHalf) overlapPadding else clockPadding
            }
            else -> 0f
        }
        4 -> when (index) {
            0 -> {
                val firstHalf = time.substring(0, 2)
                if ('1' in firstHalf) clockPadding else overlapPadding
            }
            1 -> clockPadding
            2 -> {
                val secondHalf = time.substring(2)
                if ('1' !in secondHalf) overlapPadding else clockPadding
            }
            else -> 0f
        }
        else -> 0f
    }
}

internal fun computeTotalWidth(
    time: String,
    bitmaps: Map<Char, Bitmap>,
    scale: Float,
    spacing: Float,
    overlapPadding: Float,
    config: BitmapFaceConfig,
    dotCenterMargin: Float,
): Float {
    var total = 0f
    time.forEachIndexed { index, char ->
        val bmp = bitmaps[char] ?: return@forEachIndexed
        total += bmp.width * scale
        total += getCustomSpacing(config, time, index, spacing, overlapPadding)
        if (shouldDrawSeparator(config, time, index)) {
            total += dotCenterMargin * 2
        } else if (index < time.lastIndex) {
            total += spacing
        }
    }
    return total
}

internal fun computeLineWidth(
    digits: String,
    bitmaps: Map<Char, Bitmap>,
    scale: Float,
    spacing: Float,
): Float {
    var total = 0f
    digits.forEachIndexed { index, char ->
        val bmp = bitmaps[char] ?: return@forEachIndexed
        total += bmp.width * scale
        if (index < digits.lastIndex) {
            total += spacing
        }
    }
    return total
}

internal fun circleIntersectsRect(cx: Float, cy: Float, radius: Float, rect: Rect): Boolean {
    val dx = cx - cx.coerceIn(rect.left.toFloat(), rect.right.toFloat())
    val dy = cy - cy.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
    return (dx * dx + dy * dy) <= radius * radius
}

internal fun circleNotFullyContainsRect(cx: Float, cy: Float, radius: Float, rect: Rect): Boolean {
    val r2 = radius * radius
    return arrayOf(
        rect.left.toFloat() to rect.top.toFloat(),
        rect.right.toFloat() to rect.top.toFloat(),
        rect.left.toFloat() to rect.bottom.toFloat(),
        rect.right.toFloat() to rect.bottom.toFloat(),
    ).any { (px, py) ->
        val dx = px - cx
        val dy = py - cy
        (dx * dx + dy * dy) > r2
    }
}

internal fun androidColorToComposeColor(color: Int): Color = Color(color)
