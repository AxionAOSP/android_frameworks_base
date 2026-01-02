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

package com.android.systemui.axion.volume.domain.interactor

import android.media.AudioManager
import android.os.Vibrator
import com.android.systemui.axion.volume.dagger.AxionVolumeScope
import com.android.systemui.axion.volume.repository.AxionCaptionsRepository
import com.android.systemui.axion.volume.repository.AxionVolumeRepository
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.volume.domain.interactor.VolumePanelNavigationInteractor
import com.android.systemui.volume.ui.navigation.VolumeNavigator
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

interface AxionVolumeDialogInteractor {
    val volumeDialogState: Flow<AxionVolumeDialogState>
    fun setVolume(streamType: Int, level: Float)
    fun setActiveStream(streamType: Int)
    fun setRingerMode(mode: AxionRingerMode)
    fun toggleMute(streamType: Int)
    fun toggleCaptions()
    fun setAppVolume(packageName: String, volume: Float)
    fun setAppMute(packageName: String, muted: Boolean)
    fun getSupportedRingerModes(): List<AxionRingerMode>
    fun openVolumePanel()
    fun rescheduleTimeout()
    fun setExpanded(expanded: Boolean)
    val isLeftSide: Flow<Boolean>
}

@OptIn(ExperimentalCoroutinesApi::class)
@AxionVolumeScope
class AxionVolumeDialogInteractorImpl @Inject constructor(
    private val volumeRepository: AxionVolumeRepository,
    private val captionsRepository: AxionCaptionsRepository,
    private val vibrator: Vibrator?,
    private val volumeNavigator: VolumeNavigator,
    private val volumePanelNavigationInteractor: VolumePanelNavigationInteractor,
) : AxionVolumeDialogInteractor {

    @Suppress("UNCHECKED_CAST")
    override val volumeDialogState: Flow<AxionVolumeDialogState> = combine(
        ringerModeFlow(),
        volumeStreamsFlow(),
        captionsRepository.captionsEnabledFlow,
        captionsRepository.captionsAvailableFlow,
        volumeRepository.activeAppVolumes,
        volumeRepository.activeStreamFlow,
        volumeRepository.activeAppPackageName
    ) { args: Array<Any?> ->
        val ringerMode = args[0] as AxionRingerMode
        val streams = args[1] as List<AxionVolumeStreamModel>
        val captionsEnabled = args[2] as Boolean
        val captionsAvailable = args[3] as Boolean
        val appVolumes = args[4] as List<AxionAppVolumeModel>
        val activeStream = args[5] as Int
        val activeAppPackageName = args[6] as String?

        AxionVolumeDialogState(
            ringerMode = ringerMode,
            volumeStreams = streams,
            captionsEnabled = captionsEnabled,
            captionsAvailable = captionsAvailable,
            supportedRingerModes = getSupportedRingerModes(),
            appVolumes = appVolumes,
            activeStream = activeStream,
            activeAppPackageName = activeAppPackageName
        )
    }

    private fun ringerModeFlow(): Flow<AxionRingerMode> =
        volumeRepository.ringerModeFlow.map { mode ->
            when (mode) {
                AudioManager.RINGER_MODE_NORMAL -> AxionRingerMode.NORMAL
                AudioManager.RINGER_MODE_VIBRATE -> AxionRingerMode.VIBRATE
                AudioManager.RINGER_MODE_SILENT -> AxionRingerMode.SILENT
                else -> AxionRingerMode.NORMAL
            }
        }

    private fun volumeStreamsFlow(): Flow<List<AxionVolumeStreamModel>> =
        volumeRepository.availableStreamsFlow.flatMapLatest { streams ->
            if (streams.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(streams.map { volumeStreamFlow(it) }) { it.toList() }
            }
        }

    private fun volumeStreamFlow(streamType: Int): Flow<AxionVolumeStreamModel> = combine(
        volumeRepository.volumeFlow(streamType),
        volumeRepository.muteFlow(streamType),
    ) { level, isMuted ->
        AxionVolumeStreamModel(
            streamType = streamType,
            level = level,
            isMuted = isMuted,
            maxLevel = volumeRepository.getMaxVolume(streamType),
            minLevel = volumeRepository.getMinVolume(streamType),
        )
    }

    override fun setVolume(streamType: Int, level: Float) {
        volumeRepository.setVolume(streamType, level)
    }

    override fun setActiveStream(streamType: Int) {
        volumeRepository.setActiveStream(streamType)
        volumeRepository.setActiveApp(null)
    }

    override fun setRingerMode(mode: AxionRingerMode) {
        val audioMode = when (mode) {
            AxionRingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            AxionRingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            AxionRingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
        volumeRepository.setRingerMode(audioMode)
    }

    override fun toggleMute(streamType: Int) {
        val currentlyMuted = volumeRepository.isStreamMuted(streamType)
        volumeRepository.setMute(streamType, !currentlyMuted)
    }

    override fun toggleCaptions() {
        val currentEnabled = captionsRepository.isCaptionsEnabled()
        captionsRepository.setCaptionsEnabled(!currentEnabled)
    }

    override fun setAppVolume(packageName: String, volume: Float) {
        volumeRepository.setAppVolume(packageName, volume)
        volumeRepository.setActiveApp(packageName)
    }

    override fun setAppMute(packageName: String, muted: Boolean) {
        volumeRepository.setAppMute(packageName, muted)
    }

    override fun getSupportedRingerModes(): List<AxionRingerMode> {
        val modes = mutableListOf(AxionRingerMode.NORMAL)
        if (vibrator?.hasVibrator() == true) {
            modes.add(AxionRingerMode.VIBRATE)
        }
        modes.add(AxionRingerMode.SILENT)
        return modes
    }

    override fun openVolumePanel() {
        volumeNavigator.openVolumePanel(
            volumePanelNavigationInteractor.getVolumePanelRoute()
        )
    }

    override fun rescheduleTimeout() {
        volumeRepository.userActivity()
    }
    
    override val isLeftSide: Flow<Boolean> = volumeRepository.isLeftSideFlow
    override fun setExpanded(expanded: Boolean) {
        volumeRepository.setExpanded(expanded)
    }
}
