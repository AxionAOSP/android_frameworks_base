/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.common.ringer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun RingerSliderWidget(
    interactor: RingerModeInteractor,
    theme: RingerSliderTheme,
    dimens: RingerSliderDimens,
    modifier: Modifier = Modifier,
    isDozing: Boolean = false,
    border: Modifier = Modifier
) {
    val availableModes = remember(interactor) { interactor.getAvailableRingerModes() }
    val numModes = remember(interactor) { interactor.getNumberOfModes() }
    val maxOffset = remember(interactor) { interactor.getMaxOffset() }

    var targetPosition by remember { mutableFloatStateOf(interactor.getTargetPosition(interactor.getCurrentMode())) }
    var dragOffset by remember { mutableFloatStateOf(targetPosition) }
    var isDragging by remember { mutableStateOf(false) }
    val animatedPosition = remember { Animatable(targetPosition) }

    LaunchedEffect(Unit) {
        interactor.targetPositionFlow.collect { newPosition ->
            targetPosition = newPosition
            if (!isDragging) {
                dragOffset = newPosition
                if (animatedPosition.value != newPosition) {
                    animatedPosition.animateTo(
                        targetValue = newPosition,
                        animationSpec = tween(durationMillis = 250, easing = LinearOutSlowInEasing)
                    )
                }
            }
        }
    }

    LaunchedEffect(dragOffset, isDragging) {
        if (isDragging) {
            animatedPosition.snapTo(dragOffset)
        }
    }

    Box(
        modifier = modifier
            .height(dimens.thumbSize)
            .background(if (isDozing) Color.Transparent else theme.neutralBg, CircleShape)
            .clip(CircleShape)
            .then(if (isDozing)
                Modifier.border(theme.dozeStroke, Color.White, CircleShape)
            else border)
            .pointerInput(availableModes, numModes) {
                detectTapGestures { tapOffset ->
                    val sectionWidth = size.width / numModes.toFloat()
                    val tappedIndex = (tapOffset.x / sectionWidth).toInt().coerceIn(0, numModes - 1)
                    interactor.setRingerMode(availableModes[tappedIndex].mode)
                    dragOffset = tappedIndex.toFloat()
                }
            }
            .pointerInput(availableModes, maxOffset) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        val finalMode = interactor.snapMode(dragOffset)
                        interactor.setRingerMode(finalMode)
                        val snappedPosition = interactor.getTargetPosition(finalMode)
                        dragOffset = snappedPosition
                        isDragging = false
                    },
                    onDragCancel = { 
                        isDragging = false
                        dragOffset = targetPosition
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val trackWidth = size.width - dimens.thumbSize.toPx()
                    val pixelPerUnit = trackWidth / maxOffset
                    dragOffset = (dragOffset + (dragAmount.x / pixelPerUnit))
                        .coerceIn(0f, maxOffset)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val currentIndex = animatedPosition.value.roundToInt()
            availableModes.forEachIndexed { index, _ ->
                key(index) {
                    val dotAlpha by animateFloatAsState(
                        targetValue = if (currentIndex == index) 0f else (if (isDozing) 1f else 0.4f),
                        animationSpec = tween(durationMillis = 200),
                        label = "dot_alpha_$index"
                    )
                    Box(
                        modifier = Modifier
                            .size(dimens.dotSize)
                            .graphicsLayer { alpha = dotAlpha }
                            .background(if (isDozing) Color.White else theme.neutralIcon, CircleShape)
                    )
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val totalWidth = maxWidth
            val step = if (numModes > 1) (totalWidth - dimens.thumbSize) / (numModes - 1) else 0.dp
            val thumbOffset = step * animatedPosition.value

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(dimens.thumbSize)
                    .padding(dimens.thumbPadding)
                    .background(if (isDozing) Color.Transparent else theme.activeBg, CircleShape)
                    .then(if (isDozing)
                        Modifier.border(theme.dozeStroke, Color.White, CircleShape)
                    else
                        Modifier.border(2.dp, Color.Transparent, CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                val currentIndex = animatedPosition.value.roundToInt().coerceIn(0, numModes - 1)
                Icon(
                    imageVector = availableModes[currentIndex].icon,
                    contentDescription = null,
                    tint = if (isDozing) Color.White else theme.activeIcon,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
        }
    }
}
