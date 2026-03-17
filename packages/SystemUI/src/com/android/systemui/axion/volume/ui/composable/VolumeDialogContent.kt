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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axion.volume.ui.composable

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.android.systemui.axion.volume.ui.viewmodel.ExpansionState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.domain.model.AxionStreamInfo
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogUiState
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.res.R

private val DialogShape = RoundedCornerShape(DialogCornerRadius)

@Composable
fun AxionVolumeDialogContent(
    viewModel: AxionVolumeDialogViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVisible = uiState.isVisible
    val isLeft = uiState.isLeftSide
    val isExpanded = uiState.isExpanded

    val motionScheme = MaterialTheme.motionScheme
    val visibilityAnimatable = remember { Animatable(0f) }
    val visibilityProgress = visibilityAnimatable.value
    var dismissFromExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            dismissFromExpanded = false
            visibilityAnimatable.snapTo(0f)
            viewModel.collapseIfExpanded()
            visibilityAnimatable.animateTo(1f, tween(200))
        } else {
            dismissFromExpanded = viewModel.expansionState == ExpansionState.EXPANDED
            runCatching {
                visibilityAnimatable.animateTo(0f, tween(200))
            }
            viewModel.resetState()
            viewModel.onDismissAnimationEnd()
        }
    }
    val overscrollOffset by viewModel.overscrollOffset.collectAsStateWithLifecycle()
    val animatedOverscroll by animateFloatAsState(
        targetValue = overscrollOffset,
        animationSpec = motionScheme.fastSpatialSpec(),
        label = "overscroll"
    )

    val view = LocalView.current
    val currentView by rememberUpdatedState(view)
    LaunchedEffect(Unit) {
        viewModel.volumeKeyHapticTrigger.collect {
            currentView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val sliderItems by viewModel.sliderItems.collectAsStateWithLifecycle()
    val sliderCount by viewModel.sliderCount.collectAsStateWithLifecycle()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val maxPanelWidth = screenWidthDp - WindowPadding * 2
    val expandedWidth = minOf(calculateExpandedPanelWidth(sliderCount), maxPanelWidth)
    val dialogState = uiState.dialogState
    val config = LocalConfiguration.current
    val isPhonePortrait = screenWidthDp < 600.dp && config.screenHeightDp > config.screenWidthDp
    val edgeAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
    val surfaceBright = MaterialTheme.colorScheme.surfaceBright
    val panelModifier = Modifier.clip(DialogShape).background(surfaceBright)

    val expandedContent: @Composable () -> Unit = {
        ExpandedPanelContent(
            viewModel = viewModel,
            dialogState = dialogState,
            sliderItems = sliderItems,
            sliderCount = sliderCount,
            expandedWidth = expandedWidth,
            modifier = panelModifier
        )
    }

    val collapsedContent: @Composable () -> Unit = {
        CollapsedPanelContent(
            uiState = uiState,
            viewModel = viewModel,
            modifier = panelModifier
        )
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                alpha = visibilityProgress
                if (dismissFromExpanded) {
                    val scale = 0.92f + 0.08f * visibilityProgress
                    scaleX = scale
                    scaleY = scale
                } else {
                    val dir = if (isLeft) -1f else 1f
                    translationX = dir * 24f * (1f - visibilityProgress)
                }
                translationY = animatedOverscroll
            },
        contentAlignment = edgeAlignment
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn(motionScheme.fastEffectsSpec()) +
                    scaleIn(motionScheme.fastSpatialSpec(), initialScale = 0.92f))
                    .togetherWith(
                        fadeOut(motionScheme.fastEffectsSpec()) +
                            scaleOut(motionScheme.fastSpatialSpec(), targetScale = 0.92f)
                    ).using(SizeTransform(clip = false))
            },
            contentAlignment = edgeAlignment,
            label = "panelSwitch"
        ) { expanded ->
            if (expanded) {
                if (isPhonePortrait) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        expandedContent()
                    }
                } else {
                    expandedContent()
                }
            } else {
                collapsedContent()
            }
        }
    }
}


