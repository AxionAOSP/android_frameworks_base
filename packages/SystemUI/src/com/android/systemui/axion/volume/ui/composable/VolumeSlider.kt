/*
 * Copyright (C) 2025-2026 Axion OS
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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axion.volume.ui.composable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel

private val GrayscaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val GrayscaleFilter = ColorFilter.colorMatrix(GrayscaleMatrix)
private val TrackShape = androidx.compose.foundation.shape.RoundedCornerShape(SliderCornerRadius)

@Composable
fun SliderColumn(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    trackHeight: Dp = SliderTrackHeight,
    iconSize: Dp = SliderIconSize,
    showPercentage: Boolean = false
) {
    val muted = stream.isMuted
    val motionScheme = MaterialTheme.motionScheme
    val iconAlpha by animateFloatAsState(
        if (muted) 0.38f else 1f,
        motionScheme.fastEffectsSpec(),
        label = "iconAlpha"
    )

    VolumeSlider(
        value = if (muted) 0f else stream.level,
        onValueChange = { viewModel.setVolume(stream.streamType, it) },
        trackHeight = trackHeight,
        icon = {
            Icon(
                painter = painterResource(if (muted) stream.mutedIconRes else stream.iconRes),
                contentDescription = stream.streamInfo.label,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = iconAlpha },
                tint = MaterialTheme.colorScheme.onPrimary
            )
        },
        onIconClick = { viewModel.toggleMute(stream.streamType) },
        showPercentage = showPercentage,
        onInteractionStart = {
            viewModel.isInteracting = true
            viewModel.setActiveStream(stream.streamType)
            viewModel.rescheduleTimeout()
        },
        onInteractionEnd = {
            viewModel.isInteracting = false
            viewModel.rescheduleTimeout()
        },
    )
}

@Composable
fun AppVolumeSlider(
    appVolume: AxionAppVolumeModel,
    viewModel: AxionVolumeDialogViewModel,
    trackHeight: Dp = SliderTrackHeight,
    iconSize: Dp = SliderIconSize,
    showPercentage: Boolean = false
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appInfo = remember(appVolume.packageName) {
        try { pm.getApplicationInfo(appVolume.packageName, 0) } catch (e: Exception) { null }
    }
    val label = remember(appInfo) { appInfo?.loadLabel(pm)?.toString() ?: appVolume.packageName }
    val imageBitmap = remember(appInfo) { appInfo?.loadIcon(pm)?.toBitmap()?.asImageBitmap() }
    val isMutedOrZero = appVolume.isMuted || appVolume.volume == 0f
    val motionScheme = MaterialTheme.motionScheme
    val iconAlpha by animateFloatAsState(
        if (isMutedOrZero) 0.38f else 1f,
        motionScheme.fastEffectsSpec(),
        label = "appIconAlpha"
    )

    VolumeSlider(
        value = if (appVolume.isMuted) 0f else appVolume.volume,
        onValueChange = { viewModel.setAppVolume(appVolume.packageName, it) },
        trackHeight = trackHeight,
        icon = {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = label,
                    colorFilter = if (isMutedOrZero) GrayscaleFilter else null,
                    modifier = Modifier
                        .size(iconSize)
                        .clip(CircleShape)
                        .graphicsLayer { alpha = iconAlpha }
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = label,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer { alpha = iconAlpha },
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        onIconClick = { viewModel.setAppMute(appVolume.packageName, !appVolume.isMuted) },
        showPercentage = showPercentage,
        onInteractionStart = {
            viewModel.isInteracting = true
            viewModel.rescheduleTimeout()
        },
        onInteractionEnd = {
            viewModel.isInteracting = false
            viewModel.rescheduleTimeout()
        },
    )
}

@Composable
private fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackHeight: Dp = SliderTrackHeight,
    icon: @Composable () -> Unit,
    onIconClick: () -> Unit,
    showPercentage: Boolean = false,
    onInteractionStart: () -> Unit = {},
    onInteractionEnd: () -> Unit = {},
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    var lastUserInteraction by remember { mutableStateOf(0L) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (isDragging) return@LaunchedEffect
        val inGracePeriod = System.currentTimeMillis() - lastUserInteraction < VOLUME_UPDATE_GRACE_PERIOD
        if (!inGracePeriod) {
            sliderValue = value
        }
    }

    val drawValue by animateFloatAsState(
        targetValue = sliderValue,
        animationSpec = if (isDragging) snap() else tween(80),
        label = "trackFill"
    )

    val primary = MaterialTheme.colorScheme.primary
    val trackInactive = MaterialTheme.colorScheme.surfaceContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showPercentage) {
            Text(
                text = "${(sliderValue * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(PercentageSpacing))
        }

        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(SliderTrackWidth)
                .clip(TrackShape)
                .clickable(onClick = onIconClick)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val newVal = 1f - (it.y / size.height).coerceIn(0f, 1f)
                            sliderValue = newVal
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(newVal)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            onInteractionStart()
                            val newVal = 1f - (it.y / size.height).coerceIn(0f, 1f)
                            sliderValue = newVal
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(newVal)
                        },
                        onDragEnd = {
                            isDragging = false
                            onInteractionEnd()
                            lastUserInteraction = System.currentTimeMillis()
                        },
                        onDragCancel = {
                            isDragging = false
                            onInteractionEnd()
                            lastUserInteraction = System.currentTimeMillis()
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val newVal = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                            sliderValue = newVal
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(newVal)
                        }
                    )
                }
                .drawWithCache {
                    val cr = CornerRadius(SliderCornerRadius.toPx())
                    val w = size.width
                    val h = size.height
                    onDrawBehind {
                        drawRoundRect(color = trackInactive, cornerRadius = cr)
                        val minH = w
                        val ph = minH + (h - minH) * drawValue
                        drawRoundRect(
                            color = primary,
                            topLeft = Offset(0f, h - ph),
                            size = Size(w, ph),
                            cornerRadius = cr
                        )
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier.padding(bottom = (SliderTrackWidth - SliderIconSize) / 2)
            ) { icon() }
        }
    }
}

@Composable
fun VolumeSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    sliderItems: List<VolumeSliderItem>,
    trackHeight: Dp = SliderTrackHeight,
    iconSize: Dp = SliderIconSize,
    showPercentage: Boolean = false,
    modifier: Modifier = Modifier
) {
    val needsScroll = sliderItems.size > MaxVisibleSliders
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                viewModel.isInteracting = true
                viewModel.rescheduleTimeout()
            } else {
                viewModel.isInteracting = false
                viewModel.rescheduleTimeout()
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = modifier.then(
                if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier
            ),
            horizontalArrangement = Arrangement.spacedBy(
                space = SliderSpacing,
                alignment = Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.Top
        ) {
            sliderItems.forEach { item ->
                val itemModifier = if (needsScroll) {
                    Modifier.width(SliderWidthExpanded)
                } else {
                    Modifier.weight(1f)
                }
                Box(modifier = itemModifier) {
                    when (item) {
                        is VolumeSliderItem.Stream -> {
                            key(item.model.streamType) {
                                SliderColumn(item.model, viewModel, trackHeight, iconSize, showPercentage)
                            }
                        }
                        is VolumeSliderItem.AppVolume -> {
                            key(item.model.packageName) {
                                AppVolumeSlider(item.model, viewModel, trackHeight, iconSize, showPercentage)
                            }
                        }
                    }
                }
            }
        }

        if (needsScroll && scrollState.maxValue > 0) {
            ScrollIndicator(
                scrollState = scrollState,
                visibleFraction = MaxVisibleSliders.toFloat() / sliderItems.size.toFloat()
            )
        }
    }
}

@Composable
private fun ScrollIndicator(
    scrollState: ScrollState,
    visibleFraction: Float
) {
    val scrollFraction = if (scrollState.maxValue > 0) {
        scrollState.value.toFloat() / scrollState.maxValue.toFloat()
    } else 0f

    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .padding(top = ScrollIndicatorTopPadding)
            .width(ScrollIndicatorTrackWidth)
            .height(ScrollIndicatorHeight)
            .drawWithCache {
                val cr = CornerRadius(size.height / 2f)
                val thumbWidth = size.width * visibleFraction
                val maxThumbOffset = size.width - thumbWidth
                val thumbOffset = maxThumbOffset * scrollFraction
                onDrawBehind {
                    drawRoundRect(color = trackColor, cornerRadius = cr)
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(thumbOffset, 0f),
                        size = Size(thumbWidth, size.height),
                        cornerRadius = cr
                    )
                }
            }
    )
}
