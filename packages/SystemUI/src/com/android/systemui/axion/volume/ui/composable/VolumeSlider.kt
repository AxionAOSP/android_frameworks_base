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

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.theme.UiStyleProvider
import com.android.systemui.theme.TrackPattern

@Composable
fun SliderColumn(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp = SliderHeightTarget,
    iconSize: Dp = SliderIconSize
) {
    val info = stream.streamInfo
    val muted = stream.isMuted
    val iconAlpha by animateFloatAsState(if (muted) 0.5f else 1f, label = "iconAlpha")

    VolumeSlider(
        value = if (muted) 0f else stream.level,
        onValueChange = { viewModel.setVolume(stream.streamType, it) },
        isMuted = muted,
        modifier = Modifier.height(sliderHeight).width(SliderWidthExpanded),
        viewModel = viewModel,
        icon = {
            Icon(
                imageVector = if (muted) info.mutedIcon else info.icon,
                contentDescription = info.label,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = iconAlpha),
                modifier = Modifier.size(iconSize)
            )
        },
        onIconClick = { viewModel.toggleMute(stream.streamType) },
        iconSize = iconSize
    )
}

@Composable
fun AppVolumeSlider(
    appVolume: AxionAppVolumeModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp,
    iconSize: Dp = SliderIconSize
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appInfo = remember(appVolume.packageName) {
        try { pm.getApplicationInfo(appVolume.packageName, 0) } catch (e: Exception) { null }
    }
    val label = remember(appInfo) { appInfo?.loadLabel(pm)?.toString() ?: appVolume.packageName }
    val icon = remember(appInfo) { appInfo?.loadIcon(pm) }
    val isMutedOrZero = appVolume.isMuted || appVolume.volume == 0f
    val iconAlpha by animateFloatAsState(if (isMutedOrZero) 0.5f else 1f, label = "appIconAlpha")

    VolumeSlider(
        value = if (appVolume.isMuted) 0f else appVolume.volume,
        onValueChange = { viewModel.setAppVolume(appVolume.packageName, it) },
        isMuted = appVolume.isMuted,
        modifier = Modifier.height(sliderHeight).width(SliderWidthExpanded),
        viewModel = viewModel,
        icon = {
            if (icon != null) {
                Image(
                    bitmap = icon.toBitmap().asImageBitmap(),
                    contentDescription = label,
                    colorFilter = if (isMutedOrZero) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    } else null,
                    modifier = Modifier.size(iconSize).clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = iconAlpha),
                    modifier = Modifier.size(iconSize)
                )
            }
        },
        onIconClick = { viewModel.setAppMute(appVolume.packageName, !appVolume.isMuted) },
        iconSize = iconSize
    )
}

