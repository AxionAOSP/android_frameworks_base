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

package com.android.systemui.axion.volume.ui.viewmodel

import android.media.AudioManager
import android.os.SystemClock
import com.android.systemui.axion.volume.dagger.AxionVolumeDialogScope
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.axion.volume.domain.interactor.AxionVolumeDialogInteractor
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.composable.MaxVisibleSliders
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val VOLUME_UPDATE_GRACE_PERIOD = 1000L

enum class VisibilityState {
    DISMISSED,
    SHOWING,
    QUICK_DISMISSED
}

enum class ExpansionState {
    COLLAPSED,
    EXPANDED
}

data class AxionVolumeDialogUiState(
    val dialogState: AxionVolumeDialogState = AxionVolumeDialogState(),
    val visibilityState: VisibilityState = VisibilityState.DISMISSED,
    val expansionState: ExpansionState = ExpansionState.COLLAPSED,
    val isLeftSide: Boolean = false,
    val isInteracting: Boolean = false,
    val showingAppVolumes: Boolean = false
) {
    val shouldAnimateExpansion: Boolean
        get() = visibilityState == VisibilityState.SHOWING

    val isVisible: Boolean
        get() = visibilityState == VisibilityState.SHOWING

    val isExpanded: Boolean
        get() = expansionState == ExpansionState.EXPANDED
}

