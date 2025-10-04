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
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.ScrimUtils

class LsWidgetsCallbacksController(private val ctrl: LockScreenWidgetsController) {

    private val wifiCallbackInfo = WifiCallbackInfo()

    val wifiInfo: WifiCallbackInfo get() = wifiCallbackInfo
    var connectedDeviceName by mutableStateOf<String?>(null)

    class WifiCallbackInfo {
        var enabled by mutableStateOf(false)
        var ssid by mutableStateOf<String?>(null)
    }

    val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onUiModeChanged() = ctrl.factory.updateViews()
        override fun onThemeChanged() = ctrl.factory.updateViews()
        override fun onDensityOrFontScaleChanged() = ctrl.factory.updateViews()
    }

    val scrimUtilsCb = object : ScrimUtils.ScrimEventListener {
        override fun onDozingChanged() = ctrl.factory.updateViews()
        override fun onKeyguardShowingChanged(showing: Boolean) { ctrl.listening = showing }
    }

    val flashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            ctrl.states.setActive(WidgetAction.TORCH, enabled)
        }
        override fun onFlashlightError() {}
        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            val enabled = ctrl.flashlightController.isEnabled() && available
            ctrl.states.setActive(WidgetAction.TORCH, enabled)
        }
    }

    val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isVibrate = ctrl.audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            ctrl.states.setActive(WidgetAction.RINGER, isVibrate)
        }
    }

    val btCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            ctrl.states.setActive(WidgetAction.BLUETOOTH, enabled)
        }
        override fun onBluetoothDevicesChanged() {
            ctrl.states.setActive(WidgetAction.BLUETOOTH, ctrl.bluetoothController.isBluetoothEnabled())
            connectedDeviceName = ctrl.bluetoothController.getConnectedDeviceName()
        }
    }

    val wifiSignalCallback = object : SignalCallback {
        override fun setWifiIndicators(indicators: WifiIndicators) {
            if (indicators.qsIcon == null) {
                wifiInfo.enabled = false
                wifiInfo.ssid = null
                ctrl.states.setActive(WidgetAction.WIFI, false)
                return
            }
            wifiInfo.enabled = indicators.enabled
            wifiInfo.ssid = indicators.description
            ctrl.states.setActive(WidgetAction.WIFI, indicators.enabled)
        }
    }

    val cellSignalCallback = object : SignalCallback {
        override fun setMobileDataIndicators(indicators: MobileDataIndicators) {
            if (indicators.qsIcon != null && indicators.isDefault) {
                ctrl.states.setActive(
                    WidgetAction.DATA,
                    ctrl.dataController.isMobileDataEnabled
                )
            }
        }

        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            val enabled = simDetected && ctrl.dataController.isMobileDataEnabled
            ctrl.states.setActive(WidgetAction.DATA, enabled)
        }

        override fun setIsAirplaneMode(icon: IconState) {
            val enabled = !icon.visible && ctrl.dataController.isMobileDataEnabled
            ctrl.states.setActive(WidgetAction.DATA, enabled)
        }
    }

    val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            ctrl.states.setActive(WidgetAction.HOTSPOT, enabled)
        }
        override fun onHotspotAvailabilityChanged(available: Boolean) {}
    }

    fun observe() {
        ctrl.scrimUtils.addListener(scrimUtilsCb)
        ctrl.configurationController.addCallback(configurationListener)
        ctrl.repository.observe()
        ctrl.listening = true
    }

    fun dispose() {
        ctrl.scrimUtils.removeListener(scrimUtilsCb)
        ctrl.configurationController.removeCallback(configurationListener)
        ctrl.listening = false
        ctrl.repository.dispose()
    }
}
