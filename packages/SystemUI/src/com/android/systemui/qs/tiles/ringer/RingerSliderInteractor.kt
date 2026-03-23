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

package com.android.systemui.qs.tiles.ringer

import android.app.NotificationManager
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Vibrator
import android.provider.Settings.Global
import android.service.notification.ZenModeConfig
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.ZenModeController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import kotlin.math.roundToInt

@Stable
data class RingerModeOption(
    val mode: Int,
    val icon: ImageVector,
)

@SysUISingleton
class RingerSliderInteractor @Inject constructor(
    private val audioManager: AudioManager,
    broadcastDispatcher: BroadcastDispatcher,
    context: Context,
    zenModeController: ZenModeController,
) {

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val hasVibrator = vibrator?.hasVibrator() == true

    val availableModes: List<RingerModeOption> = buildList {
        add(RingerModeOption(AudioManager.RINGER_MODE_NORMAL, Icons.Filled.VolumeUp))
        if (hasVibrator) {
            add(RingerModeOption(AudioManager.RINGER_MODE_VIBRATE, Icons.Filled.Vibration))
        }
        add(RingerModeOption(AudioManager.RINGER_MODE_SILENT, Icons.Filled.VolumeOff))
    }

    val numModes: Int = availableModes.size

    val maxOffset: Float = (numModes - 1).coerceAtLeast(1).toFloat()

    val currentMode: Int get() = audioManager.ringerModeInternal

    fun setRingerMode(mode: Int) {
        audioManager.ringerModeInternal = mode
    }

    fun targetPosition(ringerMode: Int): Float {
        val idx = availableModes.indexOfFirst { it.mode == ringerMode }
            .takeIf { it >= 0 } ?: 0
        return idx.toFloat()
    }

    fun snapToIndex(offset: Float): Int =
        offset.roundToInt().coerceIn(0, numModes - 1)

    val ringerModeChanges: Flow<Int> = broadcastDispatcher
        .broadcastFlow(IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION))
        .map { audioManager.ringerModeInternal }
        .onStart { emit(audioManager.ringerModeInternal) }

    private val _isZenMuted = MutableStateFlow(computeIsZenMuted(zenModeController))

    val isZenMuted: StateFlow<Boolean> = _isZenMuted.asStateFlow()

    init {
        zenModeController.addCallback(object : ZenModeController.Callback {
            override fun onZenChanged(zen: Int) {
                _isZenMuted.value = computeIsZenMuted(zenModeController)
            }

            override fun onConsolidatedPolicyChanged(policy: NotificationManager.Policy) {
                _isZenMuted.value = computeIsZenMuted(zenModeController)
            }
        })
    }

    private fun computeIsZenMuted(controller: ZenModeController): Boolean {
        val zen = controller.zen
        val disallowRinger = controller.consolidatedPolicy?.let {
            ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(it)
        } ?: false
        return zen == Global.ZEN_MODE_ALARMS
            || zen == Global.ZEN_MODE_NO_INTERRUPTIONS
            || (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS && disallowRinger)
    }
}
