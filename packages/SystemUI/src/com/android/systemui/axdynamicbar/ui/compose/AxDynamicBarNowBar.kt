package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.transitions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaHint
import com.android.systemui.axdynamicbar.shared.AlphaTrack
import com.android.systemui.axdynamicbar.shared.AxDynamicBarTheme
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.ShapeXl
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipContentColorOn
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import kotlin.math.abs

private val NowBarShape = ShapeXl
private val NowBarHeight = 32.dp
private val NowBarMaxWidth = 200.dp

@Composable
fun AxDynamicBarNowBar(
    state: AxDynamicBarChipState?,
    viewModel: AxDynamicBarChipViewModel,
) {
    AxDynamicBarTheme {
        AxDynamicBarNowBarContent(state, viewModel)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AxDynamicBarNowBarContent(
    state: AxDynamicBarChipState?,
    viewModel: AxDynamicBarChipViewModel,
) {
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val motionScheme = MaterialTheme.motionScheme
    val anchorView = LocalView.current
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val boundsExpandable = rememberBoundsExpandable(anchorBounds)

    AnimatedVisibility(
        visible = state != null,
        enter = slideInVertically(motionScheme.defaultSpatialSpec()) { -it } + fadeIn(motionScheme.defaultEffectsSpec()),
        exit = slideOutVertically(motionScheme.fastSpatialSpec()) { -it } + fadeOut(motionScheme.fastEffectsSpec()),
    ) {
        state?.let { chipState ->
            val event = chipState.event
            val isAlert = false
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
            val rawProgress = chipProgressFor(event)
            val progressTarget = rawProgress ?: 0f
            val progressAnim = remember { Animatable(progressTarget) }
            LaunchedEffect(progressTarget) {
                if (abs(progressTarget - progressAnim.value) > 0.05f) {
                    progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                } else {
                    progressAnim.snapTo(progressTarget)
                }
            }
            val progress = if (rawProgress != null) progressAnim.value else null

            Box(
                modifier =
                    Modifier
                        .padding(top = SpaceXs)
                        .pointerInput(viewModel, touchSlop) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var totalDragX = 0f
                                var isDragging = false

                                while (true) {
                                    val pointerEvent = awaitPointerEvent()
                                    val change = pointerEvent.changes.firstOrNull() ?: break

                                    if (!change.pressed) {
                                        change.consume()
                                        if (isDragging) {
                                            if (totalDragX > 0f) viewModel.cyclePrev()
                                            else viewModel.cycleNext()
                                        } else {
                                            viewModel.statusBarExpansion.toggle(
                                                boundsExpandable
                                            )
                                        }
                                        break
                                    }

                                    val delta = change.positionChange()
                                    totalDragX += delta.x

                                    if (!isDragging && abs(totalDragX) > touchSlop) {
                                        isDragging = true
                                    }

                                    if (isDragging) {
                                        change.consume()
                                    }
                                }
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .height(NowBarHeight)
                        .widthIn(max = NowBarMaxWidth)
                        .onGloballyPositioned { anchorBounds = it.screenBounds(anchorView) }
                        .shadow(6.dp, NowBarShape)
                        .clip(NowBarShape)
                        .background(accent)
                        .then(
                            if (progress != null) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    val barH = 3.dp.toPx()
                                    val y = size.height - barH
                                    drawRect(
                                        contentColor.copy(alpha = AlphaTrack),
                                        topLeft = Offset(0f, y),
                                        size = Size(size.width, barH),
                                    )
                                    drawRect(
                                        contentColor.copy(alpha = 0.85f),
                                        topLeft = Offset(0f, y),
                                        size = Size(size.width * progress, barH),
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                        .padding(start = 10.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NowBarEventSceneContent(
                        event = event,
                        isAlert = isAlert,
                        contentColor = contentColor,
                        chipState = chipState,
                    )
                }
            }
        }
    }
}

private object NowBarEventScenes {
    val Alert = SceneKey("ax_dynamic_bar_nowbar_alert")
    val Media = SceneKey("ax_dynamic_bar_nowbar_media")
    val Sports = SceneKey("ax_dynamic_bar_nowbar_sports")
    val AospChip = SceneKey("ax_dynamic_bar_nowbar_aosp")
    val Timer = SceneKey("ax_dynamic_bar_nowbar_timer")
    val Stopwatch = SceneKey("ax_dynamic_bar_nowbar_stopwatch")
    val AudioRecording = SceneKey("ax_dynamic_bar_nowbar_audio_recording")
    val Notification = SceneKey("ax_dynamic_bar_nowbar_notification")
    val AppSwitch = SceneKey("ax_dynamic_bar_nowbar_app_switch")
    val Default = SceneKey("ax_dynamic_bar_nowbar_default")
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

private object NowBarEventElements {
    val AlertContent = ElementKey("ax_dynamic_bar_nowbar_alert_content")
    val MediaContent = ElementKey("ax_dynamic_bar_nowbar_media_content")
    val SportsContent = ElementKey("ax_dynamic_bar_nowbar_sports_content")
    val AospChipContent = ElementKey("ax_dynamic_bar_nowbar_aosp_content")
    val TimerContent = ElementKey("ax_dynamic_bar_nowbar_timer_content")
    val StopwatchContent = ElementKey("ax_dynamic_bar_nowbar_stopwatch_content")
    val AudioRecordingContent = ElementKey("ax_dynamic_bar_nowbar_audio_recording_content")
    val NotificationContent = ElementKey("ax_dynamic_bar_nowbar_notification_content")
    val AppSwitchContent = ElementKey("ax_dynamic_bar_nowbar_app_switch_content")
    val DefaultContent = ElementKey("ax_dynamic_bar_nowbar_default_content")
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

private val NowBarDefaultSceneElements = listOf(
    NowBarEventScenes.Sports to NowBarEventElements.SportsContent,
    NowBarEventScenes.AospChip to NowBarEventElements.AospChipContent,
    NowBarEventScenes.Timer to NowBarEventElements.TimerContent,
    NowBarEventScenes.Stopwatch to NowBarEventElements.StopwatchContent,
    NowBarEventScenes.AudioRecording to NowBarEventElements.AudioRecordingContent,
    NowBarEventScenes.Notification to NowBarEventElements.NotificationContent,
    NowBarEventScenes.AppSwitch to NowBarEventElements.AppSwitchContent,
    NowBarEventScenes.Default to NowBarEventElements.DefaultContent,
)

private val NowBarEventTransitions = transitions {
    NowBarEventScenes.All.forEach { from(it) { nowBarEventTransition() } }
}

private fun TransitionBuilder.nowBarEventTransition() {
    spec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
    fade(NowBarEventElements.Content)
}

@Composable
private fun NowBarEventSceneContent(
    event: IslandEvent,
    isAlert: Boolean,
    contentColor: Color,
    chipState: AxDynamicBarChipState,
    modifier: Modifier = Modifier,
) {
    val targetScene = nowBarEventSceneFor(event, isAlert)
    val sceneState = rememberDynamicBarEventSceneState(targetScene, event, NowBarEventTransitions)

    NoOpBackDispatcherOwner {
        SceneTransitionLayout(state = sceneState.layoutState, modifier = modifier) {
            scene(NowBarEventScenes.Alert) {
                (sceneState.eventFor(NowBarEventScenes.Alert) as? IslandEvent.Notification)?.let {
                    AlertPillContent(it, contentColor, Modifier.element(NowBarEventElements.AlertContent))
                }
            }
            scene(NowBarEventScenes.Media) {
                (sceneState.eventFor(NowBarEventScenes.Media) as? IslandEvent.Media)?.let {
                    EventPillContent(
                        it,
                        contentColor,
                        chipState,
                        Modifier.element(NowBarEventElements.MediaContent),
                    )
                }
            }
            NowBarDefaultSceneElements.forEach { (sceneKey, elementKey) ->
                scene(sceneKey) {
                    sceneState.eventFor(sceneKey)?.let {
                        EventPillContent(
                            it,
                            contentColor,
                            chipState,
                            Modifier.element(elementKey),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertPillContent(
    event: IslandEvent.Notification,
    contentColor: Color,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        event.appIcon?.let { icon ->
            Image(
                bitmap = icon.toScaledBitmap(18.dp),
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(ShapeXs),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(SpaceSm))
        }
        Text(
            text = event.appName ?: "",
            style = PillPrimary,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee(iterations = 1),
        )
    }
}

@Composable
private fun EventPillContent(
    event: IslandEvent,
    contentColor: Color,
    chipState: AxDynamicBarChipState,
    modifier: Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        PillEventIcon(event, tint = contentColor)
        Spacer(Modifier.width(SpaceSm))
        if (event is IslandEvent.Media) {
            MediaText(mediaTextStateFor(event), Modifier, overrideColor = contentColor)
        } else {
            PillEventText(event, Modifier, overrideColor = contentColor)
        }
        if (chipState.eventCount > 1) {
            Spacer(Modifier.width(SpaceSm))
            NowBarDots(
                count = chipState.eventCount,
                activeIndex = chipState.pinnedIndex,
                color = contentColor,
            )
        }
    }
}

private fun nowBarEventSceneFor(event: IslandEvent, isAlert: Boolean): SceneKey =
    when {
        isAlert && event is IslandEvent.Notification -> NowBarEventScenes.Alert
        event is IslandEvent.Media -> NowBarEventScenes.Media
        event is IslandEvent.Sports -> NowBarEventScenes.Sports
        event is IslandEvent.AospChip -> NowBarEventScenes.AospChip
        event is IslandEvent.Timer -> NowBarEventScenes.Timer
        event is IslandEvent.Stopwatch -> NowBarEventScenes.Stopwatch
        event is IslandEvent.AudioRecording -> NowBarEventScenes.AudioRecording
        event is IslandEvent.Notification -> NowBarEventScenes.Notification
        event is IslandEvent.AppSwitch -> NowBarEventScenes.AppSwitch
        else -> NowBarEventScenes.Default
    }

@Composable
private fun NowBarDots(count: Int, activeIndex: Int, color: Color) {
    val dotCount = count.coerceAtMost(5)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until dotCount) {
            val active = i == activeIndex.coerceIn(0, dotCount - 1) % dotCount
            Box(
                Modifier
                    .size(if (active) 5.dp else 4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 1f else AlphaHint))
            )
        }
    }
}
