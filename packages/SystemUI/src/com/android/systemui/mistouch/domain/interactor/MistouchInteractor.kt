/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.mistouch.domain.interactor

import com.android.systemui.mistouch.domain.model.MistouchEvent
import com.android.systemui.util.WeakListenerManager

class MistouchInteractor private constructor() {

    interface MistouchEventListener {
        fun onMistouchEvent(event: MistouchEvent) {}
    }

    private val listenerManager = WeakListenerManager<MistouchEventListener>()

    fun addListener(listener: MistouchEventListener) = listenerManager.addListener(listener)
    fun removeListener(listener: MistouchEventListener) = listenerManager.removeListener(listener)

    fun handleEmergencyButtonClick() {
        notify(MistouchEvent.EMERGENCY_BUTTON_CLICK)
    }

    fun handleDoubleTapPowerGesture() {
        notify(MistouchEvent.DOUBLE_TAP_POWER_GESTURE)
    }

    fun handleAffordanceLongClick() {
        notify(MistouchEvent.AFFORDANCE_LONG_CLICK)
    }

    fun handleKeyguardInteraction() {
        notify(MistouchEvent.KEYGUARD_INTERACTION)
    }

    private fun notify(event: MistouchEvent) {
        listenerManager.notify { it.onMistouchEvent(event) }
    }

    companion object {
        const val KEYGUARD_INTERACTION_TIMEOUT_MS = 1500L

        @Volatile
        private var instance: MistouchInteractor? = null

        @JvmStatic
        fun get(): MistouchInteractor {
            return instance ?: synchronized(this) {
                instance ?: MistouchInteractor().also { instance = it }
            }
        }
    }
}
