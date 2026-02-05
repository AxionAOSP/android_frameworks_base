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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

class CyberpunkLargeClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val useGlitchInteraction: Boolean = true
    override val isLargeClock = true

    @Composable
    override fun Content() {
        val time by timeState
        val isDoze by dozeState
        val date by dateState
        val icon by iconState
        val fidget by fidgetTrigger
        
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

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        if (progress > 0f) {
                            translationX = (if (Math.random() > 0.5) 15f else -15f) * progress
                            alpha = if (Math.random() > 0.8) 0.3f else 1f
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                
                Text(
                    text = time.take(2),
                    style = TextStyle(
                        fontSize = 160.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = primaryTimeColor,
                        letterSpacing = (-8).sp,
                        lineHeight = 140.sp,
                        drawStyle = if (isDoze) Stroke(width = 8f) else Fill
                    )
                )
                
                
                if (!isDoze || true) { 
                    val barColor = if (isDoze) Color.White else accentColor
                    
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
                        
                        
                        if (icon != null) {
                            Image(
                                bitmap = icon!!.asImageBitmap(),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(barColor),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        if (date.isNotEmpty()) {
                            Text(
                                text = date.uppercase(),
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = barColor,
                                    letterSpacing = 2.sp
                                )
                            )
                        } else {
                            Text(
                                text = "2077",
                                modifier = Modifier.padding(horizontal = 4.dp),
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = barColor
                                )
                            )
                        }

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
                    text = time.takeLast(2),
                    style = TextStyle(
                        fontSize = 160.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = primaryTimeColor,
                        letterSpacing = (-8).sp,
                        lineHeight = 140.sp,
                        drawStyle = if (isDoze) Stroke(width = 8f) else Fill
                    )
                )
            }

            
            if (progress > 0.05f && !isDoze) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = (if (Math.random() > 0.5) 12.dp else -12.dp) * progress)
                        .graphicsLayer { alpha = progress * 0.4f }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(time.take(2), style = TextStyle(fontSize = 160.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = cpCyan, letterSpacing = (-8).sp, lineHeight = 140.sp))
                        Spacer(modifier = Modifier.height(30.dp))
                        Text(time.takeLast(2), style = TextStyle(fontSize = 160.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = cpCyan, letterSpacing = (-8).sp, lineHeight = 140.sp))
                    }
                }
            }
        }
    }

    override fun getTag(): String = "CyberpunkLargeClockView"
}
