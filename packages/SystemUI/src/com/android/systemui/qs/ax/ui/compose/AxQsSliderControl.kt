/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.qs.ax.ui.compose

import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderColors
import com.android.compose.PlatformSliderDefaults
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import kotlin.math.round
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
private fun AxQsSliderControl(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    interceptParentScroll: Boolean = true,
    mirrorInOverlay: Boolean = false,
    entranceProgress: () -> Float = { 1f },
    content: @Composable (sliderHeight: Dp, vertical: Boolean) -> Unit,
) {
    val mirrorModifier =
        if (mirrorInOverlay) {
            Modifier.drawInOverlay().axQsEntrance(entranceProgress)
        } else {
            Modifier
        }
    Box(
        modifier = modifier.then(mirrorModifier),
        contentAlignment = Alignment.Center,
    ) {
        val view = LocalView.current
        val layoutDirection = LocalLayoutDirection.current
        val inputModifier =
            if (vertical) {
                Modifier.interceptParentScroll(interceptParentScroll, view)
            } else {
                Modifier
            }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(inputModifier),
            contentAlignment = Alignment.Center,
        ) {
            val sliderWidth = if (vertical) maxHeight else maxWidth
            val availableHeight = if (vertical) maxWidth else HorizontalSliderThumbHeight
            val sliderHeight = axQsSliderTrackHeight(availableHeight, vertical)
            val sliderLayoutDirection = if (vertical) LayoutDirection.Ltr else layoutDirection
            Box(
                modifier =
                    Modifier.requiredWidth(sliderWidth)
                        .requiredHeight(availableHeight)
                        .rotate(if (vertical) -90f else 0f),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides sliderLayoutDirection) {
                    content(sliderHeight, vertical)
                }
            }
        }
    }
}

private fun Modifier.interceptParentScroll(enabled: Boolean, view: View): Modifier {
    if (!enabled) return this
    return pointerInput(view) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            view.parent?.requestDisallowInterceptTouchEvent(true)
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    pressed = event.changes.any { it.pressed }
                }
            } finally {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}

internal fun axQsControlShape(
    control: AxQsControl,
    span: AxQsSpan,
    verticalStyle: AxQsVerticalSliderStyle = AxQsVerticalSliderStyle.M3_EXPRESSIVE,
): Shape {
    return when {
        control == AxQsControl.RINGER -> CircleShape
        control.isVerticalSlider ->
            when (verticalStyle) {
                AxQsVerticalSliderStyle.M3_EXPRESSIVE -> VerticalSliderShape
                AxQsVerticalSliderStyle.PLATFORM -> CircleShape
            }
        control.isHorizontalSlider -> HorizontalSliderShape
        span == AxQsSpan.TileDefault && control != AxQsControl.MEDIA -> CircleShape
        else -> RoundedCornerShape(AxQsControlCornerRadius)
    }
}

internal val AxQsControlCornerRadius = 24.dp
private val HorizontalSliderCornerRadius = 12.dp
private val VerticalSliderShape = RoundedCornerShape(30)
private val HorizontalSliderShape = RoundedCornerShape(HorizontalSliderCornerRadius)
private val AxSliderTrackHeight = 64.dp
private val PlatformSliderFramePadding = 4.dp
private val AxSliderTrackInsideCornerRadius = 2.dp
private val AxSliderThumbWidth = 4.dp
private val AxSliderThumbTrackGap = 6.dp
private val AxSliderIconSize = 20.dp
private val HorizontalSliderTrackHeight = 40.dp
private val HorizontalSliderThumbHeight = 52.dp
private val HorizontalSliderIconSize = 28.dp
private const val VERTICAL_SLIDER_CORNER_DIVISOR = 3.333f

internal fun axQsSliderTrackHeight(availableHeight: Dp, vertical: Boolean): Dp {
    return if (vertical) {
        AxSliderTrackHeight * (availableHeight / CommonTileDefaults.TileHeight)
    } else {
        HorizontalSliderTrackHeight
    }
}

