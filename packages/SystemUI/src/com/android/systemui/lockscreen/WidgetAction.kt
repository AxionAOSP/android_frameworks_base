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

import android.view.View
import android.content.IntentFilter
import android.media.AudioManager

import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.*
import com.android.systemui.statusbar.policy.*
import com.android.systemui.util.*

enum class WidgetAction(
    val activeRes: Int,
    val inactiveRes: Int,
    val onClick: (LockScreenWidgetsController.ViewController) -> Unit,
    val onLongClick: ((LockScreenWidgetsController.ViewController, View) -> Boolean)? = null,
    val registerCallback: (LockScreenWidgetsController.ViewController) -> Unit = {},
    val unregisterCallback: (LockScreenWidgetsController.ViewController) -> Unit = {}
) {
    WIFI(
        LsWidgetsRes.WIFI_ACTIVE, LsWidgetsRes.WIFI_INACTIVE,
        onClick = { it.toggleWiFi() },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { controller ->
            Dependency.get(NetworkController::class.java).addCallback(controller.callbacks.wifiSignalCallback)
        },
        unregisterCallback = { controller ->
            Dependency.get(NetworkController::class.java).removeCallback(controller.callbacks.wifiSignalCallback)
        }
    ),
    DATA(
        LsWidgetsRes.DATA_ACTIVE, LsWidgetsRes.DATA_INACTIVE,
        onClick = { it.toggleMobileData() },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { controller ->
            Dependency.get(NetworkController::class.java).addCallback(controller.callbacks.cellSignalCallback)
        },
        unregisterCallback = { controller ->
            Dependency.get(NetworkController::class.java).removeCallback(controller.callbacks.cellSignalCallback)
        }
    ),
    RINGER(
        LsWidgetsRes.RINGER_ACTIVE, LsWidgetsRes.RINGER_INACTIVE,
        onClick = { it.toggleRingerMode() },
        registerCallback = { controller ->
            val filter = IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)
            controller.context.registerReceiver(controller.callbacks.ringerModeReceiver, filter)
            controller.isRingerReceiverRegistered = true
        },
        unregisterCallback = { controller ->
            if (controller.isRingerReceiverRegistered) {
                controller.context.unregisterReceiver(controller.callbacks.ringerModeReceiver)
                controller.isRingerReceiverRegistered = false
            }
        }
    ),
    BT(
        LsWidgetsRes.BT_ACTIVE, LsWidgetsRes.BT_INACTIVE,
        onClick = { it.toggleBluetooth() },
        onLongClick = { c, v -> c.showBluetoothDialog(v); true },
        registerCallback = { controller ->
            Dependency.get(BluetoothController::class.java).addCallback(controller.callbacks.btCallback)
        },
        unregisterCallback = { controller ->
            Dependency.get(BluetoothController::class.java).removeCallback(controller.callbacks.btCallback)
        }
    ),
    TORCH(
        LsWidgetsRes.TORCH_RES_ACTIVE, LsWidgetsRes.TORCH_RES_INACTIVE,
        onClick = { it.toggleFlashlight() },
        registerCallback = { controller ->
            Dependency.get(FlashlightController::class.java).addCallback(controller.callbacks.flashlightCallback)
        },
        unregisterCallback = { controller ->
            Dependency.get(FlashlightController::class.java).removeCallback(controller.callbacks.flashlightCallback)
        }
    ),
    MEDIA(
        R.drawable.ic_media_pause, R.drawable.ic_media_play,
        onClick = { it.toggleMediaPlaybackState() },
        registerCallback = { controller ->
            controller.mediaSessionManagerHelper.addMediaMetadataListener(controller)
        },
        unregisterCallback = { controller ->
            controller.mediaSessionManagerHelper.removeMediaMetadataListener(controller)
        }
    ),
    HOTSPOT(
        LsWidgetsRes.HOTSPOT_ACTIVE, LsWidgetsRes.HOTSPOT_INACTIVE,
        onClick = { it.toggleHotspot() },
        onLongClick = { c, v -> c.showInternetDialog(v); true },
        registerCallback = { controller ->
            Dependency.get(HotspotController::class.java).addCallback(controller.callbacks.hotspotCallback)
        },
        unregisterCallback = { controller ->
            Dependency.get(HotspotController::class.java).removeCallback(controller.callbacks.hotspotCallback)
        }
    ),
    TIMER(
        R.drawable.ic_alarm, R.drawable.ic_alarm,
        onClick = { it.activityLauncherUtils.launchTimer() }
    ),
    CALCULATOR(
        R.drawable.ic_calculator, R.drawable.ic_calculator,
        onClick = { it.activityLauncherUtils.launchCalculator() }
    ),
    WALLET(
        R.drawable.ic_wallet_lockscreen, R.drawable.ic_wallet_lockscreen,
        onClick = { it.activityLauncherUtils.launchWalletApp() }
    ),
    QRSCANNER(
        R.drawable.ic_qr_code_scanner, R.drawable.ic_qr_code_scanner,
        onClick = { it.activityLauncherUtils.launchQrScanner() }
    );
}
