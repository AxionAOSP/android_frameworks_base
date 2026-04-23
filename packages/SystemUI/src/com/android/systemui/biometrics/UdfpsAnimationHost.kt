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
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.android.axion.compose.host.AxComposeView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

@SysUISingleton
class UdfpsAnimationHost @Inject constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    private val interactor: UdfpsAnimationInteractor,
    private val configurationController: ConfigurationController,
) {
    private var composeView: AxComposeView? = null
    private val animParams: WindowManager.LayoutParams
    private var currentOffsetY: Int = 0
    private var currentAnimationSize: Int = 0

    private val themeListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                interactor.onThemeChanged()
                if (composeView != null) {
                    detach()
                    attach()
                }
            }
        }

    init {
        configurationController.addCallback(themeListener)
        val animationSize = interactor.uiState.value.animationSize
        currentAnimationSize = animationSize

        animParams = WindowManager.LayoutParams().apply {
            height = animationSize
            width = animationSize
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            setTrustedOverlay()
        }
    }

    private fun updateWindowLayout(state: UdfpsAnimationUiState) {
        if (currentOffsetY != state.animationOffsetY || currentAnimationSize != state.animationSize) {
            currentOffsetY = state.animationOffsetY
            currentAnimationSize = state.animationSize
            animParams.y = state.animationOffsetY
            animParams.width = state.animationSize
            animParams.height = state.animationSize
            composeView?.let { view ->
                try {
                    windowManager.updateViewLayout(view, animParams)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun attach() {
        if (composeView != null) return
        
        val initialState = interactor.uiState.value
        animParams.y = initialState.animationOffsetY
        animParams.width = initialState.animationSize
        animParams.height = initialState.animationSize
        currentOffsetY = initialState.animationOffsetY
        currentAnimationSize = initialState.animationSize

        val view = AxComposeView(context).apply {
            setContent {
                val state by interactor.uiState.collectAsState()
                
                LaunchedEffect(state.animationOffsetY, state.animationSize) {
                    updateWindowLayout(state)
                }
                
                UdfpsAnimation(state = state)
            }
        }

        try {
            windowManager.addView(view, animParams)
            composeView = view
        } catch (e: RuntimeException) {
        }
    }

    fun detach() {
        composeView?.let { view ->
            try {
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
            }
            composeView = null
        }
    }

}
