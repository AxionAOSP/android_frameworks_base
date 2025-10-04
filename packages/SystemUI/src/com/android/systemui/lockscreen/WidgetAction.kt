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

import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.systemui.res.R

enum class WidgetAction(
    val labelRes: Int,
    val onClick: (LockScreenWidgetsController) -> Unit,
    val onLongClick: ((LockScreenWidgetsController) -> Boolean)? = null,
    val registerCallback: (LockScreenWidgetsController) -> Unit = {},
    val unregisterCallback: (LockScreenWidgetsController) -> Unit = {},
    val activeLabel: @Composable (LockScreenWidgetsController) -> String? = { null }
) {
    WIFI(
        labelRes = R.string.widget_wifi,
        onClick = { ctrl ->
            val enabled = !ctrl.callbacks.wifiInfo.enabled
            ctrl.networkController.setWifiEnabled(enabled)
            ctrl.states.setActive(WIFI, enabled)
        },
        onLongClick = { c -> c.showInternetDialog(); true },
        registerCallback = { it.networkController.addCallback(it.callbacks.wifiSignalCallback) },
        unregisterCallback = { it.networkController.removeCallback(it.callbacks.wifiSignalCallback) },
        activeLabel = { ctrl -> ctrl.callbacks.wifiInfo.ssid?.removeSurrounding("\"") }
    ),

    DATA(
        labelRes = R.string.widget_data,
        onClick = { ctrl ->
            val enabled = !ctrl.dataController.isMobileDataEnabled
            ctrl.dataController.setMobileDataEnabled(enabled)
            ctrl.states.setActive(DATA, enabled)
        },
        onLongClick = { c -> c.showInternetDialog(); true },
        registerCallback = { it.networkController.addCallback(it.callbacks.cellSignalCallback) },
        unregisterCallback = { it.networkController.removeCallback(it.callbacks.cellSignalCallback) },
        activeLabel = { ctrl -> ctrl.networkController.getMobileDataNetworkName() }
    ),

    RINGER(
        labelRes = R.string.widget_ringer,
        onClick = { ctrl ->
            val current = ctrl.audioManager.ringerMode
            val next = if (current == AudioManager.RINGER_MODE_NORMAL)
                AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            ctrl.audioManager.ringerMode = next
            ctrl.states.setActive(RINGER, next == AudioManager.RINGER_MODE_VIBRATE)
        },
        registerCallback = {
            runCatching {
                it.context.registerReceiver(
                    it.callbacks.ringerModeReceiver,
                    IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
                )
            }
        },
        unregisterCallback = {
            runCatching {
                it.context.unregisterReceiver(it.callbacks.ringerModeReceiver)
            }
        },
        activeLabel = { ctrl ->
            stringResource(
                if (ctrl.audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE)
                    R.string.ringer_vibrate else R.string.ringer_normal
            )
        }
    ),

    BLUETOOTH(
        labelRes = R.string.widget_bluetooth,
        onClick = { ctrl ->
            val enabled = !ctrl.bluetoothController.isBluetoothEnabled()
            ctrl.bluetoothController.setBluetoothEnabled(enabled)
            ctrl.states.setActive(BLUETOOTH, enabled)
        },
        onLongClick = { c -> c.showBluetoothDialog(); true },
        registerCallback = { it.bluetoothController.addCallback(it.callbacks.btCallback) },
        unregisterCallback = { it.bluetoothController.removeCallback(it.callbacks.btCallback) },
        activeLabel = { ctrl -> ctrl.callbacks.connectedDeviceName }
    ),

    TORCH(
        labelRes = R.string.widget_torch,
        onClick = { ctrl ->
            runCatching {
                val enabled = !ctrl.flashlightController.isEnabled
                ctrl.flashlightController.setFlashlight(enabled)
                ctrl.states.setActive(TORCH, enabled)
            }
        },
        registerCallback = { it.flashlightController.addCallback(it.callbacks.flashlightCallback) },
        unregisterCallback = { it.flashlightController.removeCallback(it.callbacks.flashlightCallback) }
    ),

    HOTSPOT(
        labelRes = R.string.widget_hotspot,
        onClick = { ctrl ->
            val newState = !ctrl.hotspotController.isHotspotEnabled
            ctrl.hotspotController.setHotspotEnabled(newState)
            ctrl.states.setActive(HOTSPOT, newState)
        },
        onLongClick = { c -> c.showInternetDialog(); true },
        registerCallback = { it.hotspotController.addCallback(it.callbacks.hotspotCallback) },
        unregisterCallback = { it.hotspotController.removeCallback(it.callbacks.hotspotCallback) }
    );

    @Composable
    fun label(ctrl: LockScreenWidgetsController, active: Boolean): String {
        return activeLabel(ctrl).takeIf { active && !it.isNullOrBlank() }
            ?: stringResource(labelRes)
    }
}
