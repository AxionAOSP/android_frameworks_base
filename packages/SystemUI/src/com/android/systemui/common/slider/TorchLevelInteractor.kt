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
    private val ctrl: FlashlightStrengthController
) : LevelSliderInteractor {

    companion object {
        private const val DEFAULT_PERCENT: Int = 50
    }
    
    override val spec: String = "torch"

    var lastPercent: Int
        get() = ctrl.lastPercent
        set(value) {
            ctrl.lastPercent = value
        }

    var torchLevel: Int
        get() = ctrl.torchLevel
        set(value) {
            ctrl.torchLevel = value
        }

    private fun calculateLevel(): Float {
        return if (ctrl.maxLevel > 0) {
            ctrl.torchLevel / ctrl.maxLevel.toFloat()
        } else {
            0f
        }
    }

    override val level: Flow<Float> = callbackFlow {
        trySend(calculateLevel())

        val listener = object : FlashlightStrengthController.OnTorchLevelChangedListener {
            override fun onLevelChanged(level: Int) {
                trySend(calculateLevel())
            }

            override fun onStatusChanged(enabled: Int) {
                trySend(calculateLevel())
            }
        }

        ctrl.addListener(listener)

        awaitClose { 
            ctrl.removeListener(listener) 
        }
    }.distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        if (!ctrl.supported) return 0f
        return calculateLevel()
    }

    override fun setLevel(level: Float) {
        if (!ctrl.supported) return
        val percent = (level * 100).roundToInt()
        torchLevel = ctrl.toTorchLevel(percent)
        lastPercent = percent
    }

    override fun onTap(enabled: Boolean) {
        if (enabled) {
            val percent = if (lastPercent != 0) {
                lastPercent
            } else DEFAULT_PERCENT
            torchLevel = ctrl.toTorchLevel(percent)
        } else {
            ctrl.torchOn = false
        }
    }

    override fun isActive(): Boolean {
        return ctrl.torchOn
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return when {
            level <= 0f || !ctrl.torchOn -> Icons.Filled.FlashlightOff
            else -> Icons.Filled.FlashlightOn
        }
    }
    
    @Composable
    override fun getLabel(level: Float): String {
        val percentage = (level * 100).toInt()
        return "Torch • $percentage%"
    }
}
