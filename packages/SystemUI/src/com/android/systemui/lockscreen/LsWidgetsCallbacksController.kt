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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.ScrimUtils

class LsWidgetsCallbacksController(private val controller: LockScreenWidgetsController) {

    private val wifiCallbackInfo = WifiCallbackInfo()

    val wifiInfo: WifiCallbackInfo get() = wifiCallbackInfo

    val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onUiModeChanged() {
            controller.updateViews()
        }
        override fun onThemeChanged() {
            controller.updateViews()
        }
        override fun onDensityOrFontScaleChanged() {
            controller.updateViews(true)
        }
    }

    val scrimUtilsCb = object : ScrimUtils.ScrimEventListener {
        override fun onDozingChanged() {
            controller.updateViews()
        }
        override fun onKeyguardShowingChanged(showing: Boolean) {
            controller.listening = showing
        }
    }

    val flashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            controller.isFlashOn = enabled
            controller.states.updateTorch()
        }
        override fun onFlashlightError() {}
        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            controller.isFlashOn =
                controller.flashlightController.isEnabled() && available
            controller.states.updateTorch()
        }
    }

    val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.states.updateRinger()
        }
    }

    val btCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            controller.states.updateBluetooth()
        }
        override fun onBluetoothDevicesChanged() {
            controller.states.updateBluetooth()
        }
    }

    val wifiSignalCallback = object : SignalCallback {
        override fun setWifiIndicators(indicators: WifiIndicators) {
            if (indicators.qsIcon == null) {
                controller.states.updateWiFi(false)
                return
            }
            wifiInfo.enabled = indicators.enabled
            wifiInfo.ssid = indicators.description
            controller.states.updateWiFi(wifiInfo.enabled)
        }
    }

    class WifiCallbackInfo {
        var enabled = false
        var ssid: String? = null
    }

    val cellSignalCallback = object : SignalCallback {
        override fun setMobileDataIndicators(indicators: MobileDataIndicators) {
            if (indicators.qsIcon == null || !indicators.isDefault) {
                return
            }
            val isEnabled = controller.networkController
                .mobileDataController.isMobileDataEnabled
            controller.states.updateMobileData(isEnabled)
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            val isEnabled = simDetected && controller.networkController
                .mobileDataController.isMobileDataEnabled
            controller.states.updateMobileData(isEnabled)
        }

        override fun setIsAirplaneMode(icon: IconState) {
            val isEnabled = !icon.visible && controller.networkController
                .mobileDataController.isMobileDataEnabled
            controller.states.updateMobileData(isEnabled)
        }
    }

    val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            controller.states.updateHotspot()
        }
        override fun onHotspotAvailabilityChanged(available: Boolean) {}
    }
    
    fun observe() {
        controller.scrimUtils.addListener(scrimUtilsCb)
        controller.configurationController.addCallback(configurationListener)
        controller.repository.observe()
        controller.listening = true
    }
    
    fun dispose() {
        controller.scrimUtils.removeListener(scrimUtilsCb)
        controller.configurationController.removeCallback(configurationListener)
        controller.listening = false
        controller.repository.dispose()
    }
}