@Composable
private fun AxQsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    sliderHeight: Dp,
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    trackBackgroundColor: Color = Color.Transparent,
    icon: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inactiveTrackColor = AxTileDefaults.backgroundColor()
    val sliderScale = if (vertical) sliderHeight / AxSliderTrackHeight else 1f
    val iconSize = if (vertical) AxSliderIconSize * sliderScale else HorizontalSliderIconSize
    if (vertical && verticalStyle == AxQsVerticalSliderStyle.PLATFORM) {
        val platformTrackColor =
            if (trackBackgroundColor == Color.Transparent) {
                inactiveTrackColor
            } else {
                trackBackgroundColor
        }
        val framePadding = PlatformSliderFramePadding * sliderScale
        val frameHeight = CommonTileDefaults.TileHeight * sliderScale
        val platformSliderHeight = (frameHeight - framePadding * 2).coerceAtLeast(0.dp)
        Box(
            modifier =
                modifier
                    .requiredHeight(frameHeight)
                    .clip(RoundedCornerShape(frameHeight / 2))
                    .background(platformTrackColor)
                    .padding(framePadding),
            contentAlignment = Alignment.Center,
        ) {
            PlatformSlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                enabled = enabled,
                interactionSource = interactionSource,
                colors =
                    PlatformSliderColors(
                        trackColor = platformTrackColor,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        iconColor = MaterialTheme.colorScheme.onPrimary,
                        labelColorOnIndicator = MaterialTheme.colorScheme.onPrimary,
                        labelColorOnTrack = MaterialTheme.colorScheme.onSurface,
                        disabledTrackColor = platformTrackColor.copy(alpha = 0.38f),
                        disabledIndicatorColor =
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledIconColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        disabledLabelColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                sliderHeight = platformSliderHeight,
                showEndDot = false,
                draggingCornersRadius =
                    PlatformSliderDefaults.DefaultPlatformSliderDraggingCornerRadius * sliderScale,
                icon = { icon(Modifier.size(iconSize).rotate(90f)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    val colors =
        SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            activeTickColor = MaterialTheme.colorScheme.onPrimary,
            inactiveTrackColor = inactiveTrackColor,
            inactiveTickColor = MaterialTheme.colorScheme.onSurface,
            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            disabledActiveTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
            disabledInactiveTrackColor = inactiveTrackColor.copy(alpha = 0.38f),
            disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    val thumbSize =
        if (vertical) {
            DpSize(
                width = AxSliderThumbWidth * sliderScale,
                height = CommonTileDefaults.TileHeight * sliderScale,
            )
        } else {
            DpSize(width = AxSliderThumbWidth, height = HorizontalSliderThumbHeight)
        }
    val trackCornerSize =
        if (vertical) sliderHeight / VERTICAL_SLIDER_CORNER_DIVISOR
        else HorizontalSliderCornerRadius
    val trackShape = if (vertical) VerticalSliderShape else HorizontalSliderShape
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = colors,
        modifier = modifier,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                enabled = enabled,
                colors = colors,
                thumbSize = thumbSize,
            )
        },
        track = { sliderState ->
            SliderTrack(
                sliderState = sliderState,
                isEnabled = enabled,
                colors = colors,
                trackCornerSize = trackCornerSize,
                trackInsideCornerSize = AxSliderTrackInsideCornerRadius,
                thumbTrackGapSize = AxSliderThumbTrackGap,
                trackSize = sliderHeight,
                modifier = Modifier.background(trackBackgroundColor, trackShape),
                activeTrackEndIcon = { iconsState ->
                    AxQsSliderIcon(
                        visible =
                            if (vertical) {
                                iconsState.isActiveTrackEndIconVisible
                            } else {
                                !iconsState.isInactiveTrackEndIconVisible
                            },
                        vertical = vertical,
                        size = iconSize,
                        icon = icon,
                    )
                },
                inactiveTrackEndIcon = { iconsState ->
                    AxQsSliderIcon(
                        visible =
                            if (vertical) {
                                !iconsState.isActiveTrackEndIconVisible
                            } else {
                                iconsState.isInactiveTrackEndIconVisible
                            },
                        vertical = vertical,
                        size = iconSize,
                        icon = icon,
                    )
                },
            )
        },
    )
}

@Composable
private fun AxQsSliderIcon(
    visible: Boolean,
    vertical: Boolean,
    size: Dp,
    icon: @Composable (Modifier) -> Unit,
) {
    SliderIcon(
        isVisible = visible,
        icon = { icon(Modifier.size(size).rotate(if (vertical) 90f else 0f)) },
    )
}

@Composable
internal fun AxQsSliderPreview(
    control: AxQsControl,
    verticalStyle: AxQsVerticalSliderStyle,
    brightnessViewModel: BrightnessSliderViewModel,
    volumeViewModel: AudioStreamSliderViewModel,
    modifier: Modifier = Modifier,
) {
    AxQsSliderControl(
        vertical = control.isVerticalSlider,
        modifier = modifier,
        interceptParentScroll = false,
    ) {
        sliderHeight,
        vertical ->
        Box(Modifier.fillMaxSize().gesturesDisabled().clearAndSetSemantics {}) {
            when (control) {
                AxQsControl.BRIGHTNESS,
                AxQsControl.BRIGHTNESS_HORIZONTAL ->
                    AxBrightnessSlider(
                        viewModel = brightnessViewModel,
                        sliderHeight = sliderHeight,
                        vertical = vertical,
                        verticalStyle = verticalStyle,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                AxQsControl.VOLUME,
                AxQsControl.VOLUME_HORIZONTAL ->
                    AxVolumeSlider(
                        viewModel = volumeViewModel,
                        interactive = false,
                        sliderHeight = sliderHeight,
                        vertical = vertical,
                        verticalStyle = verticalStyle,
                        modifier = Modifier.fillMaxSize(),
                    )
                AxQsControl.AUTO_BRIGHTNESS,
                AxQsControl.VOLUME_MUTE,
                AxQsControl.RINGER,
                AxQsControl.MEDIA -> Unit
            }
        }
    }
}

@Composable
internal fun AxQsBrightnessControl(
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    viewModel: BrightnessSliderViewModel,
    entranceProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    var dragging by remember(vertical) { mutableStateOf(false) }
    val mirrorInOverlay = dragging && viewModel.showMirror
    AxQsSliderControl(
        vertical = vertical,
        mirrorInOverlay = mirrorInOverlay,
        entranceProgress = entranceProgress,
        modifier = modifier,
    ) {
        sliderHeight,
        vertical ->
        AxBrightnessSlider(
            viewModel = viewModel,
            onDraggingChanged = { dragging = it },
            sliderHeight = sliderHeight,
            vertical = vertical,
            verticalStyle = verticalStyle,
            mirrorInOverlay = mirrorInOverlay,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AxBrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    interactive: Boolean = true,
    mirrorInOverlay: Boolean = false,
    onDraggingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) return

    var value by remember(gamma) { mutableIntStateOf(gamma) }
    val animatedValue by
        animateFloatAsState(targetValue = value.toFloat(), label = "AxBrightnessSliderValue")
    val valueRange =
        viewModel.minBrightness.value.toFloat()..viewModel.maxBrightness.value.toFloat()
    val percentage =
        ((value - valueRange.start) * 100f / (valueRange.endInclusive - valueRange.start)).coerceIn(
            0f,
            100f,
        )
    val iconRes =
        if (viewModel.autoMode) {
            R.drawable.ic_qs_brightness_auto_on
        } else {
            BrightnessSliderViewModel.getIconForPercentage(percentage)
        }
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel =
        rememberViewModel(traceName = "AxBrightnessSliderHaptics") {
            viewModel.hapticsViewModelFactory.create(
                interactionSource,
                valueRange,
                Orientation.Horizontal,
                SliderHapticFeedbackConfig(maxVelocityToScale = 1f),
                SeekableSliderTrackerConfig(),
            )
        }
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val restricted = restriction as? PolicyRestriction.Restricted
    val enabled = restricted == null
    val overriddenByApp by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val contentDescription = stringResource(R.string.accessibility_brightness)
    val mirrorBackgroundColor = AxTileDefaults.backgroundColor()
    var dragging by remember { mutableStateOf(false) }
    val currentDragging by rememberUpdatedState(dragging)
    val currentOnDraggingChanged by rememberUpdatedState(onDraggingChanged)
    val inputModifier =
        if (interactive) {
            Modifier
                .semantics(mergeDescendants = true) {
                    text = AnnotatedString(contentDescription)
                }
                .sliderPercentage {
                    (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                }
                .pointerInteropFilter {
                    if (
                        it.actionMasked == MotionEvent.ACTION_UP ||
                            it.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        viewModel.emitBrightnessTouchForFalsing()
                    }
                    false
                }
                .then(
                    if (restricted != null) {
                        Modifier.clickable { viewModel.showPolicyRestrictionDialog(restricted) }
                    } else {
                        Modifier
                    }
                )
        } else {
            Modifier
        }

    DisposableEffect(viewModel) {
        onDispose {
            if (currentDragging) {
                viewModel.setIsDragging(false)
                currentOnDraggingChanged(false)
            }
        }
    }

    LaunchedEffect(interactionSource, overriddenByApp) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && overriddenByApp) {
                viewModel.showToast(context, R.string.quick_settings_brightness_unable_adjust_msg)
            }
        }
    }

    AxQsSlider(
        value = animatedValue,
        onValueChange = { newValue ->
            if (interactive && enabled && !overriddenByApp) {
                hapticsViewModel.onValueChange(newValue)
                value = newValue.toInt()
                if (!dragging) {
                    dragging = true
                    viewModel.setIsDragging(true)
                    onDraggingChanged(true)
                }
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(value))) }
            }
        },
        onValueChangeFinished = {
            if (interactive && enabled && !overriddenByApp) {
                hapticsViewModel.onValueChangeEnded()
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(value))) }
            }
            if (dragging) {
                dragging = false
                viewModel.setIsDragging(false)
                onDraggingChanged(false)
            }
        },
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        sliderHeight = sliderHeight,
        vertical = vertical,
        verticalStyle = verticalStyle,
        trackBackgroundColor = if (mirrorInOverlay) mirrorBackgroundColor else Color.Transparent,
        icon = { iconModifier ->
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = iconModifier,
            )
        },
        modifier =
            modifier
                .fillMaxWidth()
                .sysuiResTag("ax_brightness_slider")
                .then(inputModifier),
    )
}

