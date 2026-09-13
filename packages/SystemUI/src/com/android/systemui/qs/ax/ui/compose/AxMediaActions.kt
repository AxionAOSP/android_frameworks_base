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

package com.android.systemui.qs.ax.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.media.remedia.domain.model.MediaActionModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.ax.ui.compose.AxMediaColors
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

@Composable
internal fun MediaControls(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    actionSize: Dp,
    maxActions: Int = 3,
    spreadCoreActions: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val spacing = dimensionResource(R.dimen.qs_media_action_spacing)
    val iconSize =
        when {
            actionSize < 32.dp -> 18.dp
            actionSize < 40.dp -> 22.dp
            else -> 26.dp
        }
    val navigationIconSize =
        minOf(
            when {
                actionSize < 32.dp -> 18.dp
                actionSize < 40.dp -> 22.dp
                else -> 24.dp
            },
            MediaNavigationIconSize,
        )
    val showSideActions = maxActions >= 3
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        with(Icons.Filled) {
            Row(
                horizontalArrangement =
                    if (spreadCoreActions) {
                        Arrangement.SpaceEvenly
                    } else {
                        Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                    },
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
            ) {
                if (session != null) {
                    val coreSlots = if (showSideActions) 3 else 1
                    val additionalActions =
                        session.additionalActions.take((maxActions - coreSlots).coerceAtLeast(0))
                    if (showSideActions) {
                        additionalActions.firstOrNull()?.let { action ->
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = actionSize,
                                iconSize = iconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                    if (showSideActions) {
                        CoreMediaAction(
                            action = session.leftAction,
                            imageVector = SkipPrevious,
                            descriptionRes = R.string.controls_media_button_prev,
                            viewModel = viewModel,
                            width = actionSize,
                            iconSize = navigationIconSize,
                            tint = colors.foreground,
                            interactive = interactive,
                        )
                    }
                    CoreMediaAction(
                        action = session.playPauseAction,
                        imageVector = playPauseIcon(session),
                        descriptionRes = playPauseDescription(session),
                        viewModel = viewModel,
                        width = actionSize,
                        iconSize = iconSize,
                        tint = colors.foreground,
                        background = Color.Transparent,
                        interactive = interactive,
                    )
                    if (showSideActions) {
                        CoreMediaAction(
                            action = session.rightAction,
                            imageVector = SkipNext,
                            descriptionRes = R.string.controls_media_button_next,
                            viewModel = viewModel,
                            width = actionSize,
                            iconSize = navigationIconSize,
                            tint = colors.foreground,
                            interactive = interactive,
                        )
                    }
                    if (showSideActions) {
                        additionalActions.drop(1).forEach { action ->
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = actionSize,
                                iconSize = iconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                } else {
                    if (showSideActions) {
                        PlaceholderMediaAction(
                            imageVector = SkipPrevious,
                            descriptionRes = R.string.controls_media_button_prev,
                            width = actionSize,
                            iconSize = navigationIconSize,
                            tint = colors.foreground,
                        )
                    }
                    PlaceholderMediaAction(
                        imageVector = PlayArrow,
                        descriptionRes = R.string.controls_media_button_play,
                        width = actionSize,
                        iconSize = iconSize,
                        tint = colors.foreground,
                        background = Color.Transparent,
                    )
                    if (showSideActions) {
                        PlaceholderMediaAction(
                            imageVector = SkipNext,
                            descriptionRes = R.string.controls_media_button_next,
                            width = actionSize,
                            iconSize = navigationIconSize,
                            tint = colors.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CoreMediaAction(
    action: MediaActionModel?,
    imageVector: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
    @StringRes descriptionRes: Int,
    @DrawableRes animatedIconRes: Int? = null,
    animatedIconAtEnd: Boolean = false,
    viewModel: AxMediaViewModel,
    width: Dp,
    height: Dp = width,
    iconSize: Dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
    interactive: Boolean,
) {
    when (action) {
        is MediaActionModel.Action ->
            MediaAction(
                action = action,
                viewModel = viewModel,
                width = width,
                height = height,
                iconSize = iconSize,
                tint = tint,
                background = background,
                shape = shape,
                interactive = interactive,
                imageVector = imageVector,
                animatedIconRes = animatedIconRes,
                animatedIconAtEnd = animatedIconAtEnd,
                contentDescription = stringResource(descriptionRes),
            )
        MediaActionModel.ReserveSpace -> Spacer(Modifier.size(width = width, height = height))
        MediaActionModel.None,
        null ->
            PlaceholderMediaAction(
                iconRes = iconRes,
                imageVector = imageVector,
                descriptionRes = descriptionRes,
                width = width,
                height = height,
                iconSize = iconSize,
                tint = tint,
                background = background,
                shape = shape,
            )
    }
}

@Composable
internal fun PlaceholderMediaAction(
    @DrawableRes iconRes: Int? = null,
    imageVector: ImageVector? = null,
    @StringRes descriptionRes: Int,
    width: Dp,
    height: Dp = width,
    iconSize: Dp = 20.dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
) {
    val description = stringResource(descriptionRes)
    val buttonWidth by
        animateDpAsState(
            targetValue = width,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaPlaceholderWidth",
        )
    val buttonHeight by
        animateDpAsState(
            targetValue = height,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaPlaceholderHeight",
        )
    val buttonBackground by
        animateColorAsState(targetValue = background, label = "AxMediaPlaceholderBackground")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(CircleShape)
                .semantics {
                    contentDescription = description
                    disabled()
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(width = buttonWidth, height = buttonHeight)
                    .clip(shape)
                    .background(buttonBackground),
        ) {
            if (imageVector != null) {
                MaterialIcon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            } else if (iconRes != null) {
                Icon(
                    icon = IconModel.Resource(iconRes, null),
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
internal fun ExpandedNavigationAction(
    action: MediaActionModel?,
    @StringRes placeholderDescription: Int,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    size: Dp,
    iconSize: Dp,
    background: Color = Color.Transparent,
    imageVector: ImageVector? = null,
    @DrawableRes iconRes: Int? = null,
) {
    CoreMediaAction(
        action = action,
        imageVector = imageVector,
        iconRes = iconRes,
        descriptionRes = placeholderDescription,
        viewModel = viewModel,
        width = size,
        height = size,
        iconSize = iconSize,
        tint = colors.foreground,
        background = background,
        interactive = interactive,
    )
}

@Composable
internal fun MediaAction(
    action: MediaActionModel,
    viewModel: AxMediaViewModel,
    width: Dp,
    height: Dp = width,
    iconSize: Dp = 20.dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
    interactive: Boolean,
    imageVector: ImageVector? = null,
    @DrawableRes animatedIconRes: Int? = null,
    animatedIconAtEnd: Boolean = false,
    contentDescription: String? = null,
) {
    val buttonWidth by
        animateDpAsState(
            targetValue = width,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaActionWidth",
        )
    val buttonHeight by
        animateDpAsState(
            targetValue = height,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaActionHeight",
        )
    val buttonBackground by
        animateColorAsState(targetValue = background, label = "AxMediaActionBackground")
    when (action) {
        is MediaActionModel.Action -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 24.dp),
                            enabled = interactive && action.onClick != null,
                        ) {
                            viewModel.runAction(action)
                        },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(width = buttonWidth, height = buttonHeight)
                            .clip(shape)
                            .background(buttonBackground),
                ) {
                    if (animatedIconRes != null) {
                        val painter =
                            rememberAnimatedVectorPainter(
                                animatedImageVector =
                                    AnimatedImageVector.animatedVectorResource(animatedIconRes),
                                atEnd = animatedIconAtEnd,
                            )
                        MaterialIcon(
                            painter = painter,
                            contentDescription = contentDescription,
                            tint = tint,
                            modifier = Modifier.size(iconSize),
                        )
                    } else if (imageVector != null) {
                        AnimatedContent(
                            targetState = imageVector,
                            transitionSpec = {
                                (fadeIn(tween(180)) +
                                    scaleIn(tween(220), initialScale = 0.72f)) togetherWith
                                    (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 1.18f))
                            },
                            contentAlignment = Alignment.Center,
                            label = "AxMediaActionIcon",
                        ) { vector ->
                            MaterialIcon(
                                imageVector = vector,
                                contentDescription = contentDescription,
                                tint = tint,
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    } else {
                        Icon(icon = action.icon, tint = tint, modifier = Modifier.size(iconSize))
                    }
                }
            }
        }
        MediaActionModel.None -> Unit
        MediaActionModel.ReserveSpace ->
            Spacer(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).size(width = buttonWidth, height = buttonHeight))
    }
}

internal fun playPauseIcon(session: MediaSessionModel?): ImageVector =
    with(Icons.Filled) {
        if (session?.state == MediaSessionState.Playing) Pause else PlayArrow
    }

@StringRes
internal fun playPauseDescription(session: MediaSessionModel?): Int =
    if (session?.state == MediaSessionState.Playing) {
        R.string.controls_media_button_pause
    } else {
        R.string.controls_media_button_play
    }

private val MediaNavigationIconSize = 24.dp