@AxionVolumeScope
class AxionVolumeDialogViewModel @Inject constructor(
    private val interactor: AxionVolumeDialogInteractor,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
    @AxionVolumeDialogScope private val scope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
) {
    private val _visibilityState = MutableStateFlow(VisibilityState.DISMISSED)
    private val _expansionState = MutableStateFlow(ExpansionState.COLLAPSED)
    private val _isInteracting = MutableStateFlow(false)
    private val _showingAppVolumes = MutableStateFlow(false)
    private val _overscrollOffset = MutableStateFlow(0f)
    private val _volumeKeyHapticTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _rescheduleTimeoutTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val volumeEvents = MutableStateFlow<VolumeEvent?>(null)
    private val appVolumeEvents = MutableStateFlow<AppVolumeEvent?>(null)

    val uiState: StateFlow<AxionVolumeDialogUiState> = combine(
        interactor.volumeDialogState,
        _visibilityState,
        _expansionState,
        interactor.isLeftSide,
        _isInteracting,
        _showingAppVolumes,
        volumeEvents,
        appVolumeEvents,
    ) { flows ->
        val dialogState = (flows[0] as AxionVolumeDialogState)
            .withUserVolumeUpdate(flows[6] as VolumeEvent?)
            .withUserAppVolumeUpdate(flows[7] as AppVolumeEvent?)
        val visibilityState = flows[1] as VisibilityState
        val expansionState = flows[2] as ExpansionState
        val isLeftSide = flows[3] as Boolean
        val isInteracting = flows[4] as Boolean
        val showingAppVolumes = flows[5] as Boolean

        AxionVolumeDialogUiState(
            dialogState = dialogState,
            visibilityState = visibilityState,
            expansionState = expansionState,
            isLeftSide = isLeftSide,
            isInteracting = isInteracting,
            showingAppVolumes = showingAppVolumes,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AxionVolumeDialogUiState(),
    )

    private val streamOrder = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_BLUETOOTH_SCO,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM,
    )

    private val lockedStreamOrder = mutableListOf<Int>()
    private val lockedStreamModels = mutableMapOf<Int, AxionVolumeStreamModel>()
    private val _currStreamCount = MutableStateFlow(0)
    val currStreamCount: StateFlow<Int> = _currStreamCount.asStateFlow()

    val sliderItems: StateFlow<List<VolumeSliderItem>> = uiState
        .map { state ->
            val dialogState = state.dialogState
            val isLeft = state.isLeftSide
            val currentStreams = dialogState.volumeStreams.associateBy { it.streamType }

            if (state.isExpanded && lockedStreamOrder.isEmpty()) {
                val sorted = currentStreams.keys.sortedBy { streamType ->
                    val idx = streamOrder.indexOf(streamType)
                    if (idx >= 0) idx else streamOrder.size
                }
                lockedStreamOrder.addAll(sorted)
                lockedStreamModels.putAll(currentStreams)
            }

            if (lockedStreamOrder.isNotEmpty()) {
                currentStreams.values.forEach { lockedStreamModels[it.streamType] = it }
            }

            val sourceOrder = if (lockedStreamOrder.isNotEmpty()) lockedStreamOrder else
                currentStreams.keys.sortedBy { streamOrder.indexOf(it).let { i -> if (i >= 0) i else streamOrder.size } }

            val orderedStreams = sourceOrder
                .mapNotNull { currentStreams[it] ?: lockedStreamModels[it] }
                .map { VolumeSliderItem.Stream(it) }

            val newCount = orderedStreams.size.coerceIn(1, MaxVisibleSliders)
            if (newCount > _currStreamCount.value) _currStreamCount.value = newCount

            val appVolumeItems = dialogState.appVolumes.map { VolumeSliderItem.AppVolume(it) }

            if (isLeft) orderedStreams + appVolumeItems
            else appVolumeItems.reversed() + orderedStreams.reversed()
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sliderCount: StateFlow<Int> = sliderItems
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)

    var visibilityState: VisibilityState
        get() = _visibilityState.value
        set(value) {
            _visibilityState.value = value
            interactor.setShowing(value == VisibilityState.SHOWING)
        }

    var expansionState: ExpansionState
        get() = _expansionState.value
        set(value) {
            _expansionState.value = value
            interactor.setExpanded(value == ExpansionState.EXPANDED)
        }

    var isInteracting: Boolean
        get() = _isInteracting.value
        set(value) { _isInteracting.value = value }

    val overscrollOffset: StateFlow<Float> = _overscrollOffset.asStateFlow()
    val volumeKeyHapticTrigger: SharedFlow<Unit> = _volumeKeyHapticTrigger.asSharedFlow()
    val rescheduleTimeoutTrigger: SharedFlow<Unit> = _rescheduleTimeoutTrigger.asSharedFlow()

    private val _dismissAnimationEnd = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dismissAnimationEnd: SharedFlow<Unit> = _dismissAnimationEnd.asSharedFlow()

    fun onDismissAnimationEnd() {
        _dismissAnimationEnd.tryEmit(Unit)
    }

    fun setOverscrollOffset(offset: Float) {
        _overscrollOffset.value = offset
    }

    fun resetState() {
        expansionState = ExpansionState.COLLAPSED
        _showingAppVolumes.value = false
        lockedStreamOrder.clear()
        lockedStreamModels.clear()
        _currStreamCount.value = 0
    }

    fun collapseIfExpanded() {
        if (expansionState == ExpansionState.EXPANDED) {
            expansionState = ExpansionState.COLLAPSED
        }
    }

    fun triggerVolumeKeyHaptic() {
        _volumeKeyHapticTrigger.tryEmit(Unit)
    }

    fun toggleExpanded() {
        expansionState = when (expansionState) {
            ExpansionState.COLLAPSED -> ExpansionState.EXPANDED
            ExpansionState.EXPANDED -> ExpansionState.COLLAPSED
        }
    }

    fun toggleVolumeView() {
        _showingAppVolumes.value = !_showingAppVolumes.value
    }

    init {
        volumeEvents
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                old.streamType == new.streamType && old.level == new.level
            }
            .onEach { event ->
                withContext(bgDispatcher) { interactor.setVolume(event.streamType, event.level) }
            }
            .launchIn(scope)

        appVolumeEvents
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                old.packageName == new.packageName && old.volume == new.volume
            }
            .onEach { event ->
                withContext(bgDispatcher) { interactor.setAppVolume(event.packageName, event.volume) }
            }
            .launchIn(scope)
    }

    fun setVolume(streamType: Int, level: Float) {
        volumeEvents.value = VolumeEvent(
            streamType,
            streamLevel(streamType, level),
            SystemClock.uptimeMillis()
        )
    }

    fun setActiveStream(streamType: Int) {
        interactor.setActiveStream(streamType)
    }

    fun setRingerMode(mode: AxionRingerMode) {
        scope.launch(bgDispatcher) { interactor.setRingerMode(mode) }
    }

    fun rescheduleTimeout() {
        interactor.rescheduleTimeout()
        _rescheduleTimeoutTrigger.tryEmit(Unit)
    }

    fun toggleMute(streamType: Int) {
        scope.launch(bgDispatcher) { interactor.toggleMute(streamType) }
    }

    fun toggleCaptions() {
        scope.launch(bgDispatcher) { interactor.toggleCaptions() }
    }

    fun setAppVolume(packageName: String, volume: Float) {
        appVolumeEvents.value = AppVolumeEvent(packageName, volume, SystemClock.uptimeMillis())
    }

    fun setAppMute(packageName: String, muted: Boolean) {
        scope.launch(bgDispatcher) { interactor.setAppMute(packageName, muted) }
    }

    fun onSeeMoreClick() {
        visibilityState = VisibilityState.QUICK_DISMISSED
        interactor.openVolumePanel()
    }

    fun cycleRingerMode() {
        val currentState = uiState.value.dialogState
        val supportedModes = currentState.supportedRingerModes
        if (supportedModes.isEmpty()) return

        val currentIndex = supportedModes.indexOf(currentState.ringerMode)
        val nextIndex = (currentIndex + 1) % supportedModes.size
        interactor.setRingerMode(supportedModes[nextIndex])
    }

    private fun AxionVolumeDialogState.withUserVolumeUpdate(
        event: VolumeEvent?
    ): AxionVolumeDialogState {
        if (event == null || !event.isFresh()) return this
        return copy(
            volumeStreams = volumeStreams.map { stream ->
                if (stream.streamType == event.streamType) {
                    stream.copy(level = streamLevelFraction(stream, event.level))
                } else {
                    stream
                }
            }
        )
    }

    private fun AxionVolumeDialogState.withUserAppVolumeUpdate(
        event: AppVolumeEvent?
    ): AxionVolumeDialogState {
        if (event == null || !event.isFresh()) return this
        return copy(
            appVolumes = appVolumes.map { appVolume ->
                if (appVolume.packageName == event.packageName) {
                    appVolume.copy(volume = event.volume)
                } else {
                    appVolume
                }
            }
        )
    }

    private fun VolumeEvent.isFresh(): Boolean =
        SystemClock.uptimeMillis() - timestampMillis < VOLUME_UPDATE_GRACE_PERIOD

    private fun AppVolumeEvent.isFresh(): Boolean =
        SystemClock.uptimeMillis() - timestampMillis < VOLUME_UPDATE_GRACE_PERIOD

    private fun streamLevel(streamType: Int, level: Float): Int {
        val stream = uiState.value.dialogState.volumeStreams.find { it.streamType == streamType }
            ?: return level.roundToInt()
        return level
            .roundToInt()
            .coerceIn(stream.minLevel, stream.maxLevel)
    }

    private fun streamLevelFraction(stream: AxionVolumeStreamModel, level: Int): Float {
        val range = stream.maxLevel - stream.minLevel
        if (range <= 0) return 0f
        val volume = level.coerceIn(stream.minLevel, stream.maxLevel)
        return (volume - stream.minLevel).toFloat() / range
    }

    private data class VolumeEvent(
        val streamType: Int,
        val level: Int,
        val timestampMillis: Long,
    )

    private data class AppVolumeEvent(
        val packageName: String,
        val volume: Float,
        val timestampMillis: Long,
    )
}
