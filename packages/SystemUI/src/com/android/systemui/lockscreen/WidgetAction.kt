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
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.util.*

enum class WidgetAction(
    val activeRes: Int,
    val inactiveRes: Int,
    val onClick: (LockScreenWidgetsController) -> Unit,
    val onLongClick: ((LockScreenWidgetsController, View) -> Boolean)? = null,
    val registerCallback: (LockScreenWidgetsController) -> Unit = {},
    val unregisterCallback: (LockScreenWidgetsController) -> Unit = {}
) {
    WIFI(
        LsWidgetsRes.WIFI_ACTIVE, LsWidgetsRes.WIFI_INACTIVE,
        onClick = onClickLambda@{
            val enabled = !it.callbacks.wifiInfo.enabled
            it.networkController.setWifiEnabled(enabled)
            it.factory.update(WIFI, enabled)
        },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { it.networkController.addCallback(it.callbacks.wifiSignalCallback) },
        unregisterCallback = { it.networkController.removeCallback(it.callbacks.wifiSignalCallback) }
    ),
    DATA(
        LsWidgetsRes.DATA_ACTIVE, LsWidgetsRes.DATA_INACTIVE,
        onClick = onClickLambda@{
            val enabled = !it.dataController.isMobileDataEnabled
            it.dataController.setMobileDataEnabled(enabled)
            it.factory.update(DATA, enabled)
        },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { it.networkController.addCallback(it.callbacks.cellSignalCallback) },
        unregisterCallback = { it.networkController.removeCallback(it.callbacks.cellSignalCallback) }
    ),
    RINGER(
        LsWidgetsRes.RINGER_ACTIVE, LsWidgetsRes.RINGER_INACTIVE,
        onClick = onClickLambda@{
            val current = it.audioManager.ringerMode
            val next = if (current == AudioManager.RINGER_MODE_NORMAL)
                AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            it.audioManager.ringerMode = next
            it.factory.update(RINGER, next == AudioManager.RINGER_MODE_VIBRATE)
        },
        registerCallback = {
            val filter = IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
            it.context.registerReceiver(it.callbacks.ringerModeReceiver, filter)
            it.isRingerRegistered = true
        },
        unregisterCallback = {
            if (it.isRingerRegistered) {
                it.context.unregisterReceiver(it.callbacks.ringerModeReceiver)
                it.isRingerRegistered = false
            }
        }
    ),
    BT(
        LsWidgetsRes.BT_ACTIVE, LsWidgetsRes.BT_INACTIVE,
        onClick = onClickLambda@{
            val enabled = !it.bluetoothEnabled
            it.bluetoothController.setBluetoothEnabled(enabled)
            it.factory.update(BT, enabled)
        },
        onLongClick = { c, v -> c.showBluetoothDialog(v); true },
        registerCallback = { it.bluetoothController.addCallback(it.callbacks.btCallback) },
        unregisterCallback = { it.bluetoothController.removeCallback(it.callbacks.btCallback) }
    ),
    TORCH(
        LsWidgetsRes.TORCH_RES_ACTIVE, LsWidgetsRes.TORCH_RES_INACTIVE,
        onClick = onClickLambda@{
            val cameraId = it.cameraId ?: return@onClickLambda
            runCatching {
                it.cameraManager.setTorchMode(cameraId, !it.isFlashOn)
                it.isFlashOn = !it.isFlashOn
                it.factory.update(TORCH, it.isFlashOn)
            }
        },
        registerCallback = { it.flashlightController.addCallback(it.callbacks.flashlightCallback) },
        unregisterCallback = { it.flashlightController.removeCallback(it.callbacks.flashlightCallback) }
    ),
    HOTSPOT(
        LsWidgetsRes.HOTSPOT_ACTIVE, LsWidgetsRes.HOTSPOT_INACTIVE,
        onClick = onClickLambda@{
            val newState = !it.hotspotController.isHotspotEnabled
            it.hotspotController.setHotspotEnabled(newState)
            it.factory.update(HOTSPOT, newState)
        },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { it.hotspotController.addCallback(it.callbacks.hotspotCallback) },
        unregisterCallback = { it.hotspotController.removeCallback(it.callbacks.hotspotCallback) }
    );
}
