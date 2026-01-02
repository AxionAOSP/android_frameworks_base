/*
 * Copyright (C) 2025 Axion OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.axion.volume.ui.composable

import android.content.res.Configuration
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.domain.model.AxionStreamInfo
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.theme.UiStyleProvider
import kotlinx.coroutines.delay

private const val SLIDE_DURATION = 200

@Composable
fun AxionVolumeDialogContent(
    viewModel: AxionVolumeDialogViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVisible = uiState.isVisible
    val isLeft = uiState.isLeftSide
    val isExpanded = uiState.isExpanded

    var animateIn by remember { mutableStateOf(false) }
    
    LaunchedEffect(isVisible) {
        animateIn = isVisible
    }

    val visibilityProgress by animateFloatAsState(
        targetValue = if (animateIn && isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = DialogAnimDuration, easing = FastOutSlowInEasing),
        label = "visibility"
    )

    val overscrollOffset by viewModel.overscrollOffset.collectAsStateWithLifecycle()
    val animatedOverscroll by animateFloatAsState(
        targetValue = overscrollOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "overscroll"
    )
    
    val view = LocalView.current
    LaunchedEffect(Unit) {
        viewModel.volumeKeyHapticTrigger.collect {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val slideOffset = with(LocalDensity.current) { 48.dp.toPx() }

    var showCollapsed by remember { mutableStateOf(!isExpanded) }
    var showExpanded by remember { mutableStateOf(isExpanded) }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            showCollapsed = false
            delay(DialogAnimDuration.toLong())
            showExpanded = true
        } else {
            showExpanded = false
            delay(DialogAnimDuration.toLong())
            showCollapsed = true
        }
    }

    val slideDirection = if (isLeft) -1 else 1

    Box(
        modifier = Modifier
            .graphicsLayer {
                alpha = visibilityProgress
                translationX = if (isLeft) -slideOffset * (1f - visibilityProgress) else slideOffset * (1f - visibilityProgress)
                translationY = animatedOverscroll
            },
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        AnimatedVisibility(
            visible = showCollapsed,
            enter = slideInHorizontally(
                initialOffsetX = { slideDirection * it },
                animationSpec = tween(DialogAnimDuration)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { slideDirection * it },
                animationSpec = tween(DialogAnimDuration)
            )
        ) {
            CollapsedVolumeDialog(viewModel = viewModel)
        }

        AnimatedVisibility(
            visible = showExpanded,
            enter = slideInHorizontally(
                initialOffsetX = { slideDirection * it },
                animationSpec = tween(DialogAnimDuration)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { slideDirection * it },
                animationSpec = tween(DialogAnimDuration)
            )
        ) {
            ExpandedVolumeDialog(viewModel = viewModel)
        }
    }
}

@Composable
private fun CollapsedVolumeDialog(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ringerMode = uiState.dialogState.ringerMode
    val isLeftSide = uiState.isLeftSide
    val style = UiStyleProvider.rememberCurrentStyle()
    val cornerRadius = style.dialogCornerRadius

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RingerButton(
            ringerMode = ringerMode,
            onClick = { 
                viewModel.rescheduleTimeout()
                viewModel.toggleExpanded() 
            },
            size = VolumeButtonsSize,
            cornerRadius = cornerRadius
        )

        Box(
            modifier = Modifier
                .width(SliderWidthCollapsed)
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surfaceBright),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                ActiveStreamSlider(viewModel = viewModel)
                
                Spacer(modifier = Modifier.height(18.dp))
                
                Box(
                    modifier = Modifier
                        .size(SliderIconSize + 8.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.rescheduleTimeout()
                            viewModel.toggleExpanded()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLeftSide) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Expand",
                        modifier = Modifier.size(SliderIconSize),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ExpandedVolumeDialog(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val isLeftSide = uiState.isLeftSide
    val style = UiStyleProvider.rememberCurrentStyle()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isPhoneLandscape = !isTablet && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val streamCount = dialogState.volumeStreams.size
    val appVolumeCount = dialogState.appVolumes.size
    val totalSliderCount = streamCount + appVolumeCount
    val targetPanelWidth = calculateExpandedPanelWidth(totalSliderCount, isLandscape = isPhoneLandscape)
    val panelWidth by animateDpAsState(
        targetValue = targetPanelWidth,
        label = "PanelWidthAnimation"
    )

    val collapseButton = @Composable {
        FilledTonalButton(
            onClick = { 
                viewModel.rescheduleTimeout()
                viewModel.toggleExpanded() 
            },
            modifier = Modifier.size(VolumeButtonsSize),
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(24.dp)
            )
        }
    }

    if (isPhoneLandscape) {
        ExpandedPanelContent(
            viewModel = viewModel,
            dialogState = dialogState,
            panelWidth = panelWidth,
            cornerRadius = style.dialogCornerRadius,
            isLandscape = true
        )
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpandedPanelContent(
                viewModel = viewModel,
                dialogState = dialogState,
                panelWidth = panelWidth,
                cornerRadius = style.dialogCornerRadius
            )

            collapseButton()
        }
    }
}

@Composable
private fun ExpandedPanelContent(
    viewModel: AxionVolumeDialogViewModel,
    dialogState: AxionVolumeDialogState,
    panelWidth: Dp,
    cornerRadius: Dp,
    isLandscape: Boolean = false
) {
    Box(
        modifier = Modifier
            .width(panelWidth)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val captionsEnabled = dialogState.captionsEnabled
                FilledTonalIconButton(
                    onClick = { 
                        viewModel.rescheduleTimeout()
                        viewModel.toggleCaptions() 
                    },
                    modifier = Modifier.size(VolumeButtonsSizeExpanded),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (captionsEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (captionsEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (captionsEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                        contentDescription = "Captions",
                        modifier = Modifier.size(20.dp)
                    )
                }

                val activeAppPkg = dialogState.activeAppPackageName
                val activeAppLabel = activeAppPkg?.let { pkg ->
                    dialogState.appVolumes.find { it.packageName == pkg }?.label
                }

                val title = activeAppLabel ?: run {
                    val activeStreamInfo = AxionStreamInfo.fromStreamType(dialogState.activeStream)
                    if (activeStreamInfo != AxionStreamInfo.UNKNOWN) activeStreamInfo.label else "Volume"
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { 
                            viewModel.rescheduleTimeout()
                            viewModel.onSeeMoreClick() 
                        },
                        modifier = Modifier.size(VolumeButtonsSizeExpanded),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                }
            }

            ExpandedSlidersRow(
                viewModel = viewModel,
                isLandscape = isLandscape
            )

            RingerSegmentedButton(
                viewModel = viewModel,
                ringerMode = dialogState.ringerMode,
                supportedModes = dialogState.supportedRingerModes
            )
        }
    }
}

@Composable
private fun ActiveStreamSlider(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeStream = uiState.dialogState.activeStream

    val streamModel = uiState.dialogState.volumeStreams.find { it.streamType == activeStream }
        ?: uiState.dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }

    if (streamModel != null) {
        SliderColumn(
            stream = streamModel,
            viewModel = viewModel,
            sliderHeight = SliderHeightTarget,
            iconSize = SliderIconSize,
            showPercentage = false
        )
    }
}

@Composable
private fun ExpandedSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    isLandscape: Boolean
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val isLeftSide = uiState.isLeftSide

    val sliderItems = remember(dialogState.volumeStreams, dialogState.appVolumes, isLeftSide) {
        val streamOrder = listOf(
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM
        )
        val musicStream = dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
        val otherStreams = dialogState.volumeStreams
            .filter { it.streamType != AudioManager.STREAM_MUSIC }
            .sortedBy { streamOrder.indexOf(it.streamType) }
        val appVolumeItems = dialogState.appVolumes.map { VolumeSliderItem.AppVolume(it) }

        buildList {
            if (isLeftSide) {
                musicStream?.let { add(VolumeSliderItem.Stream(it)) }
                otherStreams.forEach { add(VolumeSliderItem.Stream(it)) }
                addAll(appVolumeItems)
            } else {
                addAll(appVolumeItems)
                otherStreams.reversed().forEach { add(VolumeSliderItem.Stream(it)) }
                musicStream?.let { add(VolumeSliderItem.Stream(it)) }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        VolumeSlidersRow(
            viewModel = viewModel,
            sliderItems = sliderItems,
            sliderHeight = SliderHeightTarget,
            iconSize = SliderIconSize,
            showPercentage = true,
            modifier = if (isLandscape) {
                Modifier.padding(
                    start = if (!isLeftSide) 8.dp else 0.dp,
                    end = if (isLeftSide) 8.dp else 0.dp
                )
            } else Modifier
        )

        if (isLandscape) {
            Box(
                modifier = Modifier
                    .align(if (isLeftSide) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(VolumeButtonsSizeExpanded)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 20.dp)
                    ) { 
                        viewModel.rescheduleTimeout()
                        viewModel.toggleExpanded() 
                    },
                contentAlignment = if (isLeftSide) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Icon(
                    imageVector = if (isLeftSide) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Collapse",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
