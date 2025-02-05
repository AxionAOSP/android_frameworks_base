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
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.axion.compose.lifecycle.repeatWhenAttached
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class UdfpsAnimationHost @Inject constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    private val interactor: UdfpsAnimationInteractor,
) {
    private var composeView: ComposeView? = null
    private val animParams: WindowManager.LayoutParams
    private var currentOffsetY: Int = 0

    init {
        val animationSize = context.resources.getDimensionPixelSize(R.dimen.udfps_animation_size)

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

    private fun updateWindowPosition(offsetY: Int) {
        if (currentOffsetY != offsetY) {
            currentOffsetY = offsetY
            animParams.y = offsetY
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
        currentOffsetY = initialState.animationOffsetY

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    setContent {
                        val state by interactor.uiState.collectAsState()
                        
                        LaunchedEffect(state.animationOffsetY) {
                            updateWindowPosition(state.animationOffsetY)
                        }
                        
                        UdfpsAnimation(state = state)
                    }
                }
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

    companion object {
        private const val TAG = "UdfpsAnimationHost"
    }
}
