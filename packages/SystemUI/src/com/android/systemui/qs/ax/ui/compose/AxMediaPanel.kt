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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.ui.compose.AnimatedMediaText
import com.android.systemui.qs.ax.ui.compose.AxMediaColors
import com.android.systemui.qs.ax.ui.compose.AxMediaSizing
import com.android.systemui.qs.ax.ui.compose.AxTileDefaults
import com.android.systemui.qs.ax.ui.compose.CoreMediaAction
import com.android.systemui.qs.ax.ui.compose.ExpandedNavigationAction
import com.android.systemui.qs.ax.ui.compose.MediaAction
import com.android.systemui.qs.ax.ui.compose.MediaAppIcon
import com.android.systemui.qs.ax.ui.compose.MediaArtwork
import com.android.systemui.qs.ax.ui.compose.MediaControls
import com.android.systemui.qs.ax.ui.compose.MediaGuts
import com.android.systemui.qs.ax.ui.compose.MediaOutputChip
import com.android.systemui.qs.ax.ui.compose.MediaSeekBar
import com.android.systemui.qs.ax.ui.compose.MediaTimestamps
import com.android.systemui.qs.ax.ui.compose.mediaActionLimit
import com.android.systemui.qs.ax.ui.compose.playPauseDescription
import com.android.systemui.qs.ax.ui.compose.playPauseIcon
import com.android.systemui.qs.ax.ui.gesture.mediaOverscrollToDismiss
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

private enum class AxMediaLayout {
    OneRow,
    Compact,
    Expanded,
}

@Composable
fun AxMediaPanel(
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    modifier: Modifier = Modifier,
    mediaViewModelFactory: MediaViewModel.Factory? = null,
    showPlaceholder: Boolean = false,
    interactive: Boolean = true,
    allowGuts: Boolean = true,
    surface: AxMediaSurface = AxMediaSurface.CONTROL,
) {
    val showOnLockscreen by viewModel.showOnLockscreen.collectAsStateWithLifecycle()
    if (surface == AxMediaSurface.LOCKSCREEN && !showOnLockscreen) return

    val sessions = viewModel.visibleSessions(surface)
    val currentSession = viewModel.currentSession?.takeIf { viewModel.isSessionVisible(it.key, surface) }
    val lastMediaPackage by viewModel.lastMediaPackage.collectAsStateWithLifecycle()
    val hasVisibleMedia = sessions.isNotEmpty() || showPlaceholder

    LaunchedEffect(viewModel, currentSession?.key) {
        viewModel.synchronizeSession(currentSession?.key)
    }

    if (!hasVisibleMedia) return

    val factory = mediaViewModelFactory
    if (factory == null || (sessions.isEmpty() && showPlaceholder)) {
        AxMediaCard(
            viewModel = viewModel,
            session = currentSession,
            lastMediaPackage = lastMediaPackage,
            span = span,
            interactive = interactive,
            allowGuts = false,
            surface = surface,
            modifier = modifier,
        )
        return
    }

    val shape = axQsControlShape(AxQsControl.MEDIA, span)
    val gesturesEnabled = !viewModel.hasVisibleGuts()
    val carouselScrollingEnabled =
        gesturesEnabled && (surface != AxMediaSurface.LOCKSCREEN || sessions.size > 1)
    var isFalseTouchDetected by remember(surface) { mutableStateOf(false) }
    val behavior =
        remember(viewModel, surface, carouselScrollingEnabled) {
            MediaUiBehavior(
                isCarouselDismissible = false,
                isCarouselScrollingEnabled = carouselScrollingEnabled,
                isSettingsRevealEnabled = false,
                carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                isCarouselScrollFalseTouch =
                    if (surface == AxMediaSurface.LOCKSCREEN) {
                        null
                    } else {
                        {
                            viewModel.isSwipeFalseTouch().also { isFalseTouchDetected = it }
                        }
                    },
            )
        }
    val onDismissed = remember(viewModel, surface) { { viewModel.dismissBySwipe(surface) } }
    val mediaModifier =
        if (surface == AxMediaSurface.SEPARATE_QQS) {
            modifier
                .fillMaxSize()
                .clip(shape)
                .mediaOverscrollToDismiss(
                    enabled = gesturesEnabled,
                    dismissAllowed = !isFalseTouchDetected,
                    onDismissed = onDismissed,
                )
        } else {
            modifier.fillMaxSize()
        }
    Media(
        viewModelFactory = factory,
        presentationStyle = MediaPresentationStyle.Default,
        behavior = behavior,
        onDismissed = onDismissed,
        modifier = mediaModifier,
        cardFilter = { viewModel.isSessionVisible(it.key, surface) },
        carouselShape = shape,
        cardContent = { card, cardModifier ->
            viewModel.sessionForKey(card.key)?.let { session ->
                AxMediaCard(
                    viewModel = viewModel,
                    session = session,
                    lastMediaPackage = lastMediaPackage,
                    span = span,
                    interactive = interactive,
                    allowGuts = allowGuts,
                    surface = surface,
                    hasMultipleSessions = sessions.size > 1,
                    modifier = cardModifier.fillMaxSize(),
                )
            }
        },
        pagerIndicator = { pagerState ->
            PagerDots(
                pagerState = pagerState,
                activeColor = Color(0xffdee0ff),
                nonActiveColor = Color(0xffa7a9ca),
                dotSize = 5.dp,
                spaceSize = 5.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
            )
        },
    )
}