@Composable
internal fun AxQsVolumeControl(
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    viewModel: AudioStreamSliderViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    AxQsSliderControl(
        vertical = vertical,
        modifier = modifier,
    ) { sliderHeight, rotated ->
        AxVolumeSliderContent(
            state = state,
            viewModel = viewModel,
            sliderHeight = sliderHeight,
            vertical = rotated,
            verticalStyle = verticalStyle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AxVolumeSlider(
    viewModel: AudioStreamSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    AxVolumeSliderContent(
        state = state,
        viewModel = viewModel,
        sliderHeight = sliderHeight,
        vertical = vertical,
        verticalStyle = verticalStyle,
        interactive = interactive,
        modifier = modifier,
    )
}

@Composable
private fun AxVolumeSliderContent(
    state: SliderState,
    viewModel: AudioStreamSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    verticalStyle: AxQsVerticalSliderStyle,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val value by axVolumeValueState(state)
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel =
        axVolumeHapticsViewModel(
            value = value,
            state = state,
            interactionSource = interactionSource,
            factory = if (interactive) viewModel.getSliderHapticsViewModelFactory() else null,
        )

    AxQsSlider(
        value = value,
        valueRange = state.valueRange,
        onValueChange = { newValue ->
            hapticsViewModel?.addVelocityDataPoint(newValue)
            if (interactive) viewModel.onValueChanged(state, newValue)
        },
        onValueChangeFinished = {
            hapticsViewModel?.onValueChangeEnded()
            if (interactive) viewModel.onValueChangeFinished()
        },
        enabled = state.isEnabled,
        interactionSource = interactionSource,
        sliderHeight = sliderHeight,
        vertical = vertical,
        verticalStyle = verticalStyle,
        icon = { iconModifier ->
            val icon = state.icon
            if (icon != null) {
                SystemUiIcon(icon = icon, modifier = iconModifier)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    modifier = iconModifier,
                )
            }
        },
        modifier =
            modifier.sysuiResTag("ax_volume_slider").clearAndSetSemantics {
                if (state.isEnabled) {
                    contentDescription = state.a11yContentDescription
                    state.a11yStateDescription?.let { stateDescription = it }
                    progressBarRangeInfo = ProgressBarRangeInfo(state.value, state.valueRange)
                    if (interactive) {
                        setProgress { targetValue ->
                            val direction =
                                when {
                                    targetValue > value -> 1
                                    targetValue < value -> -1
                                    else -> 0
                                }
                            viewModel.onValueChanged(
                                state,
                                (value + direction * state.step).coerceIn(
                                    state.valueRange.start,
                                    state.valueRange.endInclusive,
                                ),
                            )
                            true
                        }
                    }
                } else {
                    disabled()
                    contentDescription =
                        state.disabledMessage?.let { "${state.label}, $it" } ?: state.label
                }
            },
    )
}

@Composable
private fun axVolumeValueState(state: SliderState): State<Float> {
    val value = remember { Animatable(state.value) }
    var previousState by remember { mutableStateOf<SliderState?>(null) }
    LaunchedEffect(state.value, state.isEnabled) {
        val previous = previousState
        previousState = state
        if (
            previous == null ||
                previous is SliderState.Empty ||
                previous.isEnabled != state.isEnabled
        ) {
            value.snapTo(state.value)
        } else {
            value.animateTo(state.value)
        }
    }
    return value.asState()
}

@Composable
private fun axVolumeHapticsViewModel(
    value: Float,
    state: SliderState,
    interactionSource: MutableInteractionSource,
    factory: SliderHapticsViewModel.Factory?,
): SliderHapticsViewModel? {
    return factory?.let {
        val configs =
            VolumeHapticsConfigsProvider.discreteConfigs(
                1f / (state.valueRange.endInclusive - state.valueRange.start),
                state.hapticFilter,
            )
        rememberViewModel(traceName = "AxVolumeSliderHaptics") {
                it.create(
                    interactionSource,
                    state.valueRange,
                    Orientation.Horizontal,
                    configs.hapticFeedbackConfig,
                    configs.sliderTrackerConfig,
                )
            }
            .also { hapticsViewModel ->
                var lastStep by remember { mutableFloatStateOf(round(value)) }
                LaunchedEffect(value) {
                    snapshotFlow { value }
                        .map { round(it) }
                        .filter { it != lastStep }
                        .distinctUntilChanged()
                        .collect { step ->
                            lastStep = step
                            hapticsViewModel.onValueChange(step)
                        }
                }
            }
    }
}
