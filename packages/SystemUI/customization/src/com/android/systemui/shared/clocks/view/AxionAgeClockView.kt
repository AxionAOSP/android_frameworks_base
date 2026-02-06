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
import android.graphics.Bitmap
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AxionAgeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val useGlitchInteraction: Boolean = true

    @Composable
    override fun Content() {
        val time by timeState
        val isDoze by dozeState
        val date by dateState
        val icon by iconState
        val fidgetByTrigger by fidgetTrigger
        val tapPos by fidgetPosition

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

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart
        ) {
            val fidgetValue = weightFidget.value

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(end = 28.dp)
            ) {
                Spacer(modifier = Modifier.width(28.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (time.length >= 4) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AxDigit(char = time[0], isDoze = isDoze, fidgetValue = fidgetValue)
                                Spacer(modifier = Modifier.width(2.dp))
                                AxDigit(char = time[1], isDoze = isDoze, fidgetValue = fidgetValue)
                                Spacer(modifier = Modifier.width(2.dp))
                                AxDigit(char = time[2], isDoze = isDoze, fidgetValue = fidgetValue)
                                Spacer(modifier = Modifier.width(2.dp))
                                AxDigit(char = time[3], isDoze = isDoze, fidgetValue = fidgetValue)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                InfoColumn(date, icon, isDoze)
            }
        }
    }

    @Composable
    private fun InfoColumn(date: String, icon: Bitmap?, isDoze: Boolean) {
        val parts = date.split("  ")
        val mainData = parts.getOrNull(0)?.trim() ?: ""
        val qlData = parts.getOrNull(1)?.trim() ?: ""
        val hasQlInfo = icon != null || qlData.isNotEmpty()

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            if (mainData.isNotEmpty()) {
                Text(
                    text = mainData,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (isDoze) 0.6f else 0.8f),
                        letterSpacing = 0.5.sp
                    )
                )
            }

            if (hasQlInfo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (isDoze) 0.6f else 0.8f)),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (qlData.isNotEmpty()) {
                        Text(
                            text = qlData,
                            maxLines = 1,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = if (isDoze) 0.6f else 0.8f),
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AxDigit(char: Char, isDoze: Boolean, fidgetValue: Float = 0f) {
        val brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (isDoze) 0.6f else (0.45f + fidgetValue * 0.2f)),
                Color.White.copy(alpha = if (isDoze) 0.3f else (0.15f + fidgetValue * 0.1f))
            )
        )
        val borderAlpha = if (isDoze) 0.5f else (0.25f + fidgetValue * 0.15f)
        val strokeWidth = 1.dp
        Canvas(modifier = Modifier.size(width = 48.dp, height = 108.dp)) {
            drawDigit(char, brush, Color.White.copy(alpha = borderAlpha), strokeWidth.toPx(), Color.White, isDoze, fidgetValue)
        }
    }

    private fun DrawScope.drawDigit(
        char: Char, 
        brush: Brush, 
        borderColor: Color, 
        strokeWidth: Float,
        accentColor: Color,
        isDoze: Boolean,
        fidgetValue: Float = 0f
    ) {
        val w = size.width
        val h = size.height
        val padding = w * 0.15f
        val dw = w - padding * 2
        val dh = h - padding * 2
        val centerX = w / 2
        val centerY = h / 2
        
        val fidgetWall = if (isDoze) 0f else fidgetValue * (dw * -0.12f)
        val wall = dw * 0.35f + fidgetWall
        val path = Path()
        
        when (char) {
            '0' -> {
                path.addRoundRect(RoundRect(padding, padding, w - padding, h - padding, CornerRadius(dw / 2)))
            }
            '1' -> {
                path.moveTo(centerX, padding)
                path.lineTo(centerX, h - padding)
            }
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
                path.moveTo(w - padding, h - padding)
                path.lineTo(w - padding, padding)
                path.moveTo(w - padding, centerY)
                path.lineTo(padding, centerY)
                path.lineTo(padding, padding)
            }
            '5' -> {
                path.moveTo(w - padding, padding)
                path.lineTo(padding, padding)
                path.lineTo(padding, centerY)
                path.arcTo(Rect(padding, centerY, w - padding, h - padding), -90f, 270f, false)
            }
            '6' -> {
                path.moveTo(w - padding, padding)
                path.lineTo(padding, h - padding - dw / 2)
                path.addOval(Rect(padding, h - padding - dw, w - padding, h - padding))
            }
            '7' -> {
                path.moveTo(padding, padding)
                path.lineTo(w - padding, padding)
                path.lineTo(centerX, h - padding)
            }
            '8' -> {
                path.addOval(Rect(padding, padding, w - padding, centerY))
                path.addOval(Rect(padding, centerY, w - padding, h - padding))
            }
            '9' -> {
                path.moveTo(padding, h - padding)
                path.lineTo(w - padding, padding + dw / 2)
                path.addOval(Rect(padding, padding, w - padding, padding + dw))
            }
        }

        val strokeStyle = Stroke(width = wall, cap = StrokeCap.Round, join = StrokeJoin.Round)
        
        
        drawPath(path = path, brush = brush, style = strokeStyle)
        
        drawPath(path = path, color = borderColor, style = Stroke(width = wall + strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (!isDoze) {
            drawPath(
                path = path,
                color = accentColor.copy(alpha = 0.2f),
                style = Stroke(width = wall + 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }

    override fun getTag(): String = "AxionAgeClockView"
}