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
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

class VolumeInteractor(
    private val context: Context,
    private val audioManager: AudioManager,
    private val streamType: Int = AudioManager.STREAM_MUSIC
) : LevelSliderInteractor {

    private val maxVolume = audioManager.getStreamMaxVolume(streamType)
    
    var enabled = false
    
    override val spec: String = "volume"

    override val level: Flow<Float> = callbackFlow {
        trySend(getCurrentVolume())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (stream == streamType) {
                        trySend(getCurrentVolume())
                    }
                }
            }
        }

        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(receiver, filter)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float = getCurrentVolume()

    override fun setLevel(level: Float) {
        val volume = (level * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(streamType, volume, 0)
    }

    override fun isActive(): Boolean {
        return enabled
    }

    override fun onTap(enabled: Boolean) {
        this.enabled = enabled
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return when {
            level <= 0f -> Icons.Filled.VolumeOff
            level <= 0.33f -> Icons.Filled.VolumeMute
            level <= 0.66f -> Icons.Filled.VolumeDown
            else -> Icons.Filled.VolumeUp
        }
    }

    @Composable
    override fun getLabel(level: Float): String {
        val percentage = (level * 100).roundToInt()
        return "Volume • $percentage%"
    }

    private fun getCurrentVolume(): Float {
        val currentVolume = audioManager.getStreamVolume(streamType)
        return if (maxVolume > 0) currentVolume / maxVolume.toFloat() else 0f
    }
}
