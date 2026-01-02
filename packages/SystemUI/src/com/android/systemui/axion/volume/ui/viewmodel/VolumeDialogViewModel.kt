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

import com.android.systemui.axion.volume.AxionVolumeDialog
import com.android.systemui.axion.volume.dagger.AxionVolumeDialogScope
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.axion.volume.domain.interactor.AxionVolumeDialogInteractor
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
) {
    private val _visibilityState = MutableStateFlow(VisibilityState.DISMISSED)
    private val _expansionState = MutableStateFlow(ExpansionState.COLLAPSED)
    private val _isInteracting = MutableStateFlow(false)
    private val _showingAppVolumes = MutableStateFlow(false)
    private val _overscrollOffset = MutableStateFlow(0f)
    private val _volumeKeyHapticTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _rescheduleTimeoutTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val uiState: StateFlow<AxionVolumeDialogUiState> = combine(
        interactor.volumeDialogState,
        _visibilityState,
        _expansionState,
        interactor.isLeftSide,
        _isInteracting,
        _showingAppVolumes,
    ) { flows ->
        val dialogState = flows[0] as AxionVolumeDialogState
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
    
    var visibilityState: VisibilityState
        get() = _visibilityState.value
        set(value) {
            _visibilityState.value = value
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
    
    fun setOverscrollOffset(offset: Float) {
        _overscrollOffset.value = offset
    }

    fun resetState() {
        expansionState = ExpansionState.COLLAPSED
        _showingAppVolumes.value = false
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

    fun setVolume(streamType: Int, level: Float) {
        interactor.setVolume(streamType, level)
    }

    fun setActiveStream(streamType: Int) {
        interactor.setActiveStream(streamType)
    }
    
    fun setRingerMode(mode: AxionRingerMode) {
        interactor.setRingerMode(mode)
    }

    fun rescheduleTimeout() {
        interactor.rescheduleTimeout()
        _rescheduleTimeoutTrigger.tryEmit(Unit)
    }

    fun toggleMute(streamType: Int) {
        interactor.interact {
            toggleMute(streamType)
        }
    }

    fun toggleCaptions() {
        interactor.interact {
            toggleCaptions()
        }
    }

    fun setAppVolume(packageName: String, volume: Float) {
        interactor.setAppVolume(packageName, volume)
    }

    fun setAppMute(packageName: String, muted: Boolean) {
        interactor.interact {
            setAppMute(packageName, muted)
        }
    }

    fun onSeeMoreClick() {
        visibilityState = VisibilityState.QUICK_DISMISSED
        interactor.openVolumePanel()
    }
    
    fun cycleRingerMode() {
        interactor.interact {
            val currentState = uiState.value.dialogState
            val supportedModes = currentState.supportedRingerModes
            if (supportedModes.isEmpty()) return

            val currentIndex = supportedModes.indexOf(currentState.ringerMode)
            val nextIndex = (currentIndex + 1) % supportedModes.size
            val nextMode = supportedModes[nextIndex]
            
            setRingerMode(nextMode)
        }
    }

    private inline fun AxionVolumeDialogInteractor.interact(action: AxionVolumeDialogInteractor.() -> Unit) {
        _isInteracting.value = true
        try {
            action()
        } finally {
            _isInteracting.value = false
        }
    }
}
