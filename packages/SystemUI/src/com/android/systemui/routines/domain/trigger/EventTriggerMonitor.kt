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

package com.android.systemui.routines.domain.trigger

import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.android.axion.platform.IAxPlatformCallback
import com.android.systemui.ax.AxPlatformStateManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.routines.model.Trigger
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import javax.inject.Inject

@SysUISingleton
class EventTriggerMonitor @Inject constructor(
    @Application private val context: Context,
    private val batteryController: BatteryController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val stateManager: AxPlatformStateManager,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
) {

    private var callback: ((trigger: Trigger) -> Unit)? = null
    private var started = false
    private var lastBatteryLevel = -1
    private var lastChargingState = false
    private var currentForegroundPkg: String? = null
    private var launcherPackage: String? = null

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (lastChargingState != charging) {
                lastChargingState = charging
                callback?.invoke(Trigger.ChargingState(charging))
            }
            if (lastBatteryLevel != level) {
                val previousLevel = lastBatteryLevel
                lastBatteryLevel = level
                if (previousLevel >= 0) {
                    val direction = if (level < previousLevel) {
                        Trigger.BatteryLevel.Direction.BELOW
                    } else {
                        Trigger.BatteryLevel.Direction.ABOVE
                    }
                    callback?.invoke(Trigger.BatteryLevel(level, direction))
                }
            }
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> callback?.invoke(Trigger.ScreenState(on = true))
                Intent.ACTION_SCREEN_OFF -> callback?.invoke(Trigger.ScreenState(on = false))

                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiManager.EXTRA_NETWORK_INFO,
                    )
                    val connected = networkInfo?.isConnected ?: false
                    val wifiManager = ctx.getSystemService(WifiManager::class.java)
                    val ssid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
                    callback?.invoke(Trigger.WifiState(connected, ssid))
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE,
                    )
                    callback?.invoke(Trigger.BluetoothState(true, device?.address))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE,
                    )
                    callback?.invoke(Trigger.BluetoothState(false, device?.address))
                }

                AudioManager.ACTION_HEADSET_PLUG -> {
                    val plugged = intent.getIntExtra("state", 0) == 1
                    callback?.invoke(Trigger.HeadphonesState(plugged))
                }

                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = intent.getIntExtra(
                        AudioManager.EXTRA_RINGER_MODE,
                        AudioManager.RINGER_MODE_NORMAL,
                    )
                    callback?.invoke(Trigger.RingerMode(mode))
                }
            }
        }
    }

    private val taskStackListener = object : TaskStackChangeListener {
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            val pkg = taskInfo.topActivity?.packageName ?: return
            if (pkg == launcherPackage || pkg == context.packageName) return

            val previousPkg = currentForegroundPkg
            currentForegroundPkg = pkg

            callback?.invoke(Trigger.AppLaunch(pkg))
            previousPkg?.let { callback?.invoke(Trigger.AppClose(it)) }
        }
    }

    private val platformCallback = object : IAxPlatformCallback.Stub() {
        override fun onStateChanged(feature: String, state: android.os.Bundle) {
            val active = state.getBoolean("active", false)
            callback?.invoke(Trigger.FeatureState(feature, active))
        }
    }

    private val sensorPrivacyCallback =
        object : IndividualSensorPrivacyController.Callback {
            override fun onSensorBlockedChanged(sensor: Int, blocked: Boolean) {
                callback?.invoke(Trigger.SensorPrivacyState(sensor, blocked))
            }
        }

    fun setCallback(cb: (trigger: Trigger) -> Unit) {
        callback = cb
    }

    fun start() {
        if (started) return
        started = true

        batteryController.addCallback(batteryCallback)
        lastChargingState = batteryController.isPluggedIn
        launcherPackage = resolveLauncherPackage()
        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskStackListener)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        broadcastDispatcher.registerReceiver(
            receiver = broadcastReceiver,
            filter = filter,
        )

        stateManager.registerCallback(platformCallback)
        sensorPrivacyController.addCallback(sensorPrivacyCallback)

        Log.d(TAG, "Event trigger monitor started")
    }

    fun stop() {
        if (!started) return
        started = false

        batteryController.removeCallback(batteryCallback)
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskStackListener)
        broadcastDispatcher.unregisterReceiver(broadcastReceiver)
        stateManager.unregisterCallback(platformCallback)
        sensorPrivacyController.removeCallback(sensorPrivacyCallback)
        currentForegroundPkg = null

        Log.d(TAG, "Event trigger monitor stopped")
    }

    private fun resolveLauncherPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        private const val TAG = "RoutinesEventTrigger"
    }
}
