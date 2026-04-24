package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.transitions
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import kotlinx.coroutines.delay
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

internal val ExpandedContentBottomScrollPadding = 110.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpandedIslandContent(
    events: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    onScrollableOverflowChanged: (Boolean) -> Unit = {},
    expandedFilter: String? = null,
    pinnedEventId: String? = null,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    if (events.isEmpty()) return

    val filteredEvents =
        remember(events, expandedFilter, pinnedEventId) {
            if (expandedFilter != null) {
                events.filter {
                    EVENT_TYPE_IDS[it::class.java] == expandedFilter
                }
            } else {

                val base = events.filter { it !is IslandEvent.Notification }
                val pinned = base.find { it.id == pinnedEventId }
                if (pinned != null) {
                    listOf(pinned) + base.filter { it.id != pinned.id }
                } else {
                    base
                }
            }
        }

    val notifIds =
        remember(filteredEvents) {
            filteredEvents.filterIsInstance<IslandEvent.Notification>().map { it.id }
        }
    LaunchedEffect(notifIds) { notifIds.forEach { interactor.onNotificationInteraction(it) } }
    DisposableEffect(notifIds) {
        onDispose { notifIds.forEach { interactor.onNotificationInteractionEnd(it) } }
    }

    LaunchedEffect(filteredEvents) {
        if (filteredEvents.isEmpty()) {
            
            delay(200)
            onCollapse()
        }
    }

    if (filteredEvents.isEmpty()) return

    val isNotificationFilter = expandedFilter == "notification"
    val notifGroups =
        remember(filteredEvents, isNotificationFilter) {
            if (!isNotificationFilter) return@remember emptyList()
            filteredEvents
                .filterIsInstance<IslandEvent.Notification>()
                .groupBy { it.sbn.packageName }
                .values
                .toList()
    }

    val expandedGroups = remember(expandedFilter) { mutableStateMapOf<String, Boolean>() }
    val motionScheme = MaterialTheme.motionScheme
    val itemFadeInSpec = motionScheme.defaultEffectsSpec<Float>()
    val itemPlacementSpec = motionScheme.defaultSpatialSpec<IntOffset>()
    val itemFadeOutSpec = motionScheme.fastEffectsSpec<Float>()
    val listState = rememberLazyListState()
    val hasScrollableOverflow =
        remember { derivedStateOf { listState.canScrollBackward || listState.canScrollForward } }

    LaunchedEffect(hasScrollableOverflow.value) {
        onScrollableOverflowChanged(hasScrollableOverflow.value)
    }

    LazyColumn(
        modifier =
            Modifier.widthIn(max = ExpandedMaxWidth)
                .wrapContentHeight(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
        contentPadding =
            PaddingValues(
                start = SpaceLg,
                end = SpaceLg,
                top = SpaceXxs,
                bottom = ExpandedContentBottomScrollPadding,
            ),
    ) {
        if (isNotificationFilter && notifGroups.isNotEmpty()) {
            notifGroups.forEach { group ->
                if (group.size == 1) {
                    val event = group.first()
                    item(key = event.id) {
                        MagneticSwipeToDismiss(
                            onDismiss = { interactor.dismissEvent(event) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = itemFadeInSpec,
                                placementSpec = itemPlacementSpec,
                                fadeOutSpec = itemFadeOutSpec,
                            ),
                        ) {
                            PrimaryCard { NotificationExpanded(event, interactor) }
                        }
                    }
                } else {
                    val pkg = group.first().sbn.packageName
                    val isExpanded = expandedGroups[pkg] == true
                    item(key = "group_$pkg") {
                        MagneticSwipeToDismiss(
                            onDismiss = { group.forEach { interactor.dismissEvent(it) } },
                            modifier = Modifier.animateItem(
                                fadeInSpec = itemFadeInSpec,
                                placementSpec = itemPlacementSpec,
                                fadeOutSpec = itemFadeOutSpec,
                            ),
                        ) {
                            PrimaryCard {
                                NotificationGroupCard(
                                    notifications = group,
                                    isExpanded = isExpanded,
                                    onToggleExpand = { expandedGroups[pkg] = !isExpanded },
                                    interactor = interactor,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredEvents, key = { it.id }) { event ->
                MagneticSwipeToDismiss(
                    onDismiss = { interactor.dismissEvent(event) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeInSpec,
                        placementSpec = itemPlacementSpec,
                        fadeOutSpec = itemFadeOutSpec,
                    ),
                ) {
                    if (event is IslandEvent.Media) {
                        MediaCard(event, interactor)
                    } else {
                        PrimaryCard {
                            ExpandedEventSceneContent(event, interactor, hapticsViewModelFactory)
                        }
                    }
                }
            }
        }
    }
}

private object ExpandedEventScenes {
    val AudioRecording = SceneKey("ax_dynamic_bar_expanded_audio_recording")
    val PromotedOngoing = SceneKey("ax_dynamic_bar_expanded_promoted_ongoing")
    val Sports = SceneKey("ax_dynamic_bar_expanded_sports")
    val NowPlaying = SceneKey("ax_dynamic_bar_expanded_now_playing")
    val Media = SceneKey("ax_dynamic_bar_expanded_media")
    val Bluetooth = SceneKey("ax_dynamic_bar_expanded_bluetooth")
    val Hotspot = SceneKey("ax_dynamic_bar_expanded_hotspot")
    val Charging = SceneKey("ax_dynamic_bar_expanded_charging")
    val Alarm = SceneKey("ax_dynamic_bar_expanded_alarm")
    val Timer = SceneKey("ax_dynamic_bar_expanded_timer")
    val Stopwatch = SceneKey("ax_dynamic_bar_expanded_stopwatch")
    val RingerMode = SceneKey("ax_dynamic_bar_expanded_ringer_mode")
    val Vpn = SceneKey("ax_dynamic_bar_expanded_vpn")
    val Clipboard = SceneKey("ax_dynamic_bar_expanded_clipboard")
    val Notification = SceneKey("ax_dynamic_bar_expanded_notification")
    val AppSwitch = SceneKey("ax_dynamic_bar_expanded_app_switch")
    val Torch = SceneKey("ax_dynamic_bar_expanded_torch")
    val BiometricUnlock = SceneKey("ax_dynamic_bar_expanded_biometric_unlock")
    val Empty = SceneKey("ax_dynamic_bar_expanded_empty")
    val All = listOf(
        AudioRecording,
        PromotedOngoing,
        Sports,
        NowPlaying,
        Media,
        Bluetooth,
        Hotspot,
        Charging,
        Alarm,
        Timer,
        Stopwatch,
        RingerMode,
        Vpn,
        Clipboard,
        Notification,
        AppSwitch,
        Torch,
        BiometricUnlock,
        Empty,
    )
}

private object ExpandedEventElements {
    val AudioRecordingContent = ElementKey("ax_dynamic_bar_expanded_audio_recording_content")
    val PromotedOngoingContent = ElementKey("ax_dynamic_bar_expanded_promoted_ongoing_content")
    val SportsContent = ElementKey("ax_dynamic_bar_expanded_sports_content")
    val NowPlayingContent = ElementKey("ax_dynamic_bar_expanded_now_playing_content")
    val MediaContent = ElementKey("ax_dynamic_bar_expanded_media_content")
    val BluetoothContent = ElementKey("ax_dynamic_bar_expanded_bluetooth_content")
    val HotspotContent = ElementKey("ax_dynamic_bar_expanded_hotspot_content")
    val ChargingContent = ElementKey("ax_dynamic_bar_expanded_charging_content")
    val AlarmContent = ElementKey("ax_dynamic_bar_expanded_alarm_content")
    val TimerContent = ElementKey("ax_dynamic_bar_expanded_timer_content")
    val StopwatchContent = ElementKey("ax_dynamic_bar_expanded_stopwatch_content")
    val RingerModeContent = ElementKey("ax_dynamic_bar_expanded_ringer_mode_content")
    val VpnContent = ElementKey("ax_dynamic_bar_expanded_vpn_content")
    val ClipboardContent = ElementKey("ax_dynamic_bar_expanded_clipboard_content")
    val NotificationContent = ElementKey("ax_dynamic_bar_expanded_notification_content")
    val AppSwitchContent = ElementKey("ax_dynamic_bar_expanded_app_switch_content")
    val TorchContent = ElementKey("ax_dynamic_bar_expanded_torch_content")
    val BiometricUnlockContent = ElementKey("ax_dynamic_bar_expanded_biometric_unlock_content")
    val EmptyContent = ElementKey("ax_dynamic_bar_expanded_empty_content")
    val All = listOf(
        AudioRecordingContent,
        PromotedOngoingContent,
        SportsContent,
        NowPlayingContent,
        MediaContent,
        BluetoothContent,
        HotspotContent,
        ChargingContent,
        AlarmContent,
        TimerContent,
        StopwatchContent,
        RingerModeContent,
        VpnContent,
        ClipboardContent,
        NotificationContent,
        AppSwitchContent,
        TorchContent,
        BiometricUnlockContent,
        EmptyContent,
    )
    val Content =
        object : ElementMatcher {
            override fun matches(key: ElementKey, content: ContentKey): Boolean = key in All
        }
}

private val ExpandedEventSceneElements = listOf(
    ExpandedEventScenes.AudioRecording to ExpandedEventElements.AudioRecordingContent,
    ExpandedEventScenes.PromotedOngoing to ExpandedEventElements.PromotedOngoingContent,
    ExpandedEventScenes.Sports to ExpandedEventElements.SportsContent,
    ExpandedEventScenes.NowPlaying to ExpandedEventElements.NowPlayingContent,
    ExpandedEventScenes.Media to ExpandedEventElements.MediaContent,
    ExpandedEventScenes.Bluetooth to ExpandedEventElements.BluetoothContent,
    ExpandedEventScenes.Hotspot to ExpandedEventElements.HotspotContent,
    ExpandedEventScenes.Charging to ExpandedEventElements.ChargingContent,
    ExpandedEventScenes.Alarm to ExpandedEventElements.AlarmContent,
    ExpandedEventScenes.Timer to ExpandedEventElements.TimerContent,
    ExpandedEventScenes.Stopwatch to ExpandedEventElements.StopwatchContent,
    ExpandedEventScenes.RingerMode to ExpandedEventElements.RingerModeContent,
    ExpandedEventScenes.Vpn to ExpandedEventElements.VpnContent,
    ExpandedEventScenes.Clipboard to ExpandedEventElements.ClipboardContent,
    ExpandedEventScenes.Notification to ExpandedEventElements.NotificationContent,
    ExpandedEventScenes.AppSwitch to ExpandedEventElements.AppSwitchContent,
    ExpandedEventScenes.Torch to ExpandedEventElements.TorchContent,
    ExpandedEventScenes.BiometricUnlock to ExpandedEventElements.BiometricUnlockContent,
    ExpandedEventScenes.Empty to ExpandedEventElements.EmptyContent,
)

private val ExpandedEventTransitions = transitions {
    ExpandedEventScenes.All.forEach { from(it) { expandedEventTransition() } }
}

private fun TransitionBuilder.expandedEventTransition() {
    spec = tween(durationMillis = 340, easing = FastOutSlowInEasing)
    fade(ExpandedEventElements.Content)
}

@Composable
private fun ExpandedEventSceneContent(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    val targetScene = expandedEventSceneFor(event)
    val sceneState = rememberDynamicBarEventSceneState(targetScene, event, ExpandedEventTransitions)

    NoOpBackDispatcherOwner {
        SceneTransitionLayout(state = sceneState.layoutState, modifier = Modifier.fillMaxWidth()) {
            ExpandedEventSceneElements.forEach { (sceneKey, elementKey) ->
                scene(sceneKey) {
                    sceneState.eventFor(sceneKey)?.let { sceneEvent ->
                        Box(Modifier.fillMaxWidth().element(elementKey)) {
                            ExpandedEventContent(sceneEvent, interactor, hapticsViewModelFactory)
                        }
                    }
                }
            }
        }
    }
}

private fun expandedEventSceneFor(event: IslandEvent): SceneKey =
    when (event) {
        is IslandEvent.AudioRecording -> ExpandedEventScenes.AudioRecording
        is IslandEvent.PromotedOngoing -> ExpandedEventScenes.PromotedOngoing
        is IslandEvent.Sports -> ExpandedEventScenes.Sports
        is IslandEvent.NowPlaying -> ExpandedEventScenes.NowPlaying
        is IslandEvent.Media -> ExpandedEventScenes.Media
        is IslandEvent.Bluetooth -> ExpandedEventScenes.Bluetooth
        is IslandEvent.Hotspot -> ExpandedEventScenes.Hotspot
        is IslandEvent.Charging -> ExpandedEventScenes.Charging
        is IslandEvent.Alarm -> ExpandedEventScenes.Alarm
        is IslandEvent.Timer -> ExpandedEventScenes.Timer
        is IslandEvent.Stopwatch -> ExpandedEventScenes.Stopwatch
        is IslandEvent.RingerMode -> ExpandedEventScenes.RingerMode
        is IslandEvent.Vpn -> ExpandedEventScenes.Vpn
        is IslandEvent.Clipboard -> ExpandedEventScenes.Clipboard
        is IslandEvent.Notification -> ExpandedEventScenes.Notification
        is IslandEvent.AppSwitch -> ExpandedEventScenes.AppSwitch
        is IslandEvent.Torch -> ExpandedEventScenes.Torch
        is IslandEvent.BiometricUnlock -> ExpandedEventScenes.BiometricUnlock
        is IslandEvent.KeyguardIndication -> ExpandedEventScenes.Empty
        is IslandEvent.AospChip -> ExpandedEventScenes.Empty
    }

@Composable
internal fun ExpandedEventContent(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    when (event) {
        is IslandEvent.AudioRecording -> AudioRecordingExpanded(event, interactor)
        is IslandEvent.PromotedOngoing -> PromotedOngoingExpanded(event, interactor)
        is IslandEvent.Sports -> SportsExpanded(event, interactor)
        is IslandEvent.NowPlaying -> NowPlayingExpanded(event, interactor)
        is IslandEvent.Media -> MediaExpanded(event, interactor)
        is IslandEvent.Bluetooth -> BluetoothExpanded(event, interactor)
        is IslandEvent.Hotspot -> HotspotExpanded(event)
        is IslandEvent.Charging -> ChargingExpanded(event)
        is IslandEvent.Alarm -> AlarmExpanded(event, interactor)
        is IslandEvent.Timer -> TimerExpanded(event, interactor)
        is IslandEvent.Stopwatch -> StopwatchExpanded(event, interactor)
        is IslandEvent.RingerMode -> RingerModeExpanded(event, interactor)
        is IslandEvent.Vpn -> VpnExpanded(event)
        is IslandEvent.Clipboard -> ClipboardExpanded(event, interactor)
        is IslandEvent.Notification -> NotificationExpanded(event, interactor)
        is IslandEvent.AppSwitch -> AppHistoryExpanded(event, interactor)
        is IslandEvent.Torch -> TorchExpanded(event, interactor, hapticsViewModelFactory)
        is IslandEvent.BiometricUnlock -> BiometricUnlockExpanded(event)
        is IslandEvent.KeyguardIndication -> {}
        is IslandEvent.AospChip -> {}
    }
}

@Composable
internal fun BiometricUnlockExpanded(event: IslandEvent.BiometricUnlock) {
    ExpandedCardLayout(
        accentColor = GreenAccent,
        icon = { Icon(Icons.Filled.Check, null, tint = GreenAccent, modifier = Modifier.size(26.dp)) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_device_unlocked), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            Text(event.sourceName, color = SubtleGray, style = MaterialTheme.typography.labelMedium)
        },
    )
}

@Composable
internal fun PrimaryCard(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(ShapeCard)
                .background(CardBg)
                .border(1.dp, CardBorderBrush, ShapeCard)
                .padding(SpaceXxl)
    ) {
        content()
    }
}