@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    viewModel: AxionVolumeDialogViewModel,
    icon: @Composable () -> Unit,
    onIconClick: () -> Unit,
    iconSize: Dp = SliderIconSize
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    
    LaunchedEffect(value) {
        if (!isDragging) {
            sliderValue = value
        }
    }
    
    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel = key(viewModel) {
        rememberViewModel(traceName = "VolumeSliderHaptics") {
            viewModel.sliderHapticsViewModelFactory.create(
                interactionSource,
                0f..1f,
                Orientation.Vertical,
                SliderHapticFeedbackConfig(),
                SeekableSliderTrackerConfig()
            )
        }
    }

    val animatedValue by remember {
        derivedStateOf { if (isDragging) sliderValue else sliderValue }
    }.let { derived ->
        animateFloatAsState(
            targetValue = derived.value,
            animationSpec = if (isDragging) snap() else tween(50),
            label = "v"
        )
    }

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    val style = UiStyleProvider.rememberCurrentStyle()

    Column(
        modifier = modifier, 
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .wrapContentWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val maxHeight = maxHeight
            val expandedWidthPx = with(density) { style.volumeSliderTrackWidthExpanded.toPx() }
            val collapsedWidthPx = with(density) { style.volumeSliderTrackWidthCollapsed.toPx() }
            val maxHeightPx = with(density) { maxHeight.toPx() }
            
            val threshold = if (maxHeightPx > 0) expandedWidthPx / maxHeightPx else 0f
            
            val targetWidth by remember(sliderValue, isDragging, isPressed, threshold, style) {
                derivedStateOf {
                    if (!isDragging && !isPressed) {
                        style.volumeSliderTrackWidthCollapsed
                    } else {
                        if (sliderValue >= threshold) {
                            style.volumeSliderTrackWidthExpanded
                        } else {
                            val fraction = (sliderValue / threshold).coerceIn(0f, 1f)
                            style.volumeSliderTrackWidthCollapsed + (style.volumeSliderTrackWidthExpanded - style.volumeSliderTrackWidthCollapsed) * fraction
                        }
                    }
                }
            }
            
            val animatedWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "width"
            )

            val cornerRadius = style.volumeSliderCornerRadius

            Box(
                modifier = Modifier
                    .width(animatedWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(cornerRadius))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            },
                            onTap = {
                                viewModel.isInteracting = true
                                sliderValue = 1f - (it.y / size.height).coerceIn(0f, 1f)
                                onValueChange(sliderValue)
                                viewModel.isInteracting = false
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDragging = true
                                sliderValue = 1f - (it.y / size.height).coerceIn(0f, 1f)
                                onValueChange(sliderValue)
                                hapticsViewModel.onValueChange(sliderValue)
                                viewModel.isInteracting = true
                            },
                            onDragEnd = { 
                                isDragging = false
                                hapticsViewModel.onValueChangeEnded()
                                viewModel.isInteracting = false
                                viewModel.setOverscrollOffset(0f)
                            },
                            onDragCancel = { 
                                isDragging = false
                                hapticsViewModel.onValueChangeEnded()
                                viewModel.isInteracting = false
                                viewModel.setOverscrollOffset(0f)
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val rawPosition = 1f - (change.position.y / size.height)
                                
                                when {
                                    rawPosition < 0f -> {
                                        sliderValue = 0f
                                        val offset = (-rawPosition * 30f).coerceAtMost(10f)
                                        viewModel.setOverscrollOffset(offset)
                                    }
                                    rawPosition > 1f -> {
                                        sliderValue = 1f
                                        val offset = (-(rawPosition - 1f) * 30f).coerceAtLeast(-10f)
                                        viewModel.setOverscrollOffset(offset)
                                    }
                                    else -> {
                                        sliderValue = rawPosition
                                        viewModel.setOverscrollOffset(0f)
                                    }
                                }
                                hapticsViewModel.addVelocityDataPoint(sliderValue)
                                hapticsViewModel.onValueChange(sliderValue)
                                onValueChange(sliderValue)
                            }
                        )
                    }
                    .drawBehind {
                        val h = size.height
                        val w = size.width
                        val cx = w / 2

                        val cornerRadiusPx = cornerRadius.toPx()
                        
                        if (style.volumeTrackPattern == TrackPattern.DOT_MATRIX) {
                            val dotRadius = style.volumeTrackDotRadius
                            val dotSpacing = style.volumeTrackDotSpacing
                            val dotColor = onSurface.copy(alpha = 0.2f)
                            
                            var y = dotRadius + 2f
                            while (y < h - dotRadius) {
                                drawCircle(dotColor, dotRadius, Offset(cx, y))
                                y += dotSpacing
                            }
                        } else {
                            val trackColor = onSurface.copy(alpha = 0.12f)
                            drawRoundRect(
                                color = trackColor,
                                cornerRadius = CornerRadius(cornerRadiusPx)
                            )
                        }

                        val minHeight = w
                        val ph = minHeight + (h - minHeight) * animatedValue
                        
                        drawRoundRect(
                            color = primary,
                            topLeft = Offset(0f, h - ph),
                            size = Size(w, ph),
                            cornerRadius = CornerRadius(cornerRadiusPx)
                        )
                    }
            )
        }

        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(CircleShape)
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
    showingAppVolumes: Boolean,
    hasAppVolumes: Boolean,
    isLeftSide: Boolean,
    musicStream: AxionVolumeStreamModel?,
    otherStreams: List<AxionVolumeStreamModel>,
    appVolumes: List<AxionAppVolumeModel>,
    sliderHeight: Dp,
    iconSize: Dp = SliderIconSize
) {
    Box(
        modifier = Modifier
            .height(sliderHeight)
            .width(SliderExpandedContentWidth - 20.dp),
        contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        if (showingAppVolumes && hasAppVolumes) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (musicStream != null) {
                    SliderColumn(musicStream, viewModel, sliderHeight, iconSize)
                }
                appVolumes.forEach { appVolume ->
                    key(appVolume.packageName) {
                        AppVolumeSlider(appVolume, viewModel, sliderHeight, iconSize)
                    }
                }
            }
        } else {
            val orderedStreams = remember(isLeftSide, musicStream, otherStreams) {
                if (isLeftSide) {
                    listOfNotNull(musicStream) + otherStreams
                } else {
                    otherStreams + listOfNotNull(musicStream)
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(
                    space = SliderSpacing,
                    alignment = if (isLeftSide) Alignment.Start else Alignment.End
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                orderedStreams.forEach { stream ->
                    SliderColumn(stream, viewModel, sliderHeight, iconSize)
                }
            }
        }
    }
}
