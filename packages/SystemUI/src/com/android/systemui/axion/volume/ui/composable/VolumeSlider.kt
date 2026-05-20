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
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axion.volume.ui.composable

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider as VolumeDialogSlider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import java.text.NumberFormat
import kotlin.math.roundToInt

private val GrayscaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val GrayscaleFilter = ColorFilter.colorMatrix(GrayscaleMatrix)

@Composable
fun SliderColumn(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    touchWidth: Dp = CollapsedPanelWidth,
    iconSize: Dp = SliderIconSize,
    showPercentage: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val muted = stream.isMuted
    val valueRange = stream.minLevel.toFloat()..stream.maxLevel.toFloat()
    val motionScheme = MaterialTheme.motionScheme
    val iconAlpha by animateFloatAsState(
        if (muted) 0.38f else 1f,
        motionScheme.fastEffectsSpec(),
        label = "iconAlpha"
    )

    VolumeSlider(
        value = if (muted) valueRange.start else stream.sliderLevel(),
        valueRange = valueRange,
        onValueChange = { viewModel.setVolume(stream.streamType, it) },
        onOverscroll = { viewModel.setOverscrollOffset(it) },
        touchWidth = touchWidth,
        stepDistance = 1f,
        modifier = modifier,
        hapticsViewModelFactory = viewModel.sliderHapticsViewModelFactory,
        contentDescription = stream.streamInfo.label,
        icon = {
            Icon(
                painter = painterResource(if (muted) stream.mutedIconRes else stream.iconRes),
                contentDescription = stream.streamInfo.label,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { alpha = iconAlpha },
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
    showPercentage: Boolean = true
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
        hapticsViewModelFactory = viewModel.sliderHapticsViewModelFactory,
        contentDescription = label,
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
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
    onOverscroll: (Float) -> Unit,
    touchWidth: Dp,
    stepDistance: Float = 0.01f,
    modifier: Modifier = Modifier,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    contentDescription: String,
    icon: @Composable () -> Unit,
    onIconClick: () -> Unit,
    showPercentage: Boolean = false,
    onInteractionStart: () -> Unit = {},
    onInteractionEnd: () -> Unit = {},
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnOverscroll by rememberUpdatedState(onOverscroll)
    val interactionSource = remember { MutableInteractionSource() }
    val view = LocalView.current

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> onInteractionStart()
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> {
                    onInteractionEnd()
                    currentOnOverscroll(0f)
                }
            }
        }
    }

    val colors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledActiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    Column(
        modifier = modifier.width(touchWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showPercentage) {
            VolumeSliderPercentage(value = value, valueRange = valueRange)
        }
        Spacer(modifier = Modifier.height(SliderTopPadding))

        VolumeDialogSlider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChanged = { newValue ->
                currentOnValueChange(newValue)
            },
            onValueChangeFinished = {
                onInteractionEnd()
                currentOnOverscroll(0f)
            },
            isEnabled = true,
            isVertical = true,
            isReverseDirection = true,
            interactionSource = interactionSource,
            colors = colors,
            stepDistance = stepDistance,
            accessibilityParams = AccessibilityParams(contentDescription = contentDescription),
            haptics = Haptics.Enabled(
                hapticsViewModelFactory = hapticsViewModelFactory,
                hapticConfigs = VolumeHapticsConfigsProvider.continuousConfigs(
                    SliderHapticFeedbackFilter()
                ),
                orientation = Orientation.Vertical,
            ),
            track = { sliderState ->
                SliderTrack(
                    sliderState = sliderState,
                    colors = colors,
                    isEnabled = true,
                    isVertical = true,
                    trackSize = SliderTrackWidthThick,
                    activeTrackEndIcon = { iconsState ->
                        VolumeSliderIcon(
                            isVisible = !iconsState.isInactiveTrackEndIconVisible,
                            onIconClick = onIconClick,
                            icon = icon,
                        )
                    },
                    inactiveTrackEndIcon = { iconsState ->
                        VolumeSliderIcon(
                            isVisible = iconsState.isInactiveTrackEndIconVisible,
                            onIconClick = onIconClick,
                            icon = icon,
                        )
                    },
                )
            },
            thumb = { sliderState, interactions ->
                SliderDefaults.Thumb(
                    sliderState = sliderState,
                    interactionSource = interactions,
                    enabled = true,
                    colors = colors,
                    thumbSize = DpSize(SliderThumbWidth, SliderThumbHeight),
                )
            },
            modifier = Modifier
                .height(SliderTrackHeight)
                .width(touchWidth)
                .trackSliderOverscroll(
                    onOverscroll = currentOnOverscroll,
                    view = view,
                ),
        )
    }
}

@Composable
private fun VolumeSliderPercentage(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val percentFormat = remember {
        NumberFormat.getPercentInstance().apply {
            maximumFractionDigits = 0
        }
    }
    val percent = value.percentIn(valueRange)
    Box(
        modifier = Modifier
            .width(SliderIconContainerSize)
            .padding(top = SliderTopPadding + TrackToIconSpacing / 2f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = percentFormat.format(percent),
            style = MaterialTheme.typography.labelMedium.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            color = volumePanelSecondaryContentColor(),
            maxLines = 1,
        )
    }
}

private fun AxionVolumeStreamModel.sliderLevel(): Float {
    val range = maxLevel - minLevel
    if (range <= 0) return minLevel.toFloat()
    return (minLevel + level.coerceIn(0f, 1f) * range)
        .roundToInt()
        .coerceIn(minLevel, maxLevel)
        .toFloat()
}

private fun Float.percentIn(valueRange: ClosedFloatingPointRange<Float>): Float {
    val range = valueRange.endInclusive - valueRange.start
    if (range <= 0f) return 0f
    return ((this - valueRange.start) / range).coerceIn(0f, 1f)
}

@Composable
private fun VolumeSliderIcon(
    isVisible: Boolean,
    onIconClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    SliderIcon(
        icon = {
            Box(
                modifier = Modifier
                    .size(SliderIconContainerSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onIconClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        },
        isVisible = isVisible,
    )
}

private fun Modifier.trackSliderOverscroll(
    onOverscroll: (Float) -> Unit,
    view: View,
): Modifier = pointerInput(onOverscroll, view) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var wasAtEdge = false
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent()
            val pointer = event.changes.firstOrNull { it.pressed }
            if (pointer != null) {
                val rawValue = 1f - pointer.position.y / size.height
                val overscroll = when {
                    rawValue < 0f -> (-rawValue * 30f).coerceAtMost(10f)
                    rawValue > 1f -> (-(rawValue - 1f) * 30f).coerceAtLeast(-10f)
                    else -> 0f
                }
                if (overscroll != 0f && !wasAtEdge) {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
                wasAtEdge = overscroll != 0f
                onOverscroll(overscroll)
            }
            pressed = event.changes.any { it.pressed }
        }
        onOverscroll(0f)
    }
}

@Composable
fun VolumeSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    sliderItems: List<VolumeSliderItem.Stream>,
    showPercentage: Boolean = true,
    sliderCount: Int = MaxVisibleSliders,
    modifier: Modifier = Modifier,
    streamSliderModifier: (Int) -> Modifier = { Modifier },
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
                        showPercentage = showPercentage,
                        modifier = streamSliderModifier(item.model.streamType)
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
            .padding(vertical = TrackToIconSpacing),
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
                        showPercentage = true
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(SliderWidthExpanded))
            }
        }
    }
}
