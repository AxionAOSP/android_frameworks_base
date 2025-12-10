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

import android.media.AudioManager
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.theme.UiStyleProvider

@Composable
fun AxionVolumeDialogContent(
    viewModel: AxionVolumeDialogViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVisible = uiState.isVisible
    val isLeft = uiState.isLeftSide
    val dialogState = uiState.dialogState
    val appVolumes = dialogState.appVolumes
    val hasAppVolumes = appVolumes.isNotEmpty()

    var animateIn by remember { mutableStateOf(false) }
    
    LaunchedEffect(isVisible) {
        animateIn = isVisible
    }

    val visibilityProgress by animateFloatAsState(
        targetValue = if (animateIn && isVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = DialogAnimDuration,
            easing = FastOutSlowInEasing
        ),
        label = "visibility"
    )

    val overscrollOffset by viewModel.overscrollOffset.collectAsStateWithLifecycle()
    val animatedOverscroll by animateFloatAsState(
        targetValue = overscrollOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "overscroll"
    )
    
    val view = LocalView.current
    LaunchedEffect(Unit) {
        viewModel.volumeKeyHapticTrigger.collect {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val slideOffset = with(LocalDensity.current) { 48.dp.toPx() }
    
    val config = LocalConfiguration.current
    val screenHeightDp = config.screenHeightDp.dp
    
    val sizing = calculateVolumeDialogSizing(screenHeightDp)

    Column(
        modifier = Modifier
            .graphicsLayer {
                alpha = visibilityProgress
                translationX = if (isLeft) {
                    -slideOffset * (1f - visibilityProgress)
                } else {
                    slideOffset * (1f - visibilityProgress)
                }
                translationY = animatedOverscroll
            },
        horizontalAlignment = if (isLeft) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RingerControlButton(viewModel, buttonSize = sizing.ringerSize)
        
        VolumeSliderHolder(
            viewModel = viewModel,
            sliderHeight = sizing.sliderHeight,
            footerSize = sizing.footerSize,
            sliderIconSize = sizing.sliderIconSize
        )
    }
}

@Composable
private fun VolumeSliderHolder(
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp,
    footerSize: Dp,
    sliderIconSize: Dp
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val style = UiStyleProvider.rememberCurrentStyle()

    val isLeftSide = uiState.isLeftSide
    val isExpanded = uiState.isExpanded
    val shouldAnimateExpansion = uiState.shouldAnimateExpansion
    val showingAppVolumes = uiState.showingAppVolumes
    val appVolumes = dialogState.appVolumes
    val hasAppVolumes = appVolumes.isNotEmpty()

    val (musicStream, otherStreams) = remember(dialogState.volumeStreams) {
        val map = dialogState.volumeStreams.associateBy { it.streamType }
        val music = map[AudioManager.STREAM_MUSIC]
        val others = listOfNotNull(
            map[AudioManager.STREAM_VOICE_CALL],
            map[AudioManager.STREAM_RING],
            map[AudioManager.STREAM_NOTIFICATION],
            map[AudioManager.STREAM_ALARM]
        )
        music to others
    }

    val widthProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = if (shouldAnimateExpansion) {
            tween(durationMillis = 300, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "widthProgress"
    )

    val backgroundColor = MaterialTheme.colorScheme.surface
    val sidePadding = if (isExpanded) 10.dp else 6.dp

    Box(
        modifier = Modifier.width(SliderExpandedContentWidth),
        contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        DrawerLayout(
            widthProgress = widthProgress,
            collapsedWidth = SliderWidthCollapsed,
            expandedWidth = SliderExpandedContentWidth,
            isLeftSide = isLeftSide,
            modifier = Modifier
                .clip(RoundedCornerShape(style.dialogCornerRadius))
                .background(backgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .width(SliderExpandedContentWidth)
                    .padding(top = 12.dp, bottom = 10.dp, start = sidePadding, end = sidePadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                VolumeSlidersRow(
                    viewModel = viewModel,
                    showingAppVolumes = showingAppVolumes,
                    hasAppVolumes = hasAppVolumes,
                    isLeftSide = isLeftSide,
                    musicStream = musicStream,
                    otherStreams = otherStreams,
                    appVolumes = appVolumes,
                    sliderHeight = sliderHeight,
                    iconSize = sliderIconSize
                )

                if (isExpanded) {
                    Spacer(Modifier.height(12.dp))
                }

                VolumeFooterRow(
                    isLeftSide = isLeftSide,
                    buttonSize = footerSize,
                    expandButton = {
                        ExpandableCaptionButton(
                            isExpanded = isExpanded,
                            isLeftSide = isLeftSide,
                            captionsAvailable = dialogState.captionsAvailable,
                            captionsEnabled = dialogState.captionsEnabled,
                            viewModel = viewModel,
                            buttonSize = footerSize
                        )
                    },
                    hasAppVolumes = hasAppVolumes,
                    showingAppVolumes = showingAppVolumes,
                    onSystemClick = {
                        if (showingAppVolumes) viewModel.toggleVolumeView()
                    },
                    onAppsClick = {
                        if (!showingAppVolumes) viewModel.toggleVolumeView()
                    },
                    onSeeMoreClick = { viewModel.onSeeMoreClick() }
                )
            }
        }
    }
}

private data class VolumeDialogSizing(
    val ringerSize: Dp,
    val footerSize: Dp,
    val sliderHeight: Dp,
    val sliderIconSize: Dp
)

private fun calculateVolumeDialogSizing(screenHeightDp: Dp): VolumeDialogSizing {
    val ringerSize = VolumeButtonsSize
    val footerSize = 48.dp
    
    val columnSpacing = 12.dp
    val drawerTopPadding = 12.dp
    val drawerBottomPadding = 10.dp
    val spacerBeforeSliders = 12.dp
    val spacerAfterSliders = 8.dp
    val sliderInternalSpacing = 12.dp
    
    val fixedSpace = ringerSize + footerSize + columnSpacing + 
                     drawerTopPadding + drawerBottomPadding + 
                     spacerBeforeSliders + spacerAfterSliders + sliderInternalSpacing
    
    val availableForSliders = screenHeightDp - fixedSpace
    
    val sliderHeight = when {
        availableForSliders >= SliderHeightTarget -> SliderHeightTarget
        else -> availableForSliders.coerceAtLeast(SliderHeightMin)
    }
    
    val sliderIconSize = if (sliderHeight < SliderHeightTarget) {
        (SliderIconSize * (sliderHeight / SliderHeightTarget)).coerceAtLeast(SliderIconMinSize)
    } else {
        SliderIconSize
    }
    
    Log.d("calculateVolumeDialogSizing", 
        "screenHeightDp=$screenHeightDp availableForSliders=$availableForSliders fixedSpace=$fixedSpace sliderIconSize=$sliderIconSize sliderHeight=$sliderHeight")
    
    return VolumeDialogSizing(
        ringerSize = ringerSize,
        footerSize = footerSize,
        sliderHeight = sliderHeight,
        sliderIconSize = sliderIconSize
    )
}
