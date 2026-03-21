/*
 * Copyright (C) 2026 AxionOS
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
package com.android.systemui.mistouch.ui.view

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

private const val WINDOW_TITLE = "MistouchPreve"
private val WINDOW_FLAGS =
    LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_SHOW_WHEN_LOCKED
private val PRIVATE_FLAGS =
    LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS or
        LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION or
        LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY

@SysUISingleton
class MistouchPreventionOverlay @Inject constructor(
    context: Context,
) {
    interface Callback {
        fun onVolumeUpPressed()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = MistouchPreventionView(context).apply {
        visibility = View.INVISIBLE
    }
    private var callback: Callback? = null
    private var windowAdded = false

    private val volumeKeyCallback = object : MistouchPreventionView.VolumeKeyCallback {
        override fun onVolumeUpPressed() {
            callback?.onVolumeUpPressed()
        }
    }

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun attach() {
        if (windowAdded) return

        val manager = windowManager ?: return
        view.visibility = View.INVISIBLE
        manager.addView(view, layoutParams)
        windowAdded = true
    }

    fun detach() {
        hide()
        if (windowAdded) {
            windowManager?.removeView(view)
            windowAdded = false
        }
    }

    fun show() {
        attach()
        view.addCallback(volumeKeyCallback)
        view.visibility = View.VISIBLE
        view.requestFocus()
    }

    fun hide() {
        view.visibility = View.INVISIBLE
        view.removeCallback(volumeKeyCallback)
    }

    private val layoutParams: LayoutParams
        get() = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_DISPLAY_OVERLAY,
            WINDOW_FLAGS,
            PixelFormat.TRANSPARENT,
        ).apply {
            privateFlags = privateFlags or PRIVATE_FLAGS
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setTitle(WINDOW_TITLE)
        }
}
