/*
 * Copyright (C) 2025 the AxionAOSP Project
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
import android.os.SystemClock

import com.android.internal.util.android.VibrationUtils

import com.android.systemui.Dependency
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogViewModel
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.MediaSessionManagerHelper

class LsWidgetsCallbacksController(private val controller: LockScreenWidgetsController.ViewController) {

    private val wifiCallbackInfo = WifiCallbackInfo()

    val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onUiModeChanged() {
            controller.updateWidgetViews()
        }
        override fun onThemeChanged() {
            controller.updateWidgetViews()
        }
    }

    val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) { }
        override fun onDozingChanged(dozing: Boolean) {
            if (controller.dozing == dozing) return
            controller.dozing = dozing
            controller.updateContainerVisibility()
        }
    }

    val flashlightCallback = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            controller.isFlashOn = enabled
            controller.updateTorchButtonState()
        }
        override fun onFlashlightError() { }
        override fun onFlashlightAvailabilityChanged(available: Boolean) {
            controller.isFlashOn =
                Dependency.get(FlashlightController::class.java).isEnabled() && available
            controller.updateTorchButtonState()
        }
    }

    val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.updateRingerButtonState()
        }
    }

    val btCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            controller.updateBtState()
        }
        override fun onBluetoothDevicesChanged() {
            controller.updateBtState()
        }
    }

    val wifiSignalCallback = object : SignalCallback {
        override fun setWifiIndicators(indicators: WifiIndicators) {
            if (indicators.qsIcon == null) {
                controller.updateWiFiButtonState(false)
                return
            }
            wifiCallbackInfo.enabled = indicators.enabled
            wifiCallbackInfo.ssid = indicators.description
            controller.updateWiFiButtonState(wifiCallbackInfo.enabled)
        }
    }

    fun getWifiCallbackInfo(): WifiCallbackInfo = wifiCallbackInfo

    class WifiCallbackInfo {
        var enabled = false
        var ssid: String? = null
    }

    val cellSignalCallback = object : SignalCallback {
        override fun setMobileDataIndicators(indicators: MobileDataIndicators) {
            if (indicators.qsIcon == null) {
                controller.updateMobileDataState(false)
                return
            }
            controller.updateMobileDataState(
                Dependency.get(NetworkController::class.java)
                    .mobileDataController.isMobileDataEnabled
            )
        }
        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            controller.updateMobileDataState(
                simDetected && Dependency.get(NetworkController::class.java)
                    .mobileDataController.isMobileDataEnabled
            )
        }
        override fun setIsAirplaneMode(icon: IconState) {
            controller.updateMobileDataState(
                !icon.visible && Dependency.get(NetworkController::class.java)
                    .mobileDataController.isMobileDataEnabled
            )
        }
    }

    val hotspotCallback = object : HotspotController.Callback {
        override fun onHotspotChanged(enabled: Boolean, numDevices: Int) {
            controller.updateHotspotState()
        }
        override fun onHotspotAvailabilityChanged(available: Boolean) { }
    }
}
