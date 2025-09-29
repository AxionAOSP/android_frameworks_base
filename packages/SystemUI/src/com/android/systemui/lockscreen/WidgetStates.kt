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
package com.android.systemui.lockscreen

import android.media.AudioManager

class WidgetStates(
    private val controller: LockScreenWidgetsController
) {
    private val lastStates = mutableMapOf<WidgetAction, Boolean>()

    private fun updateStateIfChanged(action: WidgetAction, newState: Boolean) {
        if (lastStates[action] != newState) {
            lastStates[action] = newState
            controller.handler.post {
                controller.factory.update(action, newState)
            }
        }
    }

    fun updateTorch() {
        updateStateIfChanged(WidgetAction.TORCH, controller.isFlashOn)
    }

    fun updateRinger() {
        val isVibrate = controller.audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        updateStateIfChanged(WidgetAction.RINGER, isVibrate)
    }

    fun updateBluetooth() {
        updateStateIfChanged(WidgetAction.BLUETOOTH, controller.bluetoothEnabled)
    }

    fun updateWiFi(enabled: Boolean) {
        updateStateIfChanged(WidgetAction.WIFI, enabled)
    }

    fun updateMobileData(enabled: Boolean) {
        updateStateIfChanged(WidgetAction.DATA, enabled)
    }

    fun updateHotspot() {
        updateStateIfChanged(WidgetAction.HOTSPOT, controller.hotspotController.isHotspotEnabled)
    }

    fun isActive(action: WidgetAction): Boolean {
        return when (action) {
            WidgetAction.TORCH -> controller.isFlashOn
            WidgetAction.RINGER -> controller.audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            WidgetAction.BLUETOOTH -> controller.bluetoothEnabled
            WidgetAction.WIFI -> controller.callbacks.wifiInfo.enabled
            WidgetAction.DATA -> controller.networkController.mobileDataController.isMobileDataEnabled
            WidgetAction.HOTSPOT -> controller.hotspotController.isHotspotEnabled
            else -> false
        }
    }
}
