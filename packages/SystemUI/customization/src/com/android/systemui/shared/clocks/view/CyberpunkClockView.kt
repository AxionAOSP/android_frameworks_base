/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks.view

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.android.systemui.shared.clocks.ClockSettingsRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CyberpunkClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val animationSpec: AxClockAnimationSpec = AxClockAnimationSpecs.Cyberpunk

    @Composable
    override fun Content() {
        if (isLargeClock) LargeContent() else SmallContent()
    }

    @Composable
    private fun SmallContent() {
        val (time, date, isDoze, _, _, _, _, display) = rememberClockState()
        val fidget by state.fidgetTrigger
        val showDate = display !is DateDisplay.Hidden
        val badgeDate = if (showDate) date else ""

        val cpYellow = Color(0xFFFCEE0A)
        val cpCyan = Color(0xFF00F0FF)
        val cpRed = Color(0xFFFF5E5E)

        val primaryTimeColor = if (isDoze) Color.White else cpYellow
        val frameColor = if (isDoze) Color.White else cpYellow
        val textColor = if (isDoze) Color.White else cpCyan

        val statColor1 = if (isDoze) Color.White else cpRed
        val statColor2 = if (isDoze) Color.White else cpCyan
        val statColor3 = if (isDoze) Color.White else cpYellow

        val glitchProgress = remember { Animatable(0f) }

        LaunchedEffect(fidget) {
            if (fidget > 0) {
                glitchProgress.snapTo(1f)
                glitchProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                )
            }
        }

        val progress = glitchProgress.value
        val glitchSeed = remember(fidget) { kotlin.random.Random.nextFloat() }
        val contentAlign = when {
            isLeftAligned -> Alignment.CenterStart
            isRightAligned -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        val sidePadding = if (isSideAligned) {
            (clockPaddingStart / context.resources.displayMetrics.density).dp
        } else {
            0.dp
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(
                start = if (isRightAligned) 0.dp else sidePadding,
                end = if (isRightAligned) sidePadding else 0.dp,
            ),
            contentAlignment = contentAlign
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        if (progress > 0f) {
                            translationX = (if (glitchSeed > 0.5f) 8f else -8f) * progress
                            alpha = if (glitchSeed > 0.7f) 0.4f else 1f
                        }
                    }
            ) {
                if (progress > 0.05f && !isDoze) {
                    Box(
                        modifier = Modifier
                            .offset(x = (-6).dp * progress * 1.5f, y = 2.dp * progress)
                            .graphicsLayer {
                                alpha = 0.6f * progress
                                scaleX = 1.02f
                            }
                    ) {
                        ClockBadge(
                            time, badgeDate,
                            cpCyan,
                            frameColor.copy(alpha = 0.3f),
                            statColor1.copy(alpha = 0.5f), statColor2.copy(alpha = 0.5f), statColor3.copy(alpha = 0.5f),
                            cpCyan,
                            progress, isDoze, showDate
                        )
                    }
                }

                if (progress > 0.05f && !isDoze) {
                    Box(
                        modifier = Modifier
                            .offset(x = 6.dp * progress * 1.5f, y = (-2).dp * progress)
                            .graphicsLayer {
                                alpha = 0.6f * progress
                                scaleX = 1.02f
                            }
                    ) {
                        ClockBadge(
                            time, badgeDate,
                            cpRed,
                            frameColor.copy(alpha = 0.3f),
                            statColor1.copy(alpha = 0.5f), statColor2.copy(alpha = 0.5f), statColor3.copy(alpha = 0.5f),
                            cpRed,
                            progress, isDoze, showDate
                        )
                    }
                }

                ClockBadge(time, badgeDate, primaryTimeColor, frameColor, statColor1, statColor2, statColor3, textColor, progress, isDoze, showDate)
            }
        }
    }

    @Composable
    private fun LargeContent() {
        val (time, date, isDoze, _, _, _, _, display) = rememberClockState()
        val fidget by state.fidgetTrigger

        val cpYellow = Color(0xFFFCEE0A)
        val cpCyan = Color(0xFF00F0FF)
        val primaryTimeColor = if (isDoze) Color.White else cpYellow
        val accentColor = if (isDoze) Color.White else cpCyan

        val glitchProgress = remember { Animatable(0f) }

        LaunchedEffect(fidget) {
            if (fidget > 0) {
                glitchProgress.snapTo(1f)
                glitchProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 400)
                )
            }
        }

        val progress = glitchProgress.value
        val glitchSeed = remember(fidget) { kotlin.random.Random.nextFloat() }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        if (progress > 0f) {
                            translationX = (if (glitchSeed > 0.5f) 15f else -15f) * progress
                            alpha = if (glitchSeed > 0.8f) 0.3f else 1f
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val (hours, minutes) = splitTimeLines(time)

                Text(
                    text = hours,
                    style = TextStyle(
                        fontSize = 160.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = primaryTimeColor,
                        letterSpacing = (-8).sp,
                        lineHeight = 160.sp,
                        drawStyle = if (isDoze) Stroke(width = 8f) else Fill
                    )
                )

                val barColor = if (isDoze) Color.White else accentColor

                if (display !is DateDisplay.Hidden) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .height(2.dp)
                                .weight(1f)
                                .background(barColor.copy(alpha = 0.6f))
                        )

                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "//",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = barColor.copy(alpha = 0.8f),
                                letterSpacing = (-1).sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = dateStr.uppercase().ifEmpty { "2077" },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = barColor,
                                letterSpacing = 2.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "//",
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = barColor.copy(alpha = 0.8f),
                                letterSpacing = (-1).sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .height(2.dp)
                                .weight(1f)
                                .background(barColor.copy(alpha = 0.6f))
                        )
                    }
                }

                Text(
                    text = minutes,
                    style = TextStyle(
                        fontSize = 160.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = primaryTimeColor,
                        letterSpacing = (-8).sp,
                        lineHeight = 160.sp,
                        drawStyle = if (isDoze) Stroke(width = 8f) else Fill
                    )
                )

                if (display !is DateDisplay.Hidden) {
                    Spacer(modifier = Modifier.height(8.dp))
                    EnhancedDateArea(
                        textColor = barColor,
                        textSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        iconSize = 16.dp,
                        uppercase = true,
                        rowArrangement = Arrangement.Center,
                    )
                }
            }

            if (progress > 0.05f && !isDoze) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = (if (glitchSeed > 0.5f) 12.dp else -12.dp) * progress)
                        .graphicsLayer { alpha = progress * 0.4f }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val (gh, gm) = splitTimeLines(time)
                        Text(gh, style = TextStyle(fontSize = 160.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = cpCyan, letterSpacing = (-8).sp, lineHeight = 160.sp))
                        Spacer(modifier = Modifier.height(30.dp))
                        Text(gm, style = TextStyle(fontSize = 160.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = cpCyan, letterSpacing = (-8).sp, lineHeight = 160.sp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ClockBadge(
        time: String,
        date: String,
        primaryColor: Color,
        frameColor: Color,
        c1: Color, c2: Color, c3: Color,
        textColor: Color,
        progress: Float,
        isDoze: Boolean,
        showDate: Boolean,
    ) {
        val colAlign = when {
            isLeftAligned -> Alignment.Start
            isRightAligned -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
        val badgeSeed = remember(progress > 0f) { kotlin.random.Random.nextFloat() }

        Column(
            horizontalAlignment = colAlign,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                if (progress > 0f) {
                    rotationZ = (badgeSeed - 0.5f) * 3f * progress
                }
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val blockColor = if (isDoze) Color.White else primaryColor

                Box(modifier = Modifier.width(4.dp).height(6.dp).background(blockColor))
                Box(modifier = Modifier.width(10.dp).height(6.dp).background(blockColor))
                Box(modifier = Modifier.width(20.dp).height(6.dp).background(blockColor))
                Box(modifier = Modifier.width(12.dp).height(6.dp).background(blockColor))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .border(
                        width = 1.5.dp,
                        color = frameColor.copy(alpha = 0.8f),
                        shape = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dynSizeScale = rememberSmallClockSizeScale()
                    Text(
                        text = time,
                        style = TextStyle(
                            fontSize = 80.sp * dynSizeScale,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = primaryColor,
                            letterSpacing = (-4).sp,
                            drawStyle = if (isDoze) Stroke(width = 5f) else Fill
                        )
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            StatBar(c1, 1.0f)
                            StatBar(c2, 0.75f)
                            StatBar(c3, 0.5f)
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "V.2077",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDoze) Color.White else primaryColor,
                                letterSpacing = 0.sp
                            )
                        )
                    }
                }
            }

            if (showDate) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = date.uppercase().ifEmpty { "2077" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isDoze) Color.White else primaryColor,
                        letterSpacing = 1.sp,
                    ),
                )
            }
        }
    }

    @Composable
    private fun StatBar(color: Color, widthScale: Float) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(3.dp))

            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(32.dp * widthScale)
                    .background(color.copy(alpha = 0.8f))
            )
        }
    }

    override fun getTag(): String = if (isLargeClock) "CyberpunkLargeClockView" else "CyberpunkClockView"
}
