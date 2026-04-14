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

package com.android.systemui.qs.tiles.volume

import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.Prefs
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import kotlin.math.roundToInt

@SysUISingleton
class VolumeSliderInteractor @Inject constructor(
    private val audioManager: AudioManager,
    broadcastDispatcher: BroadcastDispatcher,
    @Application private val context: Context,
) {

    private val streamType = AudioManager.STREAM_MUSIC
    val maxVolume: Int = audioManager.getStreamMaxVolume(streamType)

    private val spec = "volume"
    private val prefsKey = "slider_enabled_$spec"

    private val _enabledFlow =
        MutableStateFlow(Prefs.getBoolean(context, prefsKey, false))
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    fun isActive(): Boolean = _enabledFlow.value

    fun onTap(enabled: Boolean) {
        if (_enabledFlow.value == enabled) return
        _enabledFlow.value = enabled
        Prefs.putBoolean(context, prefsKey, enabled)
    }

    fun currentLevel(): Float {
        val current = audioManager.getStreamVolume(streamType)
        return if (maxVolume > 0) current / maxVolume.toFloat() else 0f
    }

    fun setLevel(level: Float) {
        val volume = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(streamType, volume, 0)
    }

    fun iconForLevel(level: Float): ImageVector = when {
        level <= 0f -> Icons.Filled.VolumeOff
        level <= 0.33f -> Icons.Filled.VolumeMute
        level <= 0.66f -> Icons.Filled.VolumeDown
        else -> Icons.Filled.VolumeUp
    }

    fun labelForLevel(level: Float): String {
        val percentage = (level * 100).roundToInt()
        return "Volume \u2022 $percentage%"
    }

    val volumeChanges: Flow<Float> = broadcastDispatcher
        .broadcastFlow(IntentFilter(AudioManager.VOLUME_CHANGED_ACTION))
        .map { currentLevel() }
        .onStart { emit(currentLevel()) }
        .distinctUntilChanged { old, new ->
            (old * 100).toInt() == (new * 100).toInt()
        }
}
