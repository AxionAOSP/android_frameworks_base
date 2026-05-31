package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.transitions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaIconBg
import com.android.systemui.axdynamicbar.shared.AlphaSecondary
import com.android.systemui.axdynamicbar.shared.AlphaTertiary
import com.android.systemui.axdynamicbar.shared.AxDynamicBarTheme
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.ShapeXl
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SizeBadge
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipContentColorOn
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsReturnAnimations
import kotlin.math.abs

private val ChipShape = ShapeXl
private val ChipHeight = 24.dp

@Composable
fun AxDynamicBarChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
    ignoreKeyguard: Boolean = false,
) {
    AxDynamicBarTheme {
        AxDynamicBarChipContent(viewModel, modifier, ignoreKeyguard)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AxDynamicBarChipContent(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier,
    ignoreKeyguard: Boolean,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val keyguardCarrier by viewModel.keyguardCarrierText.collectAsStateWithLifecycle()
    
    val carrierName = if (isOnKeyguard && ignoreKeyguard) keyguardCarrier.takeIf { it.isNotBlank() } else null
    val chipTextMaxWidth = dimensionResource(R.dimen.ongoing_activity_chip_max_text_width)
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val anchorView = LocalView.current
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val boundsExpandable = rememberBoundsExpandable(anchorBounds)

    val touchSlop = LocalViewConfiguration.current.touchSlop
    val transitionControllerFactory =
        (state?.event as? IslandEvent.AospChip)?.active?.transitionManager?.controllerFactory
    val expandableController =
        rememberExpandableController(
            color = Color.Transparent,
            shape = ChipShape,
            transitionControllerFactory = transitionControllerFactory,
        )

    val motionScheme = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = state != null && (ignoreKeyguard || !isOnKeyguard),
        enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.8f, animationSpec = motionScheme.defaultSpatialSpec()),
        exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.8f, animationSpec = motionScheme.fastSpatialSpec()),
        modifier = modifier
            .pointerInput(viewModel) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    
                    val startX = down.position.x
                    val startY = down.position.y
                    var dragging = false
                    var totalDx = 0f
                    var decided = false 
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            
                            if (dragging) {
                                change.consume()
                                if (totalDx > 0) viewModel.cyclePrev()
                                else viewModel.cycleNext()
                            } else if (!decided) {

                                change.consume()
                                val current = state?.event
                                if (current is IslandEvent.AospChip) {
                                    if (!viewModel.handleAospChipTap(current, expandableController.expandable)) {
                                        viewModel.statusBarExpansion.toggle(boundsExpandable)
                                    }
                                } else {
                                    viewModel.statusBarExpansion.toggle(boundsExpandable)
                                }
                            }
                            
                            break
                        }
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        if (!decided && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            if (abs(dx) >= abs(dy)) {
                                
                                decided = true
                                dragging = true
                                totalDx = dx
                                change.consume()
                            } else {
                                
                                decided = true
                                break
                            }
                        } else if (dragging) {
                            totalDx = dx
                            change.consume()
                        }
                    }
                }
            }
    ) {
        state?.let { chipState ->
            val displayEvent = chipState.event
            val isAlert = false
            val event = displayEvent
            val chipVisibilityModifier =
                if (
                    (displayEvent as? IslandEvent.AospChip)
                        ?.active
                        ?.transitionManager
                        ?.hideChipForTransition == true
                ) {
                    Modifier.graphicsLayer { alpha = 0f }
                } else {
                    Modifier
                }
            val rawAccent = chipAccentColorFor(event)
            val accent by animateColorAsState(
                rawAccent,
                MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "accent",
            )
            val contentColor by animateColorAsState(
                chipContentColorOn(rawAccent),
                MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "content",
            )
            val progress = chipProgressFor(event)

            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.onGloballyPositioned { coords ->
                            val bounds = coords.screenBounds(anchorView)
                            anchorBounds = bounds
                            if (screenWidthPx > 0f) {
                                viewModel.updateChipBounds(
                                    bounds.left.toFloat(),
                                    bounds.top.toFloat(),
                                    bounds.right.toFloat(),
                                    bounds.bottom.toFloat(),
                                    screenWidthPx,
                                )
                            }
                        },
                ) {
                    Row(
                        modifier =
                            Modifier.height(ChipHeight)
                                .widthIn(max = 100.dp)
                                .then(chipVisibilityModifier)
                                .clip(ChipShape)
                                .background(accent)
                                .then(
                                    if (progress != null) {
                                        val trackColor = lerp(accent, contentColor, 0.2f)
                                        val fillColor = lerp(accent, contentColor, 0.6f)
                                        Modifier.drawWithContent {
                                            drawContent()
                                            val barH = 2.dp.toPx()
                                            val y = size.height - barH
                                            drawRect(
                                                trackColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width, barH),
                                            )
                                            drawRect(
                                                fillColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width * progress, barH),
                                            )
                                        }
                                    } else Modifier
                                )
                                .padding(start = SpaceSm, end = SpaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (carrierName != null) {
                            Text(
                                text = carrierName,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 56.dp),
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                        }
                        ChipEventSceneContent(
                            event = event,
                            isAlert = isAlert,
                            chipState = chipState,
                            contentColor = contentColor,
                            accent = accent,
                            chipTextMaxWidth = chipTextMaxWidth,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (event is IslandEvent.AospChip) {
                        Expandable(
                            controller = expandableController,
                            modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0f },
                            onClick = null,
                            useModifierBasedImplementation = StatusBarChipsReturnAnimations.isEnabled,
                            defaultMinSize = false,
                        ) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

private object ChipEventScenes {
    val Alert = SceneKey("ax_dynamic_bar_chip_alert")
    val Media = SceneKey("ax_dynamic_bar_chip_media")
    val Sports = SceneKey("ax_dynamic_bar_chip_sports")
    val AospChip = SceneKey("ax_dynamic_bar_chip_aosp")
    val Timer = SceneKey("ax_dynamic_bar_chip_timer")
    val Stopwatch = SceneKey("ax_dynamic_bar_chip_stopwatch")
    val AudioRecording = SceneKey("ax_dynamic_bar_chip_audio_recording")
    val Notification = SceneKey("ax_dynamic_bar_chip_notification")
    val AppSwitch = SceneKey("ax_dynamic_bar_chip_app_switch")
    val Default = SceneKey("ax_dynamic_bar_chip_default")
    val All = listOf(
        Alert,
        Media,
        Sports,
        AospChip,
        Timer,
        Stopwatch,
        AudioRecording,
        Notification,
        AppSwitch,
        Default,
    )
}

private object ChipEventElements {
    val AlertContent = ElementKey("ax_dynamic_bar_chip_alert_content")
    val MediaContent = ElementKey("ax_dynamic_bar_chip_media_content")
    val SportsContent = ElementKey("ax_dynamic_bar_chip_sports_content")
    val AospChipContent = ElementKey("ax_dynamic_bar_chip_aosp_content")
    val TimerContent = ElementKey("ax_dynamic_bar_chip_timer_content")
    val StopwatchContent = ElementKey("ax_dynamic_bar_chip_stopwatch_content")
    val AudioRecordingContent = ElementKey("ax_dynamic_bar_chip_audio_recording_content")
    val NotificationContent = ElementKey("ax_dynamic_bar_chip_notification_content")
    val AppSwitchContent = ElementKey("ax_dynamic_bar_chip_app_switch_content")
    val DefaultContent = ElementKey("ax_dynamic_bar_chip_default_content")
    val All = listOf(
        AlertContent,
        MediaContent,
        SportsContent,
        AospChipContent,
        TimerContent,
        StopwatchContent,
        AudioRecordingContent,
        NotificationContent,
        AppSwitchContent,
        DefaultContent,
    )
    val Content =
        object : ElementMatcher {
            override fun matches(key: ElementKey, content: ContentKey): Boolean = key in All
        }
}

private val ChipDefaultSceneElements = listOf(
    ChipEventScenes.AospChip to ChipEventElements.AospChipContent,
    ChipEventScenes.Timer to ChipEventElements.TimerContent,
    ChipEventScenes.Stopwatch to ChipEventElements.StopwatchContent,
    ChipEventScenes.AudioRecording to ChipEventElements.AudioRecordingContent,
    ChipEventScenes.Notification to ChipEventElements.NotificationContent,
    ChipEventScenes.AppSwitch to ChipEventElements.AppSwitchContent,
    ChipEventScenes.Default to ChipEventElements.DefaultContent,
)

private val ChipEventTransitions = transitions {
    ChipEventScenes.All.forEach { from(it) { chipEventTransition() } }
}

private fun TransitionBuilder.chipEventTransition() {
    fade(ChipEventElements.Content)
}

@Composable
private fun ChipEventSceneContent(
    event: IslandEvent,
    isAlert: Boolean,
    chipState: AxDynamicBarChipState,
    contentColor: Color,
    accent: Color,
    chipTextMaxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val targetScene = chipEventSceneFor(event, isAlert)
    val sceneState = rememberDynamicBarEventSceneState(targetScene, event, ChipEventTransitions)

    NoOpBackDispatcherOwner {
        SceneTransitionLayout(state = sceneState.layoutState, modifier = modifier) {
            scene(ChipEventScenes.Alert) {
                (sceneState.eventFor(ChipEventScenes.Alert) as? IslandEvent.Notification)?.let {
                    ChipAlertEventContent(
                        it,
                        contentColor,
                        chipTextMaxWidth,
                        Modifier.element(ChipEventElements.AlertContent),
                    )
                }
            }
            scene(ChipEventScenes.Media) {
                (sceneState.eventFor(ChipEventScenes.Media) as? IslandEvent.Media)?.let {
                    ChipDefaultEventContent(
                        it,
                        chipState,
                        contentColor,
                        accent,
                        chipTextMaxWidth,
                        Modifier.element(ChipEventElements.MediaContent),
                    )
                }
            }
            scene(ChipEventScenes.Sports) {
                (sceneState.eventFor(ChipEventScenes.Sports) as? IslandEvent.Sports)?.let {
                    ChipSportsEventContent(
                        it,
                        contentColor,
                        Modifier.element(ChipEventElements.SportsContent),
                    )
                }
            }
            ChipDefaultSceneElements.forEach { (sceneKey, elementKey) ->
                scene(sceneKey) {
                    sceneState.eventFor(sceneKey)?.let {
                        ChipDefaultEventContent(
                            it,
                            chipState,
                            contentColor,
                            accent,
                            chipTextMaxWidth,
                            Modifier.element(elementKey),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipAlertEventContent(
    event: IslandEvent.Notification,
    contentColor: Color,
    chipTextMaxWidth: Dp,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        event.appIcon?.let { icon ->
            Image(
                bitmap = icon.toScaledBitmap(16.dp),
                contentDescription = null,
                modifier = Modifier.size(16.dp).clip(ShapeXs),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(SpaceXs))
        }
        Text(
            text = event.appName ?: "",
            style = PillPrimary,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = chipTextMaxWidth).basicMarquee(iterations = 1),
        )
    }
}

@Composable
private fun ChipSportsEventContent(
    event: IslandEvent.Sports,
    contentColor: Color,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusBarSportsTeamBadge(event.team1Name, event.team1Icon, contentColor)
        Spacer(Modifier.width(SpaceXs))
        Text(
            if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
            else stringResource(R.string.ax_dynamic_bar_sports_vs),
            style = PillPrimary,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(SpaceXs))
        StatusBarSportsTeamBadge(event.team2Name, event.team2Icon, contentColor)
    }
}

@Composable
private fun ChipDefaultEventContent(
    event: IslandEvent,
    chipState: AxDynamicBarChipState,
    contentColor: Color,
    accent: Color,
    chipTextMaxWidth: Dp,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PillEventIcon(event, tint = contentColor, animated = false)
        Spacer(Modifier.width(SpaceXs))
        if (event is IslandEvent.Media) {
            MediaText(
                mediaTextStateFor(event),
                Modifier.weight(1f, fill = false).widthIn(max = chipTextMaxWidth),
                overrideColor = contentColor,
            )
        } else {
            PillEventText(
                event,
                Modifier.weight(1f, fill = false).widthIn(max = chipTextMaxWidth),
                overrideColor = contentColor,
            )
        }
        ChipEventCountBadge(chipState, accent, contentColor)
    }
}

@Composable
private fun ChipEventCountBadge(
    chipState: AxDynamicBarChipState,
    accent: Color,
    contentColor: Color,
) {
    if (chipState.eventCount <= 1) return
    Spacer(Modifier.width(SpaceXs))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(SizeBadge)
            .widthIn(min = SizeBadge)
            .background(lerp(accent, contentColor, 0.3f), RoundedCornerShape(SizeBadge / 2))
            .padding(horizontal = 3.dp),
    ) {
        Text(
            text = "${chipState.eventCount}",
            style = TsBadge,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun chipEventSceneFor(event: IslandEvent, isAlert: Boolean): SceneKey =
    when {
        isAlert && event is IslandEvent.Notification -> ChipEventScenes.Alert
        event is IslandEvent.Media -> ChipEventScenes.Media
        event is IslandEvent.Sports && event.team2Name.isNotEmpty() -> ChipEventScenes.Sports
        event is IslandEvent.AospChip -> ChipEventScenes.AospChip
        event is IslandEvent.Timer -> ChipEventScenes.Timer
        event is IslandEvent.Stopwatch -> ChipEventScenes.Stopwatch
        event is IslandEvent.AudioRecording -> ChipEventScenes.AudioRecording
        event is IslandEvent.Notification -> ChipEventScenes.Notification
        event is IslandEvent.AppSwitch -> ChipEventScenes.AppSwitch
        else -> ChipEventScenes.Default
    }

@Composable
private fun StatusBarSportsTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    val badgeSize = 16.dp
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(badgeSize),
            contentDescription = name,
            modifier = Modifier.size(badgeSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(badgeSize).clip(CircleShape)
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
