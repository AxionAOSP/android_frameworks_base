/*
 * Copyright 2025 AxionOS
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
package com.android.systemui.lockscreen

import android.media.AudioManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.systemui.res.R
import kotlin.math.roundToInt

@Composable
fun RingerSliderWidget(
    spec: WidgetSpec,
    bgColor: Color,
    border: Modifier,
    iconTint: Color,
    theme: Theme,
    dimens: Dimens,
    ctrl: LockScreenWidgetsController,
    active: Boolean,
    isDozing: Boolean
) {
    val mode = ctrl.states.getRingerMode()
    val totalWidth = (dimens.widgetSizeDp * spec.type.span) + (dimens.spacingDp * spec.type.span)
    val thumbSize = dimens.widgetSizeDp
    
    val targetPosition = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> 0f
        AudioManager.RINGER_MODE_VIBRATE -> 1f
        AudioManager.RINGER_MODE_SILENT -> 2f
        else -> 0f
    }
    
    var dragOffset by remember { mutableStateOf(targetPosition) }
    var isDragging by remember { mutableStateOf(false) }
    
    val animatedPosition by animateFloatAsState(
        targetValue = if (isDragging) dragOffset else targetPosition,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ringer_position"
    )
    
    LaunchedEffect(targetPosition) {
        if (!isDragging) {
            dragOffset = targetPosition
        }
    }
    
    Box(
        modifier = Modifier
            .width(totalWidth)
            .height(thumbSize)
            .background(
                if (isDozing) Color.Transparent else theme.neutralBg,
                CircleShape
            )
            .clip(CircleShape)
            .then(
                if (isDozing) {
                    Modifier.border(dimens.dozeStrokeDp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        val snappedMode = when {
                            dragOffset < 0.5f -> AudioManager.RINGER_MODE_NORMAL
                            dragOffset < 1.5f -> AudioManager.RINGER_MODE_VIBRATE
                            else -> AudioManager.RINGER_MODE_SILENT
                        }
                        ctrl.audioManager.ringerMode = snappedMode
                        ctrl.states.setRingerMode(snappedMode)
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val trackWidth = totalWidth - thumbSize
                    val maxOffset = 2f
                    val pixelPerUnit = trackWidth.toPx() / maxOffset
                    
                    dragOffset = (dragOffset + (dragAmount.x / pixelPerUnit))
                        .coerceIn(0f, maxOffset)
                }
            }
            .clickable {
                val current = ctrl.audioManager.ringerMode
                val next = when (current) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                ctrl.audioManager.ringerMode = next
                ctrl.states.setRingerMode(next)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val dotAlpha by animateFloatAsState(
                    targetValue = if (targetPosition.roundToInt() == index) 0f else 0.4f,
                    animationSpec = tween(durationMillis = 200),
                    label = "dot_alpha"
                )

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = dotAlpha }
                        .background(
                            if (isDozing) Color.White else theme.neutralIcon,
                            CircleShape
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(x = ((totalWidth - thumbSize) / 2f) * animatedPosition)
                .size(thumbSize)
                .padding(6.dp)
                .background(
                    when {
                        isDozing -> Color.Transparent
                        else -> theme.activeBg
                    },
                    CircleShape
                )
                .then(
                    if (isDozing) {
                        Modifier.border(dimens.dozeStrokeDp, Color.White, CircleShape)
                    } else {
                        Modifier.border(2.dp, theme.activeBg, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (mode) {
                    AudioManager.RINGER_MODE_VIBRATE -> Icons.Filled.Vibration
                    AudioManager.RINGER_MODE_SILENT -> Icons.Filled.VolumeOff
                    else -> Icons.Filled.VolumeUp
                },
                contentDescription = stringResource(spec.action.labelRes),
                tint = when {
                    isDozing -> Color.White
                    else -> theme.activeIcon
                },
                modifier = Modifier.size(dimens.iconSizeDp)
            )
        }
    }
}
