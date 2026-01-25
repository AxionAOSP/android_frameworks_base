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
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.*
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

class CyberpunkClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : AxClockView(context, attrs, defStyleAttr, defStyleRes) {

    override val showSystemDate: Boolean = false
    override val useGlitchInteraction: Boolean = true

    @Composable
    override fun Content() {
        val time by timeState
        val isDoze by dozeState
        val date by dateState
        val icon by iconState
        val fidget by fidgetTrigger
        
        
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

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        
                        if (progress > 0f) {
                            translationX = (if (Math.random() > 0.5) 8f else -8f) * progress
                            
                            alpha = if (Math.random() > 0.7) 0.4f else 1f
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
                            time, date, icon,
                            cpCyan, 
                            frameColor.copy(alpha = 0.3f), 
                            statColor1.copy(alpha=0.5f), statColor2.copy(alpha=0.5f), statColor3.copy(alpha=0.5f),
                            cpCyan, 
                            progress, isDoze
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
                            time, date, icon,
                            cpRed, 
                            frameColor.copy(alpha = 0.3f),
                            statColor1.copy(alpha=0.5f), statColor2.copy(alpha=0.5f), statColor3.copy(alpha=0.5f),
                            cpRed,
                            progress, isDoze
                        )
                    }
                }

                
                ClockBadge(time, date, icon, primaryTimeColor, frameColor, statColor1, statColor2, statColor3, textColor, progress, isDoze)
            }
        }
    }

    @Composable
    private fun ClockBadge(
        time: String,
        date: String,
        icon: Bitmap?,
        primaryColor: Color,
        frameColor: Color,
        c1: Color, c2: Color, c3: Color,
        textColor: Color,
        progress: Float,
        isDoze: Boolean
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                if (progress > 0f) {
                     
                    rotationZ = (Math.random().toFloat() - 0.5f) * 3f * progress 
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
                    
                    Text(
                        text = time,
                        style = TextStyle(
                            fontSize = 80.sp, 
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

            
            Spacer(modifier = Modifier.height(12.dp))
            
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                 
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(if (isDoze) Color.White else primaryColor),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                if (date.isNotEmpty()) {
                    Text(
                        text = date.uppercase(),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isDoze) Color.White else primaryColor,
                            letterSpacing = 1.sp
                        )
                    )
                }
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

    override fun getTag(): String = "CyberpunkClockView"
}