@Composable
private fun AxMediaCard(
    viewModel: AxMediaViewModel,
    session: MediaSessionModel?,
    lastMediaPackage: String?,
    span: AxQsSpan,
    interactive: Boolean,
    allowGuts: Boolean,
    surface: AxMediaSurface,
    hasMultipleSessions: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = axQsControlShape(AxQsControl.MEDIA, span)
    val layout =
        when {
            span.rows == 1 -> AxMediaLayout.OneRow
            span.columns >= 3 -> AxMediaLayout.Expanded
            else -> AxMediaLayout.Compact
        }
    val gutsVisible = allowGuts && session?.let(viewModel::isGutsVisible) == true
    val artwork = session?.background?.takeIf { span.columns > 1 }
    val tileBackground = AxTileDefaults.backgroundColor()
    val tileForeground = MaterialTheme.colorScheme.onSurface
    val colorScheme = session?.colorScheme
    val mediaBackground = colorScheme?.background ?: MaterialTheme.colorScheme.onSurface
    val background by
        animateColorAsState(
            targetValue =
                when {
                    artwork != null && !gutsVisible -> Color.Transparent
                    session != null -> mediaBackground
                    else -> tileBackground
                },
            label = "AxMediaBackground",
        )
    val primary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed
                } else {
                    MaterialTheme.colorScheme.primary
                },
            label = "AxMediaPrimary",
        )
    val onPrimary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimaryFixed
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            label = "AxMediaOnPrimary",
        )
    val foreground by
        animateColorAsState(
            targetValue = if (session != null) Color.White else tileForeground,
            label = "AxMediaForeground",
        )
    val animatedMediaBackground by
        animateColorAsState(targetValue = mediaBackground, label = "AxMediaArtworkOverlay")
    val colors =
        AxMediaColors(
            primary = primary,
            onPrimary = onPrimary,
            background = animatedMediaBackground,
            foreground = foreground,
        )
    val clickLabel =
        if (session != null) {
            stringResource(
                R.string.controls_media_playing_item_description,
                session.title,
                session.subtitle,
                session.appName,
            )
        } else if (lastMediaPackage != null) {
            stringResource(R.string.ax_qs_media_open_last_app)
        } else {
            null
        }
    ExpandableContainer(
        controller = rememberExpandableController(color = { background }, shape = shape),
        modifier = modifier.fillMaxSize().clip(shape),
        onClick = null,
        onClickLabel = null,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().combinedClickable(
                    enabled = interactive && (session != null || lastMediaPackage != null),
                    onClick = {
                        if (!gutsVisible) {
                            if (session != null) {
                                viewModel.openSession(session, expandable)
                            } else {
                                viewModel.openLastMediaApp(expandable)
                            }
                        }
                    },
                    onClickLabel = clickLabel,
                    onLongClick =
                        if (allowGuts) {
                            {
                                if (gutsVisible) {
                                    viewModel.closeGuts()
                                } else if (session != null) {
                                    viewModel.showGuts(session)
                                }
                            }
                        } else {
                            null
                        },
                )
            )
            AnimatedContent(
                targetState = gutsVisible,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "AxMediaGuts",
                modifier = Modifier.fillMaxSize(),
            ) { showGuts ->
                if (showGuts && session != null) {
                    MediaGuts(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        compact = span.columns == 1,
                        surface = surface,
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        if (session != null) {
                            val songKey = "${session.appName}:${session.title}:${session.subtitle}"
                            MediaArtwork(
                                artwork = artwork,
                                songKey = songKey,
                                overlayColor = colors.background,
                            )
                        }
                        AnimatedContent(
                            targetState = layout,
                            transitionSpec = {
                                ((fadeIn(tween(180)) +
                                        scaleIn(tween(220), initialScale = 0.96f)) togetherWith
                                        (fadeOut(tween(120)) +
                                            scaleOut(tween(160), targetScale = 1.04f)))
                                    .using(sizeTransform = null)
                            },
                            contentAlignment = Alignment.Center,
                            contentKey = { it },
                            label = "AxMediaLayout",
                            modifier = Modifier.fillMaxSize(),
                        ) { mediaLayout ->
                            when (mediaLayout) {
                                AxMediaLayout.OneRow ->
                                    OneRowMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        maxActions = mediaActionLimit(span.columns),
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                                AxMediaLayout.Compact ->
                                    CompactMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                                AxMediaLayout.Expanded ->
                                    ExpandedMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        span = span,
                                        colors = colors,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OneRowMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    maxActions: Int,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.ax_qs_media_not_playing)
    val subtitle = session?.subtitle.orEmpty()
    val metadata =
        if (subtitle.isEmpty()) {
            title
        } else {
            stringResource(R.string.ax_qs_media_compact_metadata, title, subtitle)
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight) {
                AxMediaSizing.from(maxWidth, maxHeight)
            }
        val actionLimit = if (maxWidth < 132.dp) 1 else maxActions
        val useRowLayout = maxWidth >= 200.dp || maxHeight < 56.dp
        if (useRowLayout) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxSize().padding(horizontal = sizing.horizontalPadding, vertical = 6.dp),
            ) {
                MediaAppIcon(
                    session = session,
                    size = sizing.headerIconSize,
                    tint = colors.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = actionLimit,
                    actionSize = sizing.actionSize,
                )
            }
        } else {
            val dense = maxHeight < 68.dp
            val bottomPadding = if (hasMultipleSessions) 16.dp else (if (dense) 6.dp else 8.dp)
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier =
                    Modifier.fillMaxSize()
                        .padding(
                            start = sizing.horizontalPadding,
                            end = sizing.horizontalPadding,
                            top = if (dense) 6.dp else 8.dp,
                            bottom = bottomPadding,
                        ),
            ) {
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style =
                        if (dense) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    MediaControls(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        interactive = interactive,
                        maxActions = actionLimit,
                        actionSize = sizing.actionSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.ax_qs_media_not_playing)
    val subtitle = session?.subtitle.orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight) {
                AxMediaSizing.from(maxWidth, maxHeight, AxQsSpan.MediaDefault)
            }
        val availableHeight = maxHeight
        val contentHeight = maxHeight.coerceAtMost(CompactMediaMaxHeight)
        val outputMaxWidth = maxWidth * 0.32f
        val bottomPadding = if (hasMultipleSessions) 18.dp else 10.dp
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .height(contentHeight)
                    .padding(top = 10.dp, bottom = bottomPadding),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = sizing.horizontalPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) {
                    MediaAppIcon(
                        session = session,
                        size = sizing.headerIconSize,
                        tint = colors.primary,
                    )
                }
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = false,
                    compact = sizing.outputChipCompact,
                    modifier = Modifier.widthIn(max = outputMaxWidth),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = sizing.horizontalPadding)) {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                    AnimatedMediaText(
                        text = title,
                        color = colors.foreground,
                        style =
                            if (sizing.outputChipCompact) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                        textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (subtitle.isNotEmpty() && availableHeight >= 112.dp) {
                        AnimatedMediaText(
                            text = subtitle,
                            color = colors.foreground.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = mediaActionLimit(AxQsSpan.MediaDefault.columns),
                    actionSize = sizing.actionSize,
                    spreadCoreActions = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandedMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    colors: AxMediaColors,
    interactive: Boolean,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.ax_qs_media_not_playing)
    val subtitle = session?.subtitle.orEmpty()
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight, span) {
                AxMediaSizing.from(maxWidth, maxHeight, span)
            }
        val bottomPadding = if (hasMultipleSessions) 16.dp else sizing.verticalPadding

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = sizing.horizontalPadding,
                        end = sizing.horizontalPadding,
                        top = sizing.verticalPadding,
                        bottom = bottomPadding,
                    ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaAppIcon(
                    session = session,
                    size = sizing.headerIconSize,
                    tint = colors.primary,
                )
                val outputDeviceName =
                    session?.outputDevice?.name?.takeUnless { it.isBlank() || it == "null" }
                        ?: stringResource(R.string.ax_dynamic_bar_media_output)
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = true,
                    label = outputDeviceName,
                    compact = sizing.outputChipCompact,
                )
            }

            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                AnimatedMediaText(
                    text = title,
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    AnimatedMediaText(
                        text = subtitle,
                        color = colors.foreground.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                MediaSeekBar(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    dense = true,
                    interactive = interactive,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sizing.showTimestamps) {
                    MediaTimestamps(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            val maxActions = mediaActionLimit(span.columns)
            val additionalActions =
                session?.additionalActions.orEmpty().take((maxActions - 3).coerceAtLeast(0))
            val leftSecondary = additionalActions.firstOrNull()
            val rightSecondary = additionalActions.drop(1).firstOrNull()
            val totalActionCount =
                if (showCoreActions) 3 + additionalActions.size else additionalActions.size
            val actionSpacing = if (totalActionCount > 3) 14.dp else sizing.actionSpacing

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                with(Icons.Filled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leftSecondary != null && showCoreActions) {
                            MediaAction(
                                action = leftSecondary,
                                viewModel = viewModel,
                                width = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                            Spacer(Modifier.width(actionSpacing))
                        }

                        if (showCoreActions) {
                            ExpandedNavigationAction(
                                action = session?.leftAction,
                                placeholderDescription = R.string.controls_media_button_prev,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                imageVector = SkipPrevious,
                            )

                            Spacer(Modifier.width(actionSpacing))

                            CoreMediaAction(
                                action = session?.playPauseAction,
                                imageVector = playPauseIcon(session),
                                descriptionRes = playPauseDescription(session),
                                animatedIconRes = R.drawable.ic_media_play_button,
                                animatedIconAtEnd = session?.state == MediaSessionState.Playing,
                                viewModel = viewModel,
                                width = sizing.heroActionSize,
                                height = sizing.heroActionSize,
                                iconSize = sizing.heroIconSize,
                                tint = colors.foreground,
                                background = Color.Transparent,
                                interactive = interactive,
                            )

                            Spacer(Modifier.width(actionSpacing))

                            ExpandedNavigationAction(
                                action = session?.rightAction,
                                placeholderDescription = R.string.controls_media_button_next,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                imageVector = SkipNext,
                            )
                        } else if (additionalActions.isNotEmpty()) {
                            additionalActions.forEachIndexed { index, action ->
                                if (index > 0) Spacer(Modifier.width(actionSpacing))
                                MediaAction(
                                    action = action,
                                    viewModel = viewModel,
                                    width = sizing.actionSize,
                                    iconSize = sizing.actionIconSize,
                                    tint = colors.foreground,
                                    interactive = interactive,
                                )
                            }
                        }

                        if (rightSecondary != null && showCoreActions) {
                            Spacer(Modifier.width(actionSpacing))
                            MediaAction(
                                action = rightSecondary,
                                viewModel = viewModel,
                                width = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CompactMediaMaxHeight = 220.dp
