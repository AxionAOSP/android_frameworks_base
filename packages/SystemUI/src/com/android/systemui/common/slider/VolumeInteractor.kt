/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.common.slider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.dagger.SysUISingleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import kotlin.math.roundToInt

@Stable
@SysUISingleton
class VolumeInteractor @Inject constructor(
    private val context: Context
) : LevelSliderInteractor {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streamType: Int = AudioManager.STREAM_MUSIC
    private val maxVolume = audioManager.getStreamMaxVolume(streamType)

    override val spec: String = "volume"

    private val _isActiveFlow = MutableStateFlow(false)
    override val isActiveFlow: StateFlow<Boolean> = _isActiveFlow.asStateFlow()

    private val _volumeLevel = MutableStateFlow(getCurrentVolume())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (stream == streamType) {
                    _volumeLevel.value = getCurrentVolume()
                }
            }
        }
    }

    init {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(receiver, filter)
    }

    override val level: Flow<Float> = _volumeLevel

    override fun getCurrentLevel(): Float = getCurrentVolume()

    override fun setLevel(level: Float) {
        val volume = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(streamType, volume, 0)
        _volumeLevel.value = level
    }

    override fun isActive(): Boolean = _isActiveFlow.value

    override fun onTap(enabled: Boolean) {
        _isActiveFlow.value = enabled
        _volumeLevel.value = getCurrentVolume()
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        val actualLevel = if (_isActiveFlow.value) _volumeLevel.value else 0f
        return when {
            !_isActiveFlow.value -> Icons.Filled.VolumeOff
            actualLevel <= 0f -> Icons.Filled.VolumeOff
            actualLevel <= 0.33f -> Icons.Filled.VolumeMute
            actualLevel <= 0.66f -> Icons.Filled.VolumeDown
            else -> Icons.Filled.VolumeUp
        }
    }

    @Composable
    override fun getLabel(level: Float): String {
        val displayPercent = (_volumeLevel.value * 100).roundToInt()
        return "Volume • $displayPercent%"
    }

    private fun getCurrentVolume(): Float {
        val currentVolume = audioManager.getStreamVolume(streamType)
        return if (maxVolume > 0) currentVolume / maxVolume.toFloat() else 0f
    }
}
