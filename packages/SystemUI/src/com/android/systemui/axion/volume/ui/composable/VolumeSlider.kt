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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
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

@Composable
fun SliderColumn(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    touchWidth: Dp = CollapsedPanelWidth,
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
        onOverscroll = { viewModel.setOverscrollOffset(it) },
        touchWidth = touchWidth,
        icon = {
            Icon(
                painter = painterResource(if (muted) stream.mutedIconRes else stream.iconRes),
                contentDescription = stream.streamInfo.label,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = iconAlpha },
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
    touchWidth: Dp = CollapsedPanelWidth,
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
        onOverscroll = { viewModel.setOverscrollOffset(it) },
        touchWidth = touchWidth,
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
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
    onOverscroll: (Float) -> Unit,
    touchWidth: Dp,
    icon: @Composable () -> Unit,
    onIconClick: () -> Unit,
    showPercentage: Boolean = false,
    onInteractionStart: () -> Unit = {},
    onInteractionEnd: () -> Unit = {},
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    var lastUserInteraction by remember { mutableStateOf(0L) }
    var wasAtEdge by remember { mutableStateOf(false) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnOverscroll by rememberUpdatedState(onOverscroll)
    val view = LocalView.current

    LaunchedEffect(value) {
        if (isDragging) return@LaunchedEffect
        val inGracePeriod = System.currentTimeMillis() - lastUserInteraction < VOLUME_UPDATE_GRACE_PERIOD
        if (!inGracePeriod) {
            sliderValue = value
        }
    }

    val motionScheme = MaterialTheme.motionScheme

    val drawValue by animateFloatAsState(
        targetValue = sliderValue,
        animationSpec = if (isDragging) snap() else motionScheme.fastEffectsSpec(),
        label = "trackFill"
    )

    val trackVisualWidthDp by animateFloatAsState(
        targetValue = if (isDragging) SliderTrackWidthThick.value else SliderTrackWidthThin.value,
        animationSpec = if (isDragging) motionScheme.fastSpatialSpec()
                        else motionScheme.defaultSpatialSpec(),
        label = "trackWidth"
    )

    val primary = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    Column(
        modifier = Modifier.width(touchWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(SliderTopPadding))

        Box(
            modifier = Modifier
                .height(SliderTrackHeight)
                .width(touchWidth)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val rawVal = 1f - (it.y / size.height)
                            val newVal = rawVal.coerceIn(0f, 1f)
                            sliderValue = newVal
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(newVal)
                            if (newVal == 0f || newVal == 1f) {
                                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                val offset = if (newVal == 0f) 10f else -10f
                                currentOnOverscroll(offset)
                                currentOnOverscroll(0f)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            wasAtEdge = false
                            onInteractionStart()
                            val newVal = 1f - (it.y / size.height).coerceIn(0f, 1f)
                            sliderValue = newVal
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(newVal)
                        },
                        onDragEnd = {
                            isDragging = false
                            wasAtEdge = false
                            onInteractionEnd()
                            currentOnOverscroll(0f)
                            lastUserInteraction = System.currentTimeMillis()
                        },
                        onDragCancel = {
                            isDragging = false
                            wasAtEdge = false
                            onInteractionEnd()
                            currentOnOverscroll(0f)
                            lastUserInteraction = System.currentTimeMillis()
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val rawVal = 1f - (change.position.y / size.height)
                            when {
                                rawVal < 0f -> {
                                    sliderValue = 0f
                                    if (!wasAtEdge) {
                                        wasAtEdge = true
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    }
                                    currentOnOverscroll((-rawVal * 30f).coerceAtMost(10f))
                                }
                                rawVal > 1f -> {
                                    sliderValue = 1f
                                    if (!wasAtEdge) {
                                        wasAtEdge = true
                                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    }
                                    currentOnOverscroll((-(rawVal - 1f) * 30f).coerceAtLeast(-10f))
                                }
                                else -> {
                                    sliderValue = rawVal
                                    wasAtEdge = false
                                    currentOnOverscroll(0f)
                                }
                            }
                            lastUserInteraction = System.currentTimeMillis()
                            currentOnValueChange(sliderValue)
                        }
                    )
                }
                .drawWithCache {
                    val h = size.height
                    val cx = size.width / 2f
                    val dotR = DotMatrixDotRadius.toPx()
                    val dotSpacing = DotMatrixDotSpacing.toPx()
                    val minFillH = SliderTrackWidthThin.toPx()

                    onDrawBehind {
                        val trackW = trackVisualWidthDp * density
                        val cr = CornerRadius(trackW / 2f)
                        val trackLeft = (size.width - trackW) / 2f
                        val fillH = minFillH + (h - minFillH) * drawValue
                        val fillTop = h - fillH

                        var dotY = dotR
                        while (dotY < fillTop - dotR) {
                            drawCircle(color = dotColor, radius = dotR, center = Offset(cx, dotY))
                            dotY += dotSpacing
                        }

                        drawRoundRect(
                            color = primary,
                            topLeft = Offset(trackLeft, fillTop),
                            size = Size(trackW, fillH),
                            cornerRadius = cr
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {}

        Spacer(modifier = Modifier.height(TrackToIconSpacing))

        Box(
            modifier = Modifier
                .size(SliderIconContainerSize)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onIconClick
                ),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

@Composable
fun VolumeSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    sliderItems: List<VolumeSliderItem.Stream>,
    showPercentage: Boolean = false,
    sliderCount: Int = MaxVisibleSliders,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = SliderRowHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(SliderRowSpacing)
    ) {
        val padded = sliderItems.take(sliderCount)
        repeat(sliderCount) { index ->
            val item = padded.getOrNull(index)
            if (item != null) {
                key(item.model.streamType) {
                    SliderColumn(
                        stream = item.model,
                        viewModel = viewModel,
                        touchWidth = SliderWidthExpanded,
                        showPercentage = showPercentage
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(SliderWidthExpanded))
            }
        }
    }
}

@Composable
fun AppVolumeSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    appItems: List<VolumeSliderItem.AppVolume>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(horizontal = SliderRowHorizontalPadding)
            .padding(top = TrackToIconSpacing),
        horizontalArrangement = Arrangement.spacedBy(SliderRowSpacing)
    ) {
        val padded = appItems.take(MaxVisibleSliders)
        repeat(MaxVisibleSliders) { index ->
            val item = padded.getOrNull(index)
            if (item != null) {
                key(item.model.packageName) {
                    AppVolumeSlider(
                        appVolume = item.model,
                        viewModel = viewModel,
                        touchWidth = SliderWidthExpanded,
                        showPercentage = false
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(SliderWidthExpanded))
            }
        }
    }
}
