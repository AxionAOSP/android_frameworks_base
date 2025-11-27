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
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.FlashlightStrengthController
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
class TorchLevelInteractor @Inject constructor(
    private val ctrl: FlashlightStrengthController
) : LevelSliderInteractor {

    companion object {
        private const val DEFAULT_PERCENT: Int = 50
    }

    override val spec: String = "torch"
    
    val isSupported: Boolean get() = ctrl.supported

    private data class TorchState(
        val isOn: Boolean,
        val levelPercent: Int
    )

    private val _state = MutableStateFlow(
        TorchState(
            isOn = ctrl.torchOn,
            levelPercent = if (ctrl.torchOn && ctrl.torchLevel > 0 && ctrl.maxLevel > 0) {
                ((ctrl.torchLevel / ctrl.maxLevel.toFloat()) * 100).roundToInt()
            } else {
                ctrl.lastPercent.takeIf { it > 0 } ?: DEFAULT_PERCENT
            }
        )
    )

    private val _isActiveFlow = MutableStateFlow(ctrl.torchOn)
    override val isActiveFlow: StateFlow<Boolean> = _isActiveFlow.asStateFlow()

    private val listener = object : FlashlightStrengthController.OnTorchLevelChangedListener {
        override fun onLevelChanged(level: Int) {
            if (level > 0 && ctrl.maxLevel > 0) {
                val percent = ((level / ctrl.maxLevel.toFloat()) * 100).roundToInt()
                _state.value = _state.value.copy(levelPercent = percent)
            }
        }

        override fun onStatusChanged(enabled: Int) {
            val isOn = enabled != 0
            _state.value = _state.value.copy(isOn = isOn)
            _isActiveFlow.value = isOn
        }
    }

    init {
        ctrl.addListener(listener)
    }

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

    override val level: Flow<Float> = _state
        .map { state -> 
            if (state.isOn) state.levelPercent / 100f else 0f
        }
        .distinctUntilChanged()

    override fun getCurrentLevel(): Float {
        if (!ctrl.supported) return 0f
        val state = _state.value
        return if (state.isOn) state.levelPercent / 100f else 0f
    }

    override fun setLevel(level: Float) {
        if (!ctrl.supported) return
        val percent = (level * 100).roundToInt()
        _state.value = _state.value.copy(levelPercent = percent)
        torchLevel = ctrl.toTorchLevel(percent)
        lastPercent = percent
    }

    override fun onTap(enabled: Boolean) {
        if (enabled) {
            val percent = _state.value.levelPercent.takeIf { it > 0 } ?: DEFAULT_PERCENT
            _state.value = _state.value.copy(isOn = true, levelPercent = percent)
            _isActiveFlow.value = true
            torchLevel = ctrl.toTorchLevel(percent)
            lastPercent = percent
        } else {
            _state.value = _state.value.copy(isOn = false)
            _isActiveFlow.value = false
            ctrl.torchOn = false
        }
    }

    override fun isActive(): Boolean {
        return _state.value.isOn
    }

    @Composable
    override fun getIcon(level: Float): ImageVector {
        return if (_state.value.isOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff
    }

    @Composable
    override fun getLabel(level: Float): String {
        val state = _state.value
        val displayPercent = if (state.isOn) state.levelPercent else 0
        return "Torch • $displayPercent%"
    }
}
