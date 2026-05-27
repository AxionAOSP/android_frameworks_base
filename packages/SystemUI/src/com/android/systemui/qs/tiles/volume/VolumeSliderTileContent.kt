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

package com.android.systemui.qs.tiles.volume

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.composefragment.LocalBlurEnabled

@Composable
fun VolumeSliderTileContent(
    modifier: Modifier = Modifier.fillMaxSize(),
    interactable: Boolean = true,
    viewModel: VolumeSliderViewModel = LocalVolumeSliderViewModel.current,
) {
    var level by remember { mutableFloatStateOf(viewModel.currentLevel()) }
    var dragLevel by remember { mutableFloatStateOf(level) }
    var isDragging by remember { mutableStateOf(false) }
    val isEnabled by viewModel.enabledFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.volumeChanges.collect { newLevel ->
            level = newLevel
            if (!isDragging) dragLevel = newLevel
        }
    }

    LaunchedEffect(level) {
        if (!isDragging) dragLevel = level
    }

    val animatedLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else level,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "volume_level"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val blurEnabled = LocalBlurEnabled.current

    val neutralBg = if (blurEnabled) {
        LocalAndroidColorScheme.current.surfaceEffect1
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }

    val density = LocalDensity.current

    val isInactive = level == 0f || !isEnabled

    val fillColor by animateColorAsState(
        targetValue = if (isInactive) Color.Transparent else primaryColor,
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "fill_color"
    )

    val trackColor by animateColorAsState(
        targetValue = if (isInactive) neutralBg else primaryColor.copy(alpha = 0.8f),
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "track_color"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isInactive) onSurfaceColor else onPrimaryColor,
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "content_color"
    )

    val interactionModifier = if (interactable) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures {
                    viewModel.onTap(!viewModel.isActive())
                }
            }
            .pointerInput(isEnabled) {
                if (isEnabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = {
                            isDragging = false
                            dragLevel = level
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / size.width
                        dragLevel = (dragLevel + delta).coerceIn(0f, 1f)
                        viewModel.setLevel(dragLevel)
                    }
                }
            }
    } else {
        Modifier
    }

    val borderModifier = if (isEnabled) {
        Modifier.border(2.dp, primaryColor.copy(alpha = 0.6f), CircleShape)
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(trackColor, CircleShape)
            .then(borderModifier)
            .then(interactionModifier)
    ) {
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val fillWidth = boxWidthPx * animatedLevel

        Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
            drawRect(
                color = fillColor,
                size = Size(fillWidth, size.height),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = CONTENT_START_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ICON_LABEL_SPACING)
        ) {
            Icon(
                imageVector = viewModel.iconForLevel(animatedLevel),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(ICON_SIZE)
            )

            Text(
                text = viewModel.labelForLevel(animatedLevel),
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = CONTENT_END_PADDING)
                    .basicMarquee(iterations = 1, initialDelayMillis = 2000)
            )
        }
    }
}

private val CONTENT_START_PADDING = 24.dp
private val CONTENT_END_PADDING = 24.dp
private val ICON_SIZE = 24.dp
private val ICON_LABEL_SPACING = 14.dp
