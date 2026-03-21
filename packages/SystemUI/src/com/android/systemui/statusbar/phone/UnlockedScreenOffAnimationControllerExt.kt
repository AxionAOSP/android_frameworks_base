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
 * limitations under the License
 */
package com.android.systemui.statusbar.phone

import java.util.concurrent.CopyOnWriteArrayList

object UnlockedScreenOffAnimationControllerExt {

    private val mCallbacks = CopyOnWriteArrayList<ScreenOffAnimationCallback>()
    var isAnimationPlaying: Boolean = false

    fun addCallback(callback: ScreenOffAnimationCallback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback)
        }
    }

    fun removeCallback(callback: ScreenOffAnimationCallback) {
        mCallbacks.remove(callback)
    }

    fun onAnimationStart() {
        mCallbacks.forEachIndexed { index, cb ->
            cb.onAnimationStart()
        }
    }

    fun onAnimationEnd() {
        mCallbacks.forEachIndexed { index, cb ->
            cb.onAnimationEnd()
        }
    }

    fun onAnimationCancel() {
        mCallbacks.forEachIndexed { index, cb ->
            cb.onAnimationCancel()
        }
    }

    fun onAnimateInKeyguardEnd() {
        mCallbacks.forEachIndexed { index, cb ->
            cb.onAnimateInKeyguardEnd()
        }
    }
}
