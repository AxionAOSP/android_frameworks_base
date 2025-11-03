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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.statusbar.policy.FlashlightStrengthController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

class TorchLevelInteractor(
    private val flashlightController: FlashlightStrengthController
) : LevelSliderInteractor {
    
    override val level: Flow<Float> = callbackFlow {
        trySend(getCurrentLevel())
        
        val listener = object : FlashlightStrengthController.OnTorchLevelChangedListener {
            override fun onLevelChanged(level: Int) {
                val normalized = if (flashlightController.maxLevel > 0) {
                    level / flashlightController.maxLevel.toFloat()
                } else {
                    0f
                }
                trySend(normalized)
            }
            
            override fun onStatusChanged(enabled: Int) {
                if (enabled == 0) {
                    trySend(0f)
                }
            }
        }
        
        flashlightController.addListener(listener)
        
        awaitClose {
            flashlightController.removeListener(listener)
        }
    }.distinctUntilChanged()
    
    override fun getCurrentLevel(): Float {
        if (!flashlightController.supported) return 0f
        
        val currentLevel = flashlightController.torchLevel
        return if (flashlightController.maxLevel > 0) {
            currentLevel / flashlightController.maxLevel.toFloat()
        } else {
            0f
        }
    }
    
    override fun setLevel(level: Float) {
        if (!flashlightController.supported) return
        
        val torchLevel = (level * flashlightController.maxLevel)
            .roundToInt()
            .coerceIn(0, flashlightController.maxLevel)
        
        flashlightController.torchLevel = torchLevel
        
        if (torchLevel > 0) {
            flashlightController.lastPercent = (level * 100).roundToInt()
        }
    }
    
    @Composable
    override fun getIcon(level: Float): ImageVector {
        return when {
            level <= 0f -> Icons.Filled.FlashlightOff
            else -> Icons.Filled.FlashlightOn
        }
    }
    
    @Composable
    override fun getLabel(level: Float): String {
        val percentage = (level * 100).roundToInt()
        return "Torch • $percentage%"
    }
}
