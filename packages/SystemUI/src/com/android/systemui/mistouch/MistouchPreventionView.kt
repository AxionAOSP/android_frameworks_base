/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.systemui.mistouch

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.axion.compose.host.AxComposeView

class MistouchPreventionView(context: Context) : FrameLayout(context) {

    interface VolumeKeyCallback {
        fun onVolumeUpPressed()
    }

    private var listener: VolumeKeyCallback? = null
    private val composeView = AxComposeView(context).also {
        it.setContent { MistouchPreventionContent() }
        addView(it, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_UP) {
            listener?.onVolumeUpPressed()
            listener = null
        }
        return true
    }

    fun addCallback(callback: VolumeKeyCallback) {
        listener = callback
    }

    fun removeCallback(callback: VolumeKeyCallback) {
        if (listener == callback) {
            listener = null
        }
    }
}
