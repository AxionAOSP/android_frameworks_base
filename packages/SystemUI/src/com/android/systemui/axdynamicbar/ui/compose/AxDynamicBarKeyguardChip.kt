@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.animation.Expandable as SystemUiExpandable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import android.content.Context
import android.graphics.drawable.Drawable
import com.android.axion.blur.axBlurBackground
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsReturnAnimations
import kotlinx.coroutines.delay
import java.util.Calendar

private val NowBarHeight = 48.dp
private val NowBarShape = ShapeChip
private val NowBarIconContainerSize = 34.dp
private val NowBarIconShape = CircleShape
private val ActionSize = 28.dp
private val PlayActionSize = 32.dp
private val ActionIconSize = 16.dp
private val PlayIconSize = 18.dp
private val BatteryIconSize = 22.dp

private val NowBarRimWidth = 1.dp

// Pill content is always white, so the glass keeps a dark base in both light and dark themes.
// The ax_blur default surface is light (accent2_50) in light theme and would kill contrast.
private val NowBarGlassTint = Color.Black.copy(alpha = 0.32f)
private val NowBarSolidTint = Color(0xFF16161A).copy(alpha = 0.82f)
private val NowBarSheen = Color.White.copy(alpha = 0.07f)
private val NowBarRimTop = Color.White.copy(alpha = 0.24f)
private val NowBarRimBottom = Color.White.copy(alpha = 0.05f)
private const val NowBarAccentWashAlpha = 0.10f

// The pill Row's animateContentSize owns width changes; nested AnimatedContents must not animate size too.
private val NowBarSnapSize = SizeTransform(clip = false) { _, _ -> snap() }

