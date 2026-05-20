/*
 * Copyright (C) 2025-2026 Axion OS
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.axion.blur.AxBlurLifecycle
import com.android.axion.blur.AxBlurSurface
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogUiState
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.res.R
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(DialogCornerRadius)

private object VolumeScenes {
    val Collapsed = SceneKey("ax_vol_collapsed")
    val Expanded = SceneKey("ax_vol_expanded")
}

private object VolumeElements {
    val PanelCard = ElementKey("ax_vol_panel_card", placeAllCopies = true)
    val PanelBackground = ElementKey("ax_vol_panel_background")
    val RingerControl = ElementKey("ax_vol_ringer_control")
    val CollapsedExpandButton = ElementKey("ax_vol_collapsed_expand_button")
    val ExpandedSeeMore = ElementKey("ax_vol_expanded_see_more")
    val StreamSliders =
        object : ElementMatcher {
            override fun matches(key: ElementKey, content: ContentKey): Boolean {
                return key.debugName.startsWith(StreamSliderElementPrefix)
            }
        }
}

private val VolumeTransitions = transitions {
    from(VolumeScenes.Collapsed, to = VolumeScenes.Expanded) {
        volumePanelMorphTransition()
    }
}

private const val StreamSliderElementPrefix = "ax_vol_stream_slider_"

private fun volumeStreamSliderElementKey(streamType: Int): ElementKey =
    ElementKey("$StreamSliderElementPrefix$streamType", streamType)

private fun TransitionBuilder.volumePanelMorphTransition() {
    spec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
    sharedElement(VolumeElements.PanelCard)
    sharedElement(VolumeElements.PanelBackground)
    sharedElement(VolumeElements.RingerControl)
    sharedElement(VolumeElements.StreamSliders)
    anchoredTranslate(VolumeElements.CollapsedExpandButton, VolumeElements.PanelCard)
    anchoredTranslate(VolumeElements.ExpandedSeeMore, VolumeElements.PanelCard)
    fractionRange(end = 0.22f) {
        fade(VolumeElements.CollapsedExpandButton)
    }
    fractionRange(start = 0.58f) {
        fade(VolumeElements.ExpandedSeeMore)
    }
}

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

    LaunchedEffect(isVisible) {
        if (isVisible) {
            visibilityAnimatable.snapTo(0f)
            viewModel.collapseIfExpanded()
            visibilityAnimatable.animateTo(1f, tween(200))
        } else {
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
    val currStreamCount by viewModel.currStreamCount.collectAsStateWithLifecycle()
    val edgeAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd

    val currentScene = if (isExpanded) VolumeScenes.Expanded else VolumeScenes.Collapsed
    val stlState = rememberMutableSceneTransitionLayoutState(currentScene, VolumeTransitions)

    LaunchedEffect(isExpanded) {
        val target = if (isExpanded) VolumeScenes.Expanded else VolumeScenes.Collapsed
        if (stlState.transitionState.currentScene != target) {
            stlState.setTargetScene(target, animationScope = this)
        }
    }

    Box(
        modifier = Modifier
            .offset {
                val dir = if (isLeft) -1f else 1f
                IntOffset(
                    (dir * 24f * (1f - visibilityProgress)).roundToInt(),
                    animatedOverscroll.roundToInt(),
                )
            }
            .graphicsLayer {
                alpha = visibilityProgress
            },
        contentAlignment = edgeAlignment
    ) {
        AxBlurLifecycle(enabled = isVisible) {
            SceneTransitionLayout(state = stlState) {
                scene(VolumeScenes.Collapsed) {
                    CollapsedPanelContent(viewModel = viewModel, uiState = uiState, isLeft = isLeft)
                }
                scene(VolumeScenes.Expanded) {
                    ExpandedPanelContent(
                        viewModel = viewModel,
                        uiState = uiState,
                        sliderItems = sliderItems,
                        currStreamCount = currStreamCount
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentScope.VolumePanelCard(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .element(VolumeElements.PanelCard)
            .clip(CardShape),
        contentAlignment = Alignment.TopCenter,
    ) {
        AxBlurSurface(
            modifier = Modifier
                .matchParentSize()
                .element(VolumeElements.PanelBackground),
            shape = CardShape,
            cornerRadius = DialogCornerRadius,
        ) {}
        content()
    }
}

@Composable
private fun ContentScope.CollapsedPanelContent(
    viewModel: AxionVolumeDialogViewModel,
    uiState: AxionVolumeDialogUiState,
    isLeft: Boolean,
) {
    val dialogState = uiState.dialogState
    val ringerMode = dialogState.ringerMode
    val activeStream = dialogState.activeStream
    val streamModel = dialogState.volumeStreams.find { it.streamType == activeStream }
        ?: dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
    val appVolumes = dialogState.appVolumes

    val cardAnimDir = if (isLeft) -1f else 1f
    var isCardVisible by remember { mutableStateOf(appVolumes.isNotEmpty()) }
    var lastAppVolumes by remember { mutableStateOf(appVolumes) }
    val appCardEntrance = remember { Animatable(0f) }

    LaunchedEffect(appVolumes) {
        if (appVolumes.isNotEmpty()) lastAppVolumes = appVolumes
    }
    LaunchedEffect(appVolumes.isNotEmpty()) {
        if (appVolumes.isNotEmpty()) {
            isCardVisible = true
            appCardEntrance.snapTo(0f)
            appCardEntrance.animateTo(1f, tween(durationMillis = 320))
        } else {
            appCardEntrance.animateTo(0f, tween(durationMillis = 220))
            isCardVisible = false
        }
    }

    val mainContent: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .requiredWidth(CollapsedPanelWidth)
                .pointerInput(Unit) { detectTapGestures {} }
        ) {
            VolumePanelCard(
                modifier = Modifier.requiredWidth(CollapsedPanelWidth),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = ContentSpacingSmall)
                ) {
                    RingerCircleButton(
                        ringerMode = ringerMode,
                        onClick = {
                            viewModel.rescheduleTimeout()
                            viewModel.cycleRingerMode()
                        },
                        modifier = Modifier.element(VolumeElements.RingerControl)
                    )

                    if (streamModel != null) {
                        SliderColumn(
                            stream = streamModel,
                            viewModel = viewModel,
                            touchWidth = CollapsedPanelWidth,
                            showPercentage = true,
                            modifier = Modifier.element(
                                volumeStreamSliderElementKey(streamModel.streamType)
                            )
                        )
                    } else {
                        Spacer(
                            modifier = Modifier.height(
                                SliderTopPadding + SliderTrackHeight
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(IconToExpandSpacing))

                    Box(
                        modifier = Modifier
                            .element(VolumeElements.CollapsedExpandButton)
                            .size(SliderIconContainerSize)
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
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription =
                                stringResource(R.string.volume_panel_expanded_sliders),
                            modifier = Modifier
                                .size(HeaderIconSize)
                                .graphicsLayer { scaleX = if (isLeft) 1f else -1f },
                            tint = volumePanelSecondaryContentColor()
                        )
                    }
                }
            }
        }
    }

    if (isCardVisible) {
        val appCards: @Composable () -> Unit = {
            lastAppVolumes.take(MaxVisibleSliders).forEachIndexed { index, app ->
                key(app.packageName) {
                    val sliderEntrance = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        sliderEntrance.animateTo(
                            1f,
                            tween(durationMillis = 240, delayMillis = index * 50)
                        )
                    }
                    val cardAlpha = appCardEntrance.value * sliderEntrance.value
                    val appCardTopPadding = ContentSpacingSmall
                    val appCardBottomPadding = ContentSpacingSmall + TrackToIconSpacing / 2f
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cardAnimDir * 24f * (1f - appCardEntrance.value))
                                        .roundToInt(),
                                    RingerCircleSize.roundToPx() -
                                        appCardTopPadding.roundToPx() +
                                        (16f * (1f - sliderEntrance.value)).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                alpha = cardAlpha
                            }
                    ) {
                        AxBlurSurface(
                            modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
                            shape = CardShape,
                            cornerRadius = DialogCornerRadius,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(
                                    top = appCardTopPadding,
                                    bottom = appCardBottomPadding
                                )
                            ) {
                                AppVolumeSlider(
                                    appVolume = app,
                                    viewModel = viewModel,
                                    touchWidth = CollapsedPanelWidth,
                                    showPercentage = true
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(RingerToSliderGap),
            verticalAlignment = Alignment.Top
        ) {
            if (isLeft) {
                mainContent()
                appCards()
            } else {
                appCards()
                mainContent()
            }
        }
    } else {
        mainContent()
    }
}

@Composable
private fun ContentScope.ExpandedPanelContent(
    viewModel: AxionVolumeDialogViewModel,
    uiState: AxionVolumeDialogUiState,
    sliderItems: List<VolumeSliderItem>,
    currStreamCount: Int,
) {
    val dialogState = uiState.dialogState
    val ringerMode = dialogState.ringerMode
    val supportedModes = dialogState.supportedRingerModes
    val streamItems = sliderItems.filterIsInstance<VolumeSliderItem.Stream>()
    val streamCount = currStreamCount.coerceIn(1, MaxVisibleSliders)
    val panelWidth = (8 + 64 * streamCount).dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .requiredWidth(panelWidth)
            .pointerInput(Unit) { detectTapGestures {} }
    ) {
        VolumePanelCard(
            modifier = Modifier.requiredWidth(panelWidth),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(
                    top = ContentSpacingSmall,
                    bottom = ContentSpacingSmall,
                )
            ) {
                RingerRow(
                    ringerMode = ringerMode,
                    supportedModes = supportedModes,
                    panelWidth = panelWidth - SliderRowHorizontalPadding * 2,
                    onModeSelected = { mode ->
                        viewModel.rescheduleTimeout()
                        viewModel.setRingerMode(mode)
                    },
                    modifier = Modifier.element(VolumeElements.RingerControl)
                )

                VolumeSlidersRow(
                    viewModel = viewModel,
                    sliderItems = streamItems,
                    showPercentage = true,
                    sliderCount = streamCount,
                    streamSliderModifier = { streamType ->
                        Modifier.element(volumeStreamSliderElementKey(streamType))
                    }
                )

                Spacer(modifier = Modifier.height(IconToExpandSpacing))

                Box(
                    modifier = Modifier
                        .element(VolumeElements.ExpandedSeeMore)
                        .fillMaxWidth()
                        .height(SeeMoreHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.rescheduleTimeout()
                            viewModel.onSeeMoreClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.see_more_title).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
