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
import android.util.Log
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
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
    private val authController: AuthController,
    private val deviceEntryIconViewModel: DeviceEntryIconViewModel,
    private val fingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val fingerprintPropertyInteractor: FingerprintPropertyInteractor,
    private val udfpsOverlayInteractor: UdfpsOverlayInteractor,
) {
    private val _uiState = MutableStateFlow(UdfpsAnimationUiState())
    val uiState: StateFlow<UdfpsAnimationUiState> = _uiState.asStateFlow()

    init {
        val animationSize = context.resources.getDimensionPixelSize(R.dimen.udfps_animation_size)
        _uiState.update { it.copy(animationSize = animationSize) }

        combine(
            fingerprintAuthRepository.isEngaged,
            deviceEntryIconViewModel.isVisible,
            fingerprintPropertyInteractor.isUdfps
        ) { isEngaged, isIconVisible, isUdfps ->
            isEngaged && isIconVisible && isUdfps
        }.onEach { shouldShow ->
            if (_uiState.value.isVisible != shouldShow) {
                _uiState.update { it.copy(isVisible = shouldShow) }
            }
        }.launchIn(scope)

        fingerprintPropertyInteractor.isUdfps
            .onEach { updatePosition() }
            .launchIn(scope)

        fingerprintPropertyInteractor.sensorLocation
            .onEach { updatePosition() }
            .launchIn(scope)

        udfpsOverlayInteractor.udfpsOverlayParams
            .onEach { updatePosition() }
            .launchIn(scope)
    }

    fun updatePosition() {
        val udfpsLocation = authController.udfpsLocation
        val scaleFactor = authController.scaleFactor

        // AuthController has the most accurate current location info
        // as it handles rotation and scale factor in a way that's proven to work
        // with the existing UdfpsController logic.
        val (scaledLocationY, scaledRadius) = if (udfpsLocation != null) {
            Pair(udfpsLocation.y.toFloat(), authController.udfpsRadius)
        } else {
            Log.w(TAG, "updatePosition | udfpsLocation is null")
            return
        }

        val animationOffset = context.resources.getDimensionPixelSize(
            R.dimen.udfps_animation_offset
        ) * scaleFactor

        val animationSize = _uiState.value.animationSize
        val offsetY = scaledLocationY -
                scaledRadius -
                (animationSize / 2) +
                animationOffset.toInt()

        _uiState.update { it.copy(animationOffsetY = offsetY.toInt()) }
    }

    companion object {
        private const val TAG = "UdfpsAnimationInteractor"
    }
}
