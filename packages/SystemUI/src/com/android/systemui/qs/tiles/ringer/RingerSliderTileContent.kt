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

package com.android.systemui.qs.tiles.ringer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.composefragment.LocalBlurEnabled
import kotlin.math.roundToInt

@Composable
fun RingerSliderTileContent(
    modifier: Modifier = Modifier.fillMaxSize(),
    interactable: Boolean = true,
    shape: Shape = CircleShape,
    viewModel: RingerSliderViewModel = LocalRingerSliderViewModel.current,
) {
    var currentRingerMode by remember { mutableIntStateOf(viewModel.currentMode) }
    val isZenMuted by viewModel.isZenMuted.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.ringerModeChanges.collect { currentRingerMode = it }
    }

    val targetPosition = remember(currentRingerMode) {
        viewModel.targetPosition(currentRingerMode)
    }

    val animatedPosition = remember { Animatable(targetPosition) }

    LaunchedEffect(targetPosition) {
        if (animatedPosition.value != targetPosition) {
            animatedPosition.animateTo(
                targetValue = targetPosition,
                animationSpec = tween(durationMillis = 250, easing = LinearOutSlowInEasing)
            )
        }
    }

    val activeBg = MaterialTheme.colorScheme.primary
    val activeIcon = MaterialTheme.colorScheme.onPrimary

    val blurEnabled = LocalBlurEnabled.current

    val neutralBg = if (blurEnabled) {
        LocalAndroidColorScheme.current.surfaceEffect1
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }

    val neutralDot = MaterialTheme.colorScheme.onSurface

    val canInteract = interactable && !isZenMuted

    val interactionModifier = if (canInteract) {
        Modifier
            .pointerInput(viewModel.availableModes, viewModel.numModes) {
                detectTapGestures { tapOffset ->
                    val sectionWidth = size.width / viewModel.numModes.toFloat()
                    val tappedIndex = (tapOffset.x / sectionWidth)
                        .toInt()
                        .coerceIn(0, viewModel.numModes - 1)
                    viewModel.setRingerMode(viewModel.availableModes[tappedIndex].mode)
                }
            }
    } else {
        Modifier
    }

    val zenAlpha = if (isZenMuted) 0.5f else 1f

    BoxWithConstraints(
        modifier = modifier
            .graphicsLayer { alpha = zenAlpha }
            .background(neutralBg, shape)
            .clip(shape)
            .then(interactionModifier),
        contentAlignment = Alignment.CenterStart
    ) {
        val controlWidth = maxWidth
        val slotSize =
            minOf(
                RINGER_INDICATOR_SIZE,
                maxHeight,
                (controlWidth - RINGER_OUTER_PADDING * 2) / viewModel.numModes,
            )
        val iconSize = minOf(RINGER_ICON_SIZE, slotSize / 2)
        val currentIndex = animatedPosition.value.roundToInt().coerceIn(0, viewModel.numModes - 1)
        val travelWidth = (controlWidth - RINGER_OUTER_PADDING * 2 - slotSize).coerceAtLeast(0.dp)
        val step = if (viewModel.numModes > 1) travelWidth / (viewModel.numModes - 1) else 0.dp
        val indicatorOffset = RINGER_OUTER_PADDING + step * animatedPosition.value

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = RINGER_OUTER_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            viewModel.availableModes.forEachIndexed { index, option ->
                key(option.mode) {
                    val dotAlpha by
                        animateFloatAsState(
                            targetValue = if (currentIndex == index) 0f else 0.4f,
                            animationSpec = tween(durationMillis = 200),
                            label = "RingerModeDotAlpha",
                        )
                    Box(modifier = Modifier.size(slotSize), contentAlignment = Alignment.Center) {
                        Box(
                            modifier =
                                Modifier.size(RINGER_DOT_SIZE)
                                    .graphicsLayer { alpha = dotAlpha }
                                    .background(neutralDot, CircleShape)
                        )
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier.offset(x = indicatorOffset)
                    .size(slotSize)
                    .background(activeBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = viewModel.availableModes[currentIndex].icon,
                contentDescription = null,
                tint = activeIcon,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

private val RINGER_INDICATOR_SIZE = 42.dp
private val RINGER_OUTER_PADDING = 8.dp
private val RINGER_ICON_SIZE = 20.dp
private val RINGER_DOT_SIZE = 6.dp
