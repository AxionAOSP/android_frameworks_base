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
package com.android.systemui.biometrics

import android.content.Context
import android.hardware.fingerprint.FingerprintSensorProperties
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class UdfpsAnimationUiState(
    val isVisible: Boolean = false,
    val sensorType: Int = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
    val animationOffsetY: Int = 0,
    val animationSize: Int = 0,
)

@SysUISingleton
class UdfpsAnimationInteractor @Inject constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private val _uiState = MutableStateFlow(UdfpsAnimationUiState())
    val uiState: StateFlow<UdfpsAnimationUiState> = _uiState.asStateFlow()

    init {
        val animationSize = context.resources.getDimensionPixelSize(R.dimen.udfps_animation_size)
        _uiState.update { it.copy(animationSize = animationSize) }

        combine(
            udfpsOverlayInteractor.isFingerDown,
            deviceEntryUdfpsInteractor.isListeningForUdfps,
        ) { isFingerDown, isListening ->
            isFingerDown && isListening
        }.onEach { updateVisibility(it) }
            .launchIn(scope)

        udfpsOverlayInteractor.udfpsOverlayParams
            .onEach { updatePosition(it) }
            .launchIn(scope)
    }

    private fun updateVisibility(visible: Boolean) {
        if (_uiState.value.isVisible != visible) {
            _uiState.update { it.copy(isVisible = visible) }
        }
    }

    private fun updatePosition(params: UdfpsOverlayParams) {
        if (params.sensorBounds.isEmpty) {
            return
        }

        val animationOffset = context.resources.getDimensionPixelSize(
            R.dimen.udfps_animation_offset
        ) * params.scaleFactor

        val animationSize = _uiState.value.animationSize
        val offsetY = params.sensorBounds.top - (animationSize / 2) + animationOffset.toInt()

        _uiState.update {
            it.copy(
                animationOffsetY = offsetY,
                sensorType = params.sensorType,
            )
        }
    }
}
