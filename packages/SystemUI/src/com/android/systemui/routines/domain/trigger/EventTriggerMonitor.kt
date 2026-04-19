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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    private val connectivityManager: ConnectivityManager,
) {

    private var callback: ((trigger: Trigger) -> Unit)? = null
    private var lastBatteryLevel = -1
    private var lastChargingState = false
    private var currentForegroundPkg: String? = null
    private var launcherPackage: String? = null
    private var lastCaptivePortalState = false

    private val activeListeners = mutableSetOf<ListenerGroup>()

    enum class ListenerGroup {
        BATTERY,
        SCREEN,
        WIFI,
        BLUETOOTH,
        HEADPHONES,
        RINGER,
        APP_LIFECYCLE,
        FEATURE_STATE,
        SENSOR_PRIVACY,
        CAPTIVE_PORTAL,
    }

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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCaptive = isWifi &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            if (isCaptive != lastCaptivePortalState) {
                lastCaptivePortalState = isCaptive
                if (isCaptive) {
                    val wifiManager = context.getSystemService(WifiManager::class.java)
                    val ssid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
                    callback?.invoke(Trigger.CaptivePortal(ssid))
                }
            }
        }

        override fun onLost(network: Network) {
            lastCaptivePortalState = false
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

    fun updateListeners(requiredGroups: Set<ListenerGroup>) {
        val toRegister = requiredGroups - activeListeners
        val toUnregister = activeListeners - requiredGroups

        toUnregister.forEach { unregisterGroup(it) }
        toRegister.forEach { registerGroup(it) }

        Log.d(TAG, "Listeners updated: active=${activeListeners.size} " +
            "(+${toRegister.size} -${toUnregister.size})")
    }

    fun stop() {
        activeListeners.toSet().forEach { unregisterGroup(it) }
        currentForegroundPkg = null
        lastCaptivePortalState = false
        Log.d(TAG, "Event trigger monitor stopped")
    }

    private fun registerGroup(group: ListenerGroup) {
        if (group in activeListeners) return
        activeListeners.add(group)

        when (group) {
            ListenerGroup.BATTERY -> {
                lastChargingState = batteryController.isPluggedIn
                batteryController.addCallback(batteryCallback)
            }
            ListenerGroup.SCREEN -> {
                broadcastDispatcher.registerReceiver(
                    receiver = screenReceiver,
                    filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                )
            }
            ListenerGroup.WIFI -> {
                broadcastDispatcher.registerReceiver(
                    receiver = wifiReceiver,
                    filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION),
                )
            }
            ListenerGroup.BLUETOOTH -> {
                broadcastDispatcher.registerReceiver(
                    receiver = bluetoothReceiver,
                    filter = IntentFilter().apply {
                        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    },
                )
            }
            ListenerGroup.HEADPHONES -> {
                broadcastDispatcher.registerReceiver(
                    receiver = headphonesReceiver,
                    filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG),
                )
            }
            ListenerGroup.RINGER -> {
                broadcastDispatcher.registerReceiver(
                    receiver = ringerReceiver,
                    filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
                )
            }
            ListenerGroup.APP_LIFECYCLE -> {
                launcherPackage = resolveLauncherPackage()
                TaskStackChangeListeners.getInstance()
                    .registerTaskStackListener(taskStackListener)
            }
            ListenerGroup.FEATURE_STATE -> {
                stateManager.registerCallback(platformCallback)
            }
            ListenerGroup.SENSOR_PRIVACY -> {
                sensorPrivacyController.addCallback(sensorPrivacyCallback)
            }
            ListenerGroup.CAPTIVE_PORTAL -> {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            }
        }
    }

    private fun unregisterGroup(group: ListenerGroup) {
        if (group !in activeListeners) return
        activeListeners.remove(group)

        when (group) {
            ListenerGroup.BATTERY -> {
                batteryController.removeCallback(batteryCallback)
                lastBatteryLevel = -1
            }
            ListenerGroup.SCREEN -> {
                broadcastDispatcher.unregisterReceiver(screenReceiver)
            }
            ListenerGroup.WIFI -> {
                broadcastDispatcher.unregisterReceiver(wifiReceiver)
            }
            ListenerGroup.BLUETOOTH -> {
                broadcastDispatcher.unregisterReceiver(bluetoothReceiver)
            }
            ListenerGroup.HEADPHONES -> {
                broadcastDispatcher.unregisterReceiver(headphonesReceiver)
            }
            ListenerGroup.RINGER -> {
                broadcastDispatcher.unregisterReceiver(ringerReceiver)
            }
            ListenerGroup.APP_LIFECYCLE -> {
                TaskStackChangeListeners.getInstance()
                    .unregisterTaskStackListener(taskStackListener)
                currentForegroundPkg = null
            }
            ListenerGroup.FEATURE_STATE -> {
                stateManager.unregisterCallback(platformCallback)
            }
            ListenerGroup.SENSOR_PRIVACY -> {
                sensorPrivacyController.removeCallback(sensorPrivacyCallback)
            }
            ListenerGroup.CAPTIVE_PORTAL -> {
                runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
                lastCaptivePortalState = false
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> callback?.invoke(Trigger.ScreenState(on = true))
                Intent.ACTION_SCREEN_OFF -> callback?.invoke(Trigger.ScreenState(on = false))
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                WifiManager.EXTRA_NETWORK_INFO,
            )
            val connected = networkInfo?.isConnected ?: false
            val wifiManager = ctx.getSystemService(WifiManager::class.java)
            val ssid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
            callback?.invoke(Trigger.WifiState(connected, ssid))
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE,
            )
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    callback?.invoke(Trigger.BluetoothState(true, device?.address))
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    callback?.invoke(Trigger.BluetoothState(false, device?.address))
            }
        }
    }

    private val headphonesReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val plugged = intent.getIntExtra("state", 0) == 1
            callback?.invoke(Trigger.HeadphonesState(plugged))
        }
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mode = intent.getIntExtra(
                AudioManager.EXTRA_RINGER_MODE,
                AudioManager.RINGER_MODE_NORMAL,
            )
            callback?.invoke(Trigger.RingerMode(mode))
        }
    }

    private fun resolveLauncherPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        private const val TAG = "RoutinesEventTrigger"

        fun triggerToGroup(trigger: Trigger): ListenerGroup? = when (trigger) {
            is Trigger.ChargingState, is Trigger.BatteryLevel -> ListenerGroup.BATTERY
            is Trigger.ScreenState -> ListenerGroup.SCREEN
            is Trigger.WifiState -> ListenerGroup.WIFI
            is Trigger.BluetoothState -> ListenerGroup.BLUETOOTH
            is Trigger.HeadphonesState -> ListenerGroup.HEADPHONES
            is Trigger.RingerMode -> ListenerGroup.RINGER
            is Trigger.AppLaunch, is Trigger.AppClose -> ListenerGroup.APP_LIFECYCLE
            is Trigger.FeatureState -> ListenerGroup.FEATURE_STATE
            is Trigger.SensorPrivacyState -> ListenerGroup.SENSOR_PRIVACY
            is Trigger.CaptivePortal -> ListenerGroup.CAPTIVE_PORTAL
            is Trigger.TimeOfDay, is Trigger.Interval, is Trigger.Location -> null
        }
    }
}