@Composable
private fun CollapsedPanelContent(
    uiState: AxionVolumeDialogUiState,
    viewModel: AxionVolumeDialogViewModel,
    modifier: Modifier = Modifier
) {
    val isLeftSide = uiState.isLeftSide
    val activeStream = uiState.dialogState.activeStream
    val streamModel = uiState.dialogState.volumeStreams.find { it.streamType == activeStream }
        ?: uiState.dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }

    val ringerMode = uiState.dialogState.ringerMode
    val supportedModes = uiState.dialogState.supportedRingerModes
    var drawerOpen by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .requiredWidth(SliderWidthCollapsed)
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(vertical = ContentSpacingSmall)
    ) {
        AnimatedVisibility(
            visible = drawerOpen,
            enter = expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
            ) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
            ) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ContentSpacingSmall),
                modifier = Modifier.padding(bottom = ContentSpacingSmall)
            ) {
                supportedModes.filter { it != ringerMode }.forEach { mode ->
                    RingerDrawerItem(
                        mode = mode,
                        onClick = {
                            viewModel.rescheduleTimeout()
                            viewModel.setRingerMode(mode)
                            drawerOpen = false
                        }
                    )
                }
            }
        }

        RingerButton(
            ringerMode = ringerMode,
            onClick = {
                viewModel.rescheduleTimeout()
                drawerOpen = !drawerOpen
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (streamModel != null) {
            SliderColumn(
                stream = streamModel,
                viewModel = viewModel,
                trackHeight = SliderTrackHeight,
                iconSize = SliderIconSize,
                showPercentage = false
            )
        }

        Box(
            modifier = Modifier
                .width(ExpandButtonWidth)
                .height(ExpandButtonHeight)
                .clickable {
                    viewModel.rescheduleTimeout()
                    viewModel.toggleExpanded()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "Expand",
                modifier = Modifier.size(ExpandIconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RingerDrawerItem(
    mode: AxionRingerMode,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(RingerDrawerItemSize)
            .clip(RoundedCornerShape(RingerDrawerItemCornerRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(mode.iconRes),
            contentDescription = mode.label,
            modifier = Modifier.size(RingerDrawerIconSize),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ExpandedPanelContent(
    viewModel: AxionVolumeDialogViewModel,
    dialogState: AxionVolumeDialogState,
    sliderItems: List<VolumeSliderItem>,
    sliderCount: Int,
    expandedWidth: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .requiredWidth(expandedWidth)
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(PanelHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ContentSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val captionsEnabled = dialogState.captionsEnabled
            val btnModifier = Modifier.size(VolumeButtonsSizeExpanded)
            val odiModifier = btnModifier.clickable(
                        onClick = {
                            viewModel.rescheduleTimeout()
                            viewModel.toggleCaptions()
                        },
                        interactionSource = null,
                        indication = null,
                    )
            val settingsModifier = btnModifier.clickable(
                        onClick = {
                            viewModel.rescheduleTimeout()
                            viewModel.onSeeMoreClick()
                        },
                        interactionSource = null,
                        indication = null,
                    )
            Box(
                modifier = odiModifier,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (captionsEnabled) R.drawable.ic_volume_odi_captions
                        else R.drawable.ic_volume_odi_captions_disabled
                    ),
                    contentDescription = "Captions",
                    modifier = Modifier.size(HeaderIconSize),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            val activeAppPkg = dialogState.activeAppPackageName
            val activeAppLabel = activeAppPkg?.let { pkg ->
                dialogState.appVolumes.find { it.packageName == pkg }?.label
            }
            val title = activeAppLabel ?: run {
                val info = AxionStreamInfo.fromStreamType(dialogState.activeStream)
                if (info != AxionStreamInfo.UNKNOWN) info.label else "Volume"
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = TitlePaddingHorizontal)
                    .basicMarquee()
            )

            Box(
                modifier = settingsModifier,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(HeaderIconSize),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        VolumeSlidersRow(
            viewModel = viewModel,
            sliderItems = sliderItems,
            trackHeight = SliderTrackHeight,
            iconSize = SliderIconSize,
            showPercentage = true,
        )
    }
}