@Composable
private fun rememberChargingParts(batteryString: String): List<String> {
    return remember(batteryString) {
        batteryString.split("\n", limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Glass in the style of One UI's Now Bar.
 *
 * The backdrop comes from ax_blur's [axBlurBackground], so it follows the system blur toggle and
 * radius exactly like the keyguard shortcuts, and falls back to a solid tint when blur is off.
 * Compose layer alpha never reaches the SurfaceFlinger region, so the visibility alpha is passed
 * in explicitly. It is read here, in its own scope, so a fade does not recompose the whole pill.
 * An accent wash, top sheen and light-to-dark rim are drawn over the blur.
 */
@Composable
private fun NowBarGlass(
    accent: Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .axBlurBackground(
                enabled = true,
                fallbackColor = NowBarSolidTint,
                tintColor = NowBarGlassTint,
                cornerRadius = NowBarHeight / 2,
                alpha = alpha(),
            )
            .drawWithCache {
                val corner = CornerRadius(size.height / 2f)
                val rimPx = NowBarRimWidth.toPx()
                val rimInset = rimPx / 2f
                val rimCorner = CornerRadius((size.height - rimPx) / 2f)
                val rimBrush = Brush.verticalGradient(listOf(NowBarRimTop, NowBarRimBottom))
                val sheenBrush = Brush.verticalGradient(0f to NowBarSheen, 0.6f to Color.Transparent)
                onDrawBehind {
                    if (accent.isSpecified && accent.alpha > 0f) {
                        drawRoundRect(
                            color = accent.copy(alpha = NowBarAccentWashAlpha),
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(brush = sheenBrush, cornerRadius = corner)
                    drawRoundRect(
                        brush = rimBrush,
                        topLeft = Offset(rimInset, rimInset),
                        size = Size(size.width - rimPx, size.height - rimPx),
                        cornerRadius = rimCorner,
                        style = Stroke(width = rimPx),
                    )
                }
            }
    )
}

private class LastNonNull<T : Any> {
    var value: T? = null
}

@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNull<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private sealed interface KeyguardChipDisplayState {
    data class Event(
        val event: IslandEvent,
        val eventCount: Int,
        val pinnedIndex: Int,
    ) : KeyguardChipDisplayState

    data class Battery(
        val info: KeyguardBatteryInfo,
        val batteryString: String,
    ) : KeyguardChipDisplayState
}

@Composable
fun AxDynamicBarKeyguardChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
) {
    AxDynamicBarTheme {
        AxDynamicBarKeyguardChipContent(viewModel, modifier)
    }
}

@Composable
private fun AxDynamicBarKeyguardChipContent(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val keyguardBatteryChipMode by viewModel.keyguardBatteryChipMode.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val batteryString by viewModel.batteryString.collectAsStateWithLifecycle()

    val motionScheme = MaterialTheme.motionScheme
    val expandedVisibleState = remember { MutableTransitionState(false) }
    val targetExpanded = isKeyguardExpanded && state != null
    LaunchedEffect(targetExpanded) {
        expandedVisibleState.targetState = targetExpanded
    }
    val isExpandedContentVisible =
        expandedVisibleState.currentState || targetExpanded
    val transitionControllerFactory =
        (state?.event as? IslandEvent.AospChip)?.active?.transitionManager?.controllerFactory
    val expandableController =
        rememberExpandableController(
            color = Color.Transparent,
            shape = NowBarShape,
            transitionControllerFactory = transitionControllerFactory,
        )

    Box(modifier = if (isExpandedContentVisible) modifier.fillMaxSize() else modifier) {
        LaunchedEffect(expandedVisibleState.isIdle, expandedVisibleState.currentState) {
            if (expandedVisibleState.isIdle && !expandedVisibleState.currentState) {
                viewModel.keyguardExpansion.notifyCollapseSettled()
            }
        }
        AnimatedVisibility(
            visibleState = expandedVisibleState,
            enter = fadeIn(motionScheme.defaultEffectsSpec()),
            exit = fadeOut(tween(durationMillis = 250)),
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
        ) {
            state?.let {
                KeyguardExpandedContent(
                    event = it.event,
                    allEvents = it.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }

        val chipState = state
        val currentDisplayState: KeyguardChipDisplayState? = when {
            chipState != null -> KeyguardChipDisplayState.Event(
                event = chipState.event,
                eventCount = chipState.eventCount,
                pinnedIndex = chipState.pinnedIndex,
            )
            keyguardBatteryChipMode > 0 && (keyguardBatteryChipMode != 1 || batteryInfo.isCharging) ->
                KeyguardChipDisplayState.Battery(batteryInfo, batteryString)
            else -> null
        }
        // Keep the last state composed while the exit animation runs, otherwise the pill pops out.
        val displayState = rememberLastNonNull(currentDisplayState)

        AnimatedVisibility(
            visible = isOnKeyguard && isEnabled && isKeyguardEnabled && !isKeyguardExpanded && currentDisplayState != null,
            enter = scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(durationMillis = 200, delayMillis = 300),
            ),
            exit = scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(viewModel, touchSlop) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startX = down.position.x
                        val startY = down.position.y
                        var dragging = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging && abs(totalDx) > touchSlop) {
                                    change.consume()
                                    if (totalDx > 0) viewModel.cyclePrev()
                                    else viewModel.cycleNext()
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            if (!dragging && abs(dx) > touchSlop) {
                                if (abs(dx) > abs(dy)) {
                                    dragging = true
                                } else {
                                    break
                                }
                            }
                            if (dragging) {
                                totalDx = dx
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            // Fade here instead of fadeIn/fadeOut so the SurfaceFlinger blur region, which sits
            // outside the Compose layer tree, can follow the exact same alpha.
            val visibilityAlpha by transition.animateFloat(
                transitionSpec = {
                    if (targetState == EnterExitState.Visible) {
                        tween(durationMillis = 200, delayMillis = 300)
                    } else {
                        motionScheme.fastEffectsSpec()
                    }
                },
                label = "kg_chip_alpha",
            ) { if (it == EnterExitState.Visible) 1f else 0f }

            displayState?.let { ds ->
                val isAospChipHiding =
                    ((ds as? KeyguardChipDisplayState.Event)?.event as? IslandEvent.AospChip)
                        ?.active?.transitionManager?.hideChipForTransition == true

                Expandable(
                    controller = expandableController,
                    modifier = Modifier.graphicsLayer {
                        alpha = if (isAospChipHiding) 0f else visibilityAlpha
                    },
                    onClick = null,
                    useModifierBasedImplementation = StatusBarChipsReturnAnimations.isEnabled,
                    defaultMinSize = false,
                ) { aospExpandable ->
                    KeyguardNowBarPill(
                        displayState = ds,
                        viewModel = viewModel,
                        batteryString = batteryString,
                        aospChipExpandable = aospExpandable,
                        glassAlpha = { if (isAospChipHiding) 0f else visibilityAlpha },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyguardNowBarPill(
    displayState: KeyguardChipDisplayState,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String,
    aospChipExpandable: SystemUiExpandable,
    glassAlpha: () -> Float,
) {
    val targetAccent = when (displayState) {
        is KeyguardChipDisplayState.Event -> chipAccentColorFor(displayState.event)
        is KeyguardChipDisplayState.Battery -> when {
            displayState.info.isCharging -> BatteryChargingColor
            displayState.info.isPowerSave -> BatteryPowerSaveColor
            else -> Color.White
        }
    }
    val accent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "kg_accent",
    )

    val targetProgress = when (displayState) {
        is KeyguardChipDisplayState.Event -> chipProgressFor(displayState.event)
        is KeyguardChipDisplayState.Battery ->
            if (displayState.info.isCharging) (displayState.info.level / 100f).coerceIn(0f, 1f) else null
    }
    val progressAnim = remember { Animatable(targetProgress ?: 0f) }
    LaunchedEffect(targetProgress) {
        if (targetProgress != null) {
            if (abs(targetProgress - progressAnim.value) > 0.05f) {
                progressAnim.animateTo(targetProgress, tween(300, easing = FastOutSlowInEasing))
            } else {
                progressAnim.snapTo(targetProgress)
            }
        }
    }

    val isInteractive = when (displayState) {
        is KeyguardChipDisplayState.Event -> {
            val ev = displayState.event
            ev !is IslandEvent.KeyguardIndication && ev !is IslandEvent.AppSwitch
        }
        is KeyguardChipDisplayState.Battery -> false
    }

    Box(contentAlignment = Alignment.Center) {
        // Sized by the Row, whose animateContentSize drives the width, so the glass tracks it.
        NowBarGlass(
            accent = accent,
            alpha = glassAlpha,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            modifier = Modifier
                .height(NowBarHeight)
                .widthIn(min = 100.dp, max = 340.dp)
                .clip(NowBarShape)
                .then(
                    if (targetProgress != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val progressColor =
                                if (accent.isSpecified && accent.alpha > 0f) accent else Color.White
                            val barH = 2.5.dp.toPx()
                            val y = size.height - barH
                            drawRect(Color.White.copy(alpha = 0.12f), Offset(0f, y), Size(size.width, barH))
                            drawRect(
                                progressColor.copy(alpha = 0.90f),
                                Offset(0f, y),
                                Size(size.width * progressAnim.value, barH),
                            )
                        }
                    } else Modifier
                )
                .clickable(enabled = isInteractive) {
                    if (displayState is KeyguardChipDisplayState.Event) {
                        when (val ev = displayState.event) {
                            is IslandEvent.Notification -> viewModel.launchNotificationFromKeyguard(ev)
                            is IslandEvent.AospChip -> viewModel.handleAospChipTap(ev, aospChipExpandable)
                            else -> viewModel.keyguardExpansion.toggle()
                        }
                    }
                }
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = 380f,
                    ),
                )
                .padding(start = 7.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = displayState,
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                        scaleIn(initialScale = 0.92f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f))) togetherWith
                        (fadeOut(tween(durationMillis = 140)) +
                            scaleOut(targetScale = 0.92f, animationSpec = tween(durationMillis = 140))) using
                        NowBarSnapSize
                },
                contentKey = { target ->
                    when (target) {
                        is KeyguardChipDisplayState.Event -> "${target.event::class.simpleName}_${target.event.id}"
                        is KeyguardChipDisplayState.Battery -> "battery"
                    }
                },
                label = "kg_chip_content",
            ) { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (current) {
                        is KeyguardChipDisplayState.Event -> {
                            val event = current.event
                            if (event is IslandEvent.Media) {
                                MediaNowBarContent(
                                    event = event,
                                    accent = accent,
                                    viewModel = viewModel,
                                    eventCount = current.eventCount,
                                    pinnedIndex = current.pinnedIndex,
                                )
                            } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                                SportsNowBarContent(
                                    event = event,
                                    eventCount = current.eventCount,
                                    pinnedIndex = current.pinnedIndex,
                                )
                            } else {
                                GenericNowBarContent(
                                    event = event,
                                    accent = accent,
                                    viewModel = viewModel,
                                    batteryString = batteryString,
                                    eventCount = current.eventCount,
                                    pinnedIndex = current.pinnedIndex,
                                )
                            }
                        }
                        is KeyguardChipDisplayState.Battery -> {
                            BatteryNowBarContent(
                                info = current.info,
                                batteryString = current.batteryString,
                                iconTint = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MediaNowBarContent(
    event: IslandEvent.Media,
    accent: Color,
    viewModel: AxDynamicBarChipViewModel,
    eventCount: Int,
    pinnedIndex: Int,
) {
    val motionScheme = MaterialTheme.motionScheme

    AnimatedContent(
        targetState = event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                NowBarSnapSize
        },
        contentKey = { iconKeyFor(it) },
        label = "kg_media_icon",
    ) { media ->
        val art = media.albumArt
        if (art != null) {
            Image(
                bitmap = art.toScaledBitmap(NowBarIconContainerSize),
                contentDescription = null,
                modifier = Modifier
                    .size(NowBarIconContainerSize)
                    .clip(NowBarIconShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(NowBarIconContainerSize)
                    .clip(NowBarIconShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                PillEventIcon(media, tint = Color.White, animated = false)
            }
        }
    }

    Spacer(Modifier.width(8.dp))

    AnimatedContent(
        targetState = event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                NowBarSnapSize
        },
        contentKey = { "${it.track}|${it.artist}" },
        label = "kg_media_text",
        modifier = Modifier.weight(1f, fill = false),
    ) { ev ->
        Column(
            modifier = Modifier.widthIn(max = 135.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                style = MaterialTheme.typography.labelMediumEmphasized.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = 1),
            )
            if (ev.artist.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = ev.artist,
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Spacer(Modifier.width(8.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionButton(
            icon = ActionIcon.SKIP_PREV,
            color = Color.White.copy(alpha = 0.90f),
            bgColor = Color.White.copy(alpha = 0.08f),
            onClick = { viewModel.skipPrev() },
            size = ActionSize,
            iconSize = ActionIconSize,
        )
        Surface(
            onClick = { viewModel.togglePlayPause() },
            modifier = Modifier.size(PlayActionSize),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.18f),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(PlayActionSize)) {
                Icon(
                    if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (event.isPlaying) R.string.ax_dynamic_bar_pause
                        else R.string.ax_dynamic_bar_play,
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(PlayIconSize),
                )
            }
        }
        ActionButton(
            icon = ActionIcon.SKIP_NEXT,
            color = Color.White.copy(alpha = 0.90f),
            bgColor = Color.White.copy(alpha = 0.08f),
            onClick = { viewModel.skipNext() },
            size = ActionSize,
            iconSize = ActionIconSize,
        )
    }

    if (eventCount > 1) {
        Spacer(Modifier.width(6.dp))
        NowBarDots(
            count = eventCount,
            activeIndex = pinnedIndex,
            color = Color.White,
        )
    }
}

@Composable
private fun RowScope.SportsNowBarContent(
    event: IslandEvent.Sports,
    eventCount: Int,
    pinnedIndex: Int,
) {
    SportsChipTeamBadge(event.team1Name, event.team1Icon, Color.White)
    Spacer(Modifier.width(8.dp))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
            else stringResource(R.string.ax_dynamic_bar_sports_vs),
            style = MaterialTheme.typography.labelMediumEmphasized.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
        if (event.statusDetail.isNotEmpty()) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = event.statusDetail,
                style = MaterialTheme.typography.labelSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = Color.White.copy(alpha = 0.70f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    Spacer(Modifier.width(8.dp))
    SportsChipTeamBadge(event.team2Name, event.team2Icon, Color.White)

    if (eventCount > 1) {
        Spacer(Modifier.width(6.dp))
        NowBarDots(
            count = eventCount,
            activeIndex = pinnedIndex,
            color = Color.White,
        )
    }
}

@Composable
private fun RowScope.GenericNowBarContent(
    event: IslandEvent,
    accent: Color,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String,
    eventCount: Int,
    pinnedIndex: Int,
) {
    val context = LocalContext.current
    val motionScheme = MaterialTheme.motionScheme

    AnimatedContent(
        targetState = event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                NowBarSnapSize
        },
        contentKey = { iconKeyFor(it) },
        label = "kg_chip_icon",
    ) { ev ->
        Box(
            modifier = Modifier
                .size(NowBarIconContainerSize)
                .clip(NowBarIconShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            PillEventIcon(
                ev,
                tint = if (accent != Color.Unspecified && accent.alpha > 0f) accent else Color.White,
                animated = false,
            )
        }
    }

    Spacer(Modifier.width(8.dp))

    AnimatedContent(
        targetState = event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                NowBarSnapSize
        },
        contentKey = { textKeyFor(it) },
        label = "kg_chip_text",
        modifier = Modifier.weight(1f, fill = false),
    ) { ev ->
        NowBarEventTwoLineText(ev, batteryString)
    }

    val actions = actionsFor(event, context)
    if (actions.isNotEmpty()) {
        Spacer(Modifier.width(6.dp))
        actions.forEach { action ->
            ActionButton(
                icon = action.icon,
                color = Color.White,
                bgColor = Color.White.copy(alpha = 0.14f),
                onClick = { action.perform(viewModel, event, context) },
                size = ActionSize,
                iconSize = ActionIconSize,
            )
            Spacer(Modifier.width(4.dp))
        }
    }

    if (eventCount > 1) {
        Spacer(Modifier.width(6.dp))
        NowBarDots(
            count = eventCount,
            activeIndex = pinnedIndex,
            color = Color.White,
        )
    }
}

@Composable
private fun RowScope.BatteryNowBarContent(
    info: KeyguardBatteryInfo,
    batteryString: String,
    iconTint: Color,
) {
    val parts = rememberChargingParts(batteryString)
    val isCharging = info.isCharging

    Box(
        modifier = Modifier
            .size(NowBarIconContainerSize)
            .clip(NowBarIconShape)
            .background(iconTint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isCharging) {
            AnimatedChargingBoltIcon(iconTint, BatteryIconSize)
        } else {
            AnimatedBatteryFillIcon(info.level, iconTint, BatteryIconSize)
        }
    }
    Spacer(Modifier.width(10.dp))
    Column(
        modifier = Modifier.weight(1f, fill = false),
        verticalArrangement = Arrangement.Center,
    ) {
        if (isCharging) {
            Text(
                text = if (parts.isNotEmpty()) parts[0] else stringResource(R.string.ax_dynamic_bar_charging),
                style = MaterialTheme.typography.labelMediumEmphasized.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (parts.size >= 2) parts[1] else info.timeRemaining
            if (sub != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = "${info.level}%",
                style = MaterialTheme.typography.labelMediumEmphasized.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            val secondaryLabel = when {
                info.isPowerSave -> stringResource(R.string.ax_dynamic_bar_saver)
                info.timeRemaining != null -> info.timeRemaining
                else -> null
            }
            if (secondaryLabel != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = secondaryLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AnimatedChargingBoltIcon(color: Color, iconSize: Dp = BatteryIconSize) {
    val transition = rememberInfiniteTransition(label = "kg_bolt")
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "kg_bolt_glow",
    )
    val boltPath = remember { Path() }
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, h * 0.05f)
        boltPath.lineTo(w * 0.25f, h * 0.50f)
        boltPath.lineTo(w * 0.45f, h * 0.50f)
        boltPath.lineTo(w * 0.40f, h * 0.95f)
        boltPath.lineTo(w * 0.75f, h * 0.42f)
        boltPath.lineTo(w * 0.55f, h * 0.42f)
        boltPath.close()
        drawPath(boltPath, color.copy(alpha = glow))
    }
}

@Composable
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
    val fillFraction by animateFloatAsState(
        targetValue = (level / 100f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "kg_battery_fill",
    )
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.7f
        val bodyH = h * 0.85f
        val bodyX = (w - bodyW) / 2f
        val bodyY = h - bodyH
        val tipW = bodyW * 0.4f
        val tipH = h * 0.1f
        
        drawRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset((w - tipW) / 2f, 0f),
            size = Size(tipW, tipH),
        )
        
        drawRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset(bodyX, bodyY),
            size = Size(bodyW, bodyH),
        )
        
        val fillH = bodyH * fillFraction
        drawRect(
            color,
            topLeft = Offset(bodyX, bodyY + bodyH - fillH),
            size = Size(bodyW, fillH),
        )
    }
}

@Composable
private fun NowBarEventTwoLineText(event: IslandEvent, batteryString: String) {
    Column(
        modifier = Modifier.widthIn(max = 145.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (event) {
            is IslandEvent.AudioRecording -> {
                Text(
                    text = event.appName.ifEmpty { stringResource(R.string.ax_dynamic_bar_recording) },
                    style = MaterialTheme.typography.labelMediumEmphasized.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
                Spacer(Modifier.height(1.dp))
                when (event.state) {
                    RecordingState.RECORDING -> ElapsedTimeText(
                        event.startTimeMs,
                        Color.White.copy(alpha = 0.70f),
                        Modifier,
                        event.pausedDurationMs,
                    )
                    RecordingState.PAUSED -> Text(
                        stringResource(R.string.ax_dynamic_bar_paused),
                        style = MaterialTheme.typography.labelSmall.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                    )
                    RecordingState.SAVED -> Text(
                        stringResource(R.string.ax_dynamic_bar_saved),
                        style = MaterialTheme.typography.labelSmall.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                    )
                }
            }
            is IslandEvent.Timer -> {
                Text(
                    text = event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) },
                    style = MaterialTheme.typography.labelMediumEmphasized.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
                Spacer(Modifier.height(1.dp))
                if (event.endTimeMs > 0L) {
                    CountdownText(event, Color.White.copy(alpha = 0.70f), Modifier)
                } else {
                    Text(
                        if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused)
                        else stringResource(R.string.ax_dynamic_bar_running),
                        style = MaterialTheme.typography.labelSmall.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                    )
                }
            }
            is IslandEvent.Stopwatch -> {
                Text(
                    text = event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_stopwatch) },
                    style = MaterialTheme.typography.labelMediumEmphasized.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
                Spacer(Modifier.height(1.dp))
                StopwatchTimeText(event, Color.White.copy(alpha = 0.70f), Modifier)
            }
            is IslandEvent.Charging -> {
                val parts = rememberChargingParts(batteryString)
                Text(
                    text = if (parts.isNotEmpty()) parts[0] else "${event.level}%",
                    style = MaterialTheme.typography.labelMediumEmphasized.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = if (parts.size >= 2) parts[1] else event.timeRemaining
                if (sub != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            is IslandEvent.AospChip -> {
                AospKeyguardChipText(event, Color.White, Modifier)
            }
            else -> {
                val primary = when (event) {
                    is IslandEvent.Notification -> event.title ?: event.appName ?: ""
                    is IslandEvent.Bluetooth -> event.deviceName.take(16)
                    is IslandEvent.Hotspot -> stringResource(R.string.ax_dynamic_bar_hotspot)
                    is IslandEvent.Alarm -> event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }
                    is IslandEvent.Torch -> stringResource(R.string.ax_dynamic_bar_flashlight)
                    is IslandEvent.RingerMode -> event.label
                    is IslandEvent.Vpn -> stringResource(R.string.ax_dynamic_bar_vpn_active)
                    is IslandEvent.Clipboard -> event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }
                    is IslandEvent.BiometricUnlock -> stringResource(R.string.ax_dynamic_bar_unlocked)
                    is IslandEvent.AppSwitch -> stringResource(R.string.ax_dynamic_bar_recents)
                    is IslandEvent.PromotedOngoing -> event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }
                    is IslandEvent.NowPlaying -> "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' ')
                    is IslandEvent.KeyguardIndication -> event.text
                    else -> ""
                }
                Text(
                    text = primary,
                    style = MaterialTheme.typography.labelMediumEmphasized.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
                val secondary = secondaryTextFor(event)
                if (secondary != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AospKeyguardChipText(
    event: IslandEvent.AospChip,
    color: Color,
    modifier: Modifier,
) {
    when (val content = event.active.content) {
        is OngoingActivityChipModel.Content.Text -> {
            if (content.text.isNotBlank()) MarqueeText(content.text, color, modifier)
        }
        is OngoingActivityChipModel.Content.Timer -> AospKeyguardTimerText(content, color, modifier)
        is OngoingActivityChipModel.Content.ShortTimeDelta -> AospKeyguardDeltaText(content, color, modifier)
        is OngoingActivityChipModel.Content.Countdown ->
            PillMonoLabel(formatCountdownLong(content.secondsUntilStarted * 1000L), color, modifier)
        is OngoingActivityChipModel.Content.IconOnly -> Unit
    }
}

@Composable
private fun AospKeyguardTimerText(
    content: OngoingActivityChipModel.Content.Timer,
    color: Color,
    modifier: Modifier,
) {
    var elapsedMs by remember(content.startTimeMs, content.isEventInFuture, content.timeSource) {
        mutableLongStateOf(aospTimerElapsedMs(content))
    }
    LaunchedEffect(content.startTimeMs, content.isEventInFuture, content.timeSource) {
        while (true) {
            elapsedMs = aospTimerElapsedMs(content)
            delay(1000L - abs(content.startTimeMs - content.timeSource.getCurrentTime()) % 1000L)
        }
    }
    PillMonoLabel(formatCountdownLong(elapsedMs), color, modifier)
}

private fun aospTimerElapsedMs(content: OngoingActivityChipModel.Content.Timer): Long {
    val now = content.timeSource.getCurrentTime()
    return if (content.isEventInFuture) {
        (content.startTimeMs - now).coerceAtLeast(0L)
    } else {
        (now - content.startTimeMs).coerceAtLeast(0L)
    }
}

@Composable
private fun AospKeyguardDeltaText(
    content: OngoingActivityChipModel.Content.ShortTimeDelta,
    color: Color,
    modifier: Modifier,
) {
    var deltaMs by remember(content.time) {
        mutableLongStateOf(System.currentTimeMillis() - content.time)
    }
    LaunchedEffect(content.time) {
        while (true) {
            deltaMs = System.currentTimeMillis() - content.time
            delay(30_000)
        }
    }
    MarqueeText(aospShortDeltaText(deltaMs), color, modifier)
}

@Composable
private fun aospShortDeltaText(deltaMs: Long): String {
    val mins = abs(deltaMs) / 60_000L
    return when {
        mins < 1L -> stringResource(R.string.ax_dynamic_bar_just_now)
        mins < 60L -> stringResource(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        mins < 1440L -> stringResource(R.string.ax_dynamic_bar_hours_ago, (mins / 60L).toInt())
        else -> stringResource(R.string.ax_dynamic_bar_days_ago, (mins / 1440L).toInt())
    }
}

@Composable
private fun secondaryTextFor(event: IslandEvent): String? = when (event) {
    is IslandEvent.Media -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.AudioRecording -> event.appName.takeIf { it.isNotBlank() }
    is IslandEvent.Timer,
    is IslandEvent.Stopwatch,
    is IslandEvent.Vpn,
    is IslandEvent.AppSwitch -> null
    is IslandEvent.Hotspot -> when {
        event.numDevices == 1 -> "1 device"
        event.numDevices > 1 -> "${event.numDevices} devices"
        else -> stringResource(R.string.ax_dynamic_bar_hotspot_active)
    }
    is IslandEvent.Torch -> if (event.supportsLevel) "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%" else stringResource(R.string.ax_dynamic_bar_on)
    is IslandEvent.Charging -> event.timeRemaining
    is IslandEvent.Bluetooth -> if (event.batteryLevel >= 0) "${event.batteryLevel}%" else event.deviceTypeLabel.takeIf { it.isNotBlank() }
    is IslandEvent.Alarm -> {
        if (event.triggerTimeMs > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = event.triggerTimeMs }
            val triggerTime = "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            triggerTime.takeIfDistinctFrom(event.label)
        } else null
    }
    is IslandEvent.BiometricUnlock -> event.sourceName
    is IslandEvent.Notification ->
        event.text
            ?.takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.title, event.appName)
            ?.take(30)
    is IslandEvent.PromotedOngoing ->
        event.text
            .takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.shortText, event.title, event.appName)
            ?.take(20)
    is IslandEvent.Sports -> "${event.team1Name} ${stringResource(R.string.ax_dynamic_bar_sports_vs)} ${event.team2Name}"
    is IslandEvent.NowPlaying -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.KeyguardIndication -> when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> stringResource(R.string.ax_dynamic_bar_biometric)
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> stringResource(R.string.ax_dynamic_bar_trust)
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> stringResource(R.string.ax_dynamic_bar_alignment)
        else -> null
    }
    else -> null
}

private fun String.takeIfDistinctFrom(vararg others: String?): String? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.takeIf { candidate ->
        others.none { other -> candidate.equals(other?.trim(), ignoreCase = true) }
    }
}

@Composable
private fun SportsChipTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(NowBarIconContainerSize),
            contentDescription = name,
            modifier = Modifier.size(NowBarIconContainerSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(NowBarIconContainerSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun NowBarDots(count: Int, activeIndex: Int, color: Color) {
    val dotCount = count.coerceAtMost(5)
    // With more events than dots, map the pinned index proportionally so the last dot is not stuck.
    val activeDot = if (count <= dotCount) {
        activeIndex.coerceIn(0, dotCount - 1)
    } else {
        (activeIndex.coerceIn(0, count - 1) * (dotCount - 1) + (count - 1) / 2) / (count - 1)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until dotCount) {
            val active = i == activeDot
            Box(
                Modifier
                    .size(if (active) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 1f else AlphaHint))
            )
        }
    }
}

private enum class ActionIcon { PLAY, PAUSE, STOP, SKIP_PREV, SKIP_NEXT }

private data class ChipAction(
    val icon: ActionIcon,
    val perform: (AxDynamicBarChipViewModel, IslandEvent, Context) -> Unit,
)

private fun actionsFor(event: IslandEvent, context: Context): List<ChipAction> = when (event) {
    is IslandEvent.AudioRecording -> {
        val classified = event.actions.map { it to it.action.classify(context, it.action.actionIntent?.creatorPackage ?: context.packageName) }
        val pauseResume = classified.firstOrNull { (_, k) -> k == NotificationActionType.PAUSE || k == NotificationActionType.RESUME }?.first
        val stop = classified.firstOrNull { (_, k) -> k == NotificationActionType.STOP || k == NotificationActionType.DELETE }?.first
        listOfNotNull(
            pauseResume?.let { action ->
                ChipAction(
                    if (event.state == RecordingState.RECORDING) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
            stop?.let { action ->
                ChipAction(ActionIcon.STOP) { _, _, ctx ->
                    action.action.actionIntent?.sendWithBal(ctx)
                }
            },
        )
    }
    is IslandEvent.Timer -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isPaused) ActionIcon.PLAY else ActionIcon.PAUSE,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Stopwatch -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isRunning) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Torch -> listOf(
        ChipAction(ActionIcon.STOP) { vm, event, _ -> vm.dismissEvent(event) },
    )
    else -> emptyList()
}

@Composable
private fun ActionButton(
    icon: ActionIcon,
    color: Color,
    bgColor: Color,
    onClick: () -> Unit,
    size: Dp = ActionSize,
    iconSize: Dp = ActionIconSize,
) {
    val imageVector = when (icon) {
        ActionIcon.PLAY -> Icons.Filled.PlayArrow
        ActionIcon.PAUSE -> Icons.Filled.Pause
        ActionIcon.STOP -> Icons.Filled.Stop
        ActionIcon.SKIP_PREV -> Icons.Filled.SkipPrevious
        ActionIcon.SKIP_NEXT -> Icons.Filled.SkipNext
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(
                imageVector,
                contentDescription = stringResource(
                    when (icon) {
                        ActionIcon.PLAY -> R.string.ax_dynamic_bar_play
                        ActionIcon.PAUSE -> R.string.ax_dynamic_bar_pause
                        ActionIcon.STOP -> R.string.ax_dynamic_bar_stop
                        ActionIcon.SKIP_PREV -> R.string.ax_dynamic_bar_previous
                        ActionIcon.SKIP_NEXT -> R.string.ax_dynamic_bar_next
                    }
                ),
                tint = color,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun MarqueeText(text: String, color: Color, modifier: Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelMediumEmphasized.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.widthIn(max = 140.dp).basicMarquee(iterations = 1),
    )
}

@Composable
private fun ElapsedTimeText(
    startTimeMs: Long,
    color: Color,
    modifier: Modifier,
    pausedDurationMs: Long = 0L,
) {
    var elapsedMs by remember(startTimeMs, pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - startTimeMs - pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(startTimeMs, pausedDurationMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs - pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }
    PillMonoLabel(formatCountdownLong(elapsedMs), color, modifier)
}

@Composable
private fun CountdownText(event: IslandEvent.Timer, color: Color, modifier: Modifier) {
    if (event.isPaused) {
        PillMonoLabel(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
    } else {
        var remainingMs by remember(event.endTimeMs) {
            mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
        PillMonoLabel(formatCountdownLong(remainingMs), color, modifier)
    }
}

@Composable
private fun StopwatchTimeText(event: IslandEvent.Stopwatch, color: Color, modifier: Modifier) {
    if (!event.isRunning) {
        PillMonoLabel(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
    } else {
        var elapsedMs by remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        PillMonoLabel(formatStopwatch(elapsedMs), color, modifier)
    }
}
