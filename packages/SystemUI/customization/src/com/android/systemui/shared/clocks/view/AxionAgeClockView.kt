/*
 * Copyright (C) 2026 AxionOS Project
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

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.shared.clocks.ClockSettingsRepository
import kotlinx.coroutines.launch

class AxionAgeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val useGlitchInteraction: Boolean = true

    override val clockHeightBase: Int
        get() {
            if (isLargeClock) return super.clockHeightBase
            val density = context.resources.displayMetrics.density
            return ((SMALL_DIGIT_DP + SMALL_INFO_DP) * density * scaleRatio).toInt()
        }

    override fun getTag(): String =
        if (isLargeClock) "AxionAgeLargeClockView" else "AxionAgeClockView"

    @Composable
    override fun Content() {
        val (time, date, isDoze, screenOff, regionDark, _, _, display) = rememberClockState()
        val fidgetByTrigger by state.fidgetTrigger

        val weightFidget = remember { Animatable(0f) }

        LaunchedEffect(fidgetByTrigger) {
            if (fidgetByTrigger > 0) {
                launch {
                    weightFidget.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                    )
                    weightFidget.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    )
                }
            }
        }

        val large = isLargeClock
        val fidgetValue = weightFidget.value
        val textColor = tintColor(isDoze, screenOff, regionDark)
        val isDark = isDoze || regionDark
        val dynSizeScale = rememberSmallClockSizeScale()
        val sz = if (large) 1f else dynSizeScale
        val digitW = (if (large) 90.dp else 48.dp) * sz
        val digitH = (if (large) 150.dp else 108.dp) * sz
        val digitSpacing = (if (large) 8.dp else 2.dp) * sz
        val digitStroke = if (large) 1.5.dp else 1.dp
        val glowExtra = if (large) 4f else 3f
        val infoTextSize = if (large) 18.sp else 14.sp
        val infoIconSize = if (large) 24.dp else 16.dp
        val infoSpacing = if (large) 6.dp else 4.dp
        val showInfo = display !is DateDisplay.Hidden

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = when {
                large -> Alignment.Center
                isLeftAligned -> Alignment.CenterStart
                isRightAligned -> Alignment.CenterEnd
                else -> Alignment.Center
            },
        ) {
            if (large) {
                LargeLayout(time, date, isDark, fidgetValue, digitW, digitH, digitSpacing, digitStroke, glowExtra, infoTextSize, infoIconSize, infoSpacing, textColor, showInfo)
            } else {
                SmallLayout(time, date, isDark, fidgetValue, digitW, digitH, digitSpacing, digitStroke, glowExtra, infoTextSize, infoIconSize, infoSpacing, textColor, showInfo)
            }
        }
    }

    @Composable
    private fun LargeLayout(
        time: String, date: String, isDark: Boolean, fidgetValue: Float,
        digitW: Dp, digitH: Dp, digitSpacing: Dp, digitStroke: Dp, glowExtra: Float,
        infoTextSize: TextUnit, infoIconSize: Dp, infoSpacing: Dp,
        textColor: Color, showInfo: Boolean,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (time.length >= 4) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(digitSpacing)) {
                        AxDigit(time[0], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                        AxDigit(time[1], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(digitSpacing)) {
                        AxDigit(time[2], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                        AxDigit(time[3], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                    }
                }
            }
            if (showInfo) {
                EnhancedDateArea(
                    textColor = textColor,
                    textSize = infoTextSize,
                    iconSize = infoIconSize,
                    rowArrangement = Arrangement.Center,
                )
            }
        }
    }

    @Composable
    private fun SmallLayout(
        time: String, date: String, isDark: Boolean, fidgetValue: Float,
        digitW: Dp, digitH: Dp, digitSpacing: Dp, digitStroke: Dp, glowExtra: Float,
        infoTextSize: TextUnit, infoIconSize: Dp, infoSpacing: Dp,
        textColor: Color, showInfo: Boolean,
    ) {
        val rowArrangement = when {
            isLeftAligned -> Arrangement.Start
            isRightAligned -> Arrangement.End
            else -> Arrangement.Center
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = rowArrangement,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        ) {
            if (time.length >= 4) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AxDigit(time[0], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                    Spacer(modifier = Modifier.width(digitSpacing))
                    AxDigit(time[1], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                    Spacer(modifier = Modifier.width(digitSpacing))
                    AxDigit(time[2], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                    Spacer(modifier = Modifier.width(digitSpacing))
                    AxDigit(time[3], isDark, fidgetValue, digitW, digitH, digitStroke, glowExtra, textColor)
                }
            }

            if (showInfo) {
                Spacer(modifier = Modifier.width(12.dp))
                EnhancedDateArea(
                    textColor = textColor,
                    textSize = infoTextSize,
                    iconSize = infoIconSize,
                    rowArrangement = Arrangement.Start,
                )
            }
        }
    }

    @Composable
    private fun AxDigit(
        char: Char, isDark: Boolean, fidgetValue: Float,
        w: Dp, h: Dp, strokeW: Dp, glowExtra: Float,
        baseColor: Color,
    ) {
        val isDoze by state.dozeFlow.collectAsState()
        val fillTop = when {
            isDoze -> 0.6f
            isDark -> 0.45f + fidgetValue * 0.2f
            else -> 0.7f + fidgetValue * 0.15f
        }
        val fillBottom = when {
            isDoze -> 0.3f
            isDark -> 0.15f + fidgetValue * 0.1f
            else -> 0.35f + fidgetValue * 0.1f
        }
        val brush = Brush.verticalGradient(
            colors = listOf(
                baseColor.copy(alpha = fillTop),
                baseColor.copy(alpha = fillBottom),
            ),
        )
        val borderAlpha = when {
            isDoze -> 0.5f
            isDark -> 0.25f + fidgetValue * 0.15f
            else -> 0.5f + fidgetValue * 0.15f
        }
        Canvas(modifier = Modifier.size(width = w, height = h)) {
            drawDigit(char, brush, baseColor.copy(alpha = borderAlpha), strokeW.toPx(), baseColor, isDark, fidgetValue, glowExtra)
        }
    }

    private fun DrawScope.drawDigit(
        char: Char,
        brush: Brush,
        borderColor: Color,
        strokeWidth: Float,
        accentColor: Color,
        isDark: Boolean,
        fidgetValue: Float,
        glowExtra: Float,
    ) {
        val w = size.width
        val h = size.height
        val padding = w * 0.15f
        val dw = w - padding * 2
        val dh = h - padding * 2
        val centerX = w / 2
        val centerY = h / 2

        val fidgetWall = fidgetValue * (dw * -0.12f)
        val wall = dw * 0.35f + fidgetWall
        val path = Path()

        when (char) {
            '0' -> path.addRoundRect(RoundRect(padding, padding, w - padding, h - padding, CornerRadius(dw / 2)))
            '1' -> { path.moveTo(centerX, padding); path.lineTo(centerX, h - padding) }
            '2' -> {
                path.arcTo(Rect(padding, padding, w - padding, padding + dw), 180f, 180f, true)
                path.lineTo(padding, h - padding)
                path.lineTo(w - padding, h - padding)
            }
            '3' -> {
                path.arcTo(Rect(padding, padding, w - padding, padding + dh / 2), 180f, 270f, false)
                path.arcTo(Rect(padding, centerY, w - padding, h - padding), 270f, 270f, false)
            }
            '4' -> {
                path.moveTo(w - padding, h - padding); path.lineTo(w - padding, padding)
                path.moveTo(w - padding, centerY); path.lineTo(padding, centerY); path.lineTo(padding, padding)
            }
            '5' -> {
                path.moveTo(w - padding, padding); path.lineTo(padding, padding); path.lineTo(padding, centerY)
                path.arcTo(Rect(padding, centerY, w - padding, h - padding), -90f, 270f, false)
            }
            '6' -> {
                path.moveTo(padding, padding); path.lineTo(padding, h - padding - dw / 2)
                path.addOval(Rect(padding, h - padding - dw, w - padding, h - padding))
            }
            '7' -> {
                path.moveTo(padding, padding); path.lineTo(w - padding, padding); path.lineTo(centerX, h - padding)
            }
            '8' -> {
                path.addOval(Rect(padding, padding, w - padding, centerY))
                path.addOval(Rect(padding, centerY, w - padding, h - padding))
            }
            '9' -> {
                path.moveTo(w - padding, h - padding); path.lineTo(w - padding, padding + dw / 2)
                path.addOval(Rect(padding, padding, w - padding, padding + dw))
            }
        }

        val strokeStyle = Stroke(width = wall, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path = path, brush = brush, style = strokeStyle)
        drawPath(path = path, color = borderColor, style = Stroke(width = wall + strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val glowAlpha = if (isDark) 0.2f else 0.12f
        drawPath(
            path = path,
            color = accentColor.copy(alpha = glowAlpha),
            style = Stroke(width = wall + glowExtra, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    companion object {
        private const val SMALL_DIGIT_DP = 108f
        private const val SMALL_INFO_DP = 24f
    }
}
