/*
 * Copyright (C) 2025 Axion OS
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

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel

@Composable
fun RingerControlButton(
    viewModel: AxionVolumeDialogViewModel,
    buttonSize: Dp = VolumeButtonsSize
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val ringerMode = dialogState.ringerMode
    val supportedModes = dialogState.supportedRingerModes
    val isLeftSide = uiState.isLeftSide
    val isExpanded = uiState.isExpanded

    val widthProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "ringerWidthProgress"
    )

    Box(
        modifier = Modifier.width(SliderExpandedContentWidth),
        contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        DrawerLayout(
            widthProgress = widthProgress,
            collapsedWidth = buttonSize,
            expandedWidth = SliderExpandedContentWidth,
            isLeftSide = isLeftSide,
            modifier = Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .width(SliderExpandedContentWidth)
                    .height(buttonSize),
                contentAlignment = Alignment.Center
            ) {
                if (isExpanded) {
                    val selectedIndex = supportedModes.indexOf(ringerMode).coerceAtLeast(0)
                    val spacing = (SliderExpandedContentWidth - buttonSize) / (supportedModes.size - 1).coerceAtLeast(1)
                    val edgePadding = (buttonSize - RingerIndicatorSize) / 2
                    
                    val indicatorOffset by animateDpAsState(
                        targetValue = edgePadding + (if (supportedModes.size > 1) spacing * selectedIndex else 0.dp),
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        label = "indicator"
                    )
                    
                    Surface(
                        modifier = Modifier
                            .offset(x = indicatorOffset)
                            .align(Alignment.CenterStart)
                            .size(RingerIndicatorSize),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        tonalElevation = 2.dp
                    ) {}
                }
                
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = if (!isExpanded && !isLeftSide) Arrangement.End else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    supportedModes.forEach { mode ->
                        val isSelected = mode == ringerMode
                        if (isExpanded || isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { 
                                        if (isExpanded) viewModel.setRingerMode(mode) 
                                        else viewModel.cycleRingerMode()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val targetColor = if (isSelected && isExpanded) MaterialTheme.colorScheme.onPrimary
                                else if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant

                                val animatedColor by animateColorAsState(targetColor, label = "iconColor", animationSpec = tween(200))
                                
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = mode.label,
                                    tint = animatedColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val AxionRingerMode.icon: ImageVector
    get() = when (this) {
        AxionRingerMode.NORMAL -> Icons.Filled.RingVolume
        AxionRingerMode.VIBRATE -> Icons.Filled.Vibration
        AxionRingerMode.SILENT -> Icons.Filled.VolumeOff
    }

private val AxionRingerMode.label: String
    get() = when (this) {
        AxionRingerMode.NORMAL -> "Ring"
        AxionRingerMode.VIBRATE -> "Vibrate"
        AxionRingerMode.SILENT -> "Silent"
    }
