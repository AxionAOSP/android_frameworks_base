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
import android.graphics.Point
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.SystemProperties
import android.util.Log
import android.view.WindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val windowManager: WindowManager,
    private val authController: AuthController,
) : UdfpsController.Callback {
    private val _uiState = MutableStateFlow(UdfpsAnimationUiState())
    val uiState: StateFlow<UdfpsAnimationUiState> = _uiState.asStateFlow()

    private var fingerDown = false
    private var sensorProps: FingerprintSensorPropertiesInternal? = null

    init {
        val animationSize = context.resources.getDimensionPixelSize(R.dimen.udfps_animation_size)
        _uiState.update { it.copy(animationSize = animationSize) }
        
        ScrimUtils.get().addListener(object : ScrimUtils.ScrimEventListener {
            override fun onKeyguardShowingChanged(showing: Boolean) {
                if (!showing) {
                    fingerDown = false
                    updateVisibility()
                }
            }
            override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
                if (goingAway) {
                    fingerDown = false
                    updateVisibility()
                }
            }
            override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
                if (fadingAway) {
                    fingerDown = false
                    updateVisibility()
                }
            }
        })
    }

    fun setSensorProps(props: FingerprintSensorPropertiesInternal) {
        sensorProps = props
        _uiState.update { 
            it.copy(sensorType = props.sensorType)
        }
        updatePosition()
    }

    fun stopAnimation() {
        fingerDown = false
        updateVisibility()
    }

    override fun onFingerDown() {
        fingerDown = true
        updateVisibility()
    }

    override fun onFingerUp() {
        stopAnimation()
    }

    private fun updateVisibility() {
        val shouldShow = fingerDown
        
        if (_uiState.value.isVisible != shouldShow) {
            _uiState.update { it.copy(isVisible = shouldShow) }
        }
    }

    private fun updatePosition() {
        val props = sensorProps ?: return
        
        val displaySize = Point()
        windowManager.defaultDisplay.getRealSize(displaySize)
        
        val isFullResolution = displaySize.y > 3000
        val udfpsLocation = authController.udfpsLocation
        val scaleFactor = authController.scaleFactor
        val udfpsRadius = if (isFullResolution) {
            authController.udfpsRadius
        } else {
            props.location.sensorRadius.toFloat()
        }
        val udfpsLocationY = if (isFullResolution && udfpsLocation != null) {
            udfpsLocation.y.toFloat()
        } else {
            props.location.sensorLocationY.toFloat()
        }
        
        val animationOffset = context.resources.getDimensionPixelSize(
            R.dimen.udfps_animation_offset
        ) * scaleFactor
        
        val animationSize = _uiState.value.animationSize
        val offsetY = (udfpsLocationY * scaleFactor).toInt() - 
                      (udfpsRadius * scaleFactor).toInt() - 
                      (animationSize / 2) + 
                      animationOffset.toInt()

        _uiState.update { it.copy(animationOffsetY = offsetY) }
    }

    companion object {
        private const val TAG = "UdfpsAnimationInteractor"
    }
}
