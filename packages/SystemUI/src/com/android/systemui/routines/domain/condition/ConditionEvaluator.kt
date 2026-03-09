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

package com.android.systemui.routines.domain.condition

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.android.systemui.ax.AxPlatformStateManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.routines.model.Condition
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController
import java.util.Calendar
import javax.inject.Inject

@SysUISingleton
class ConditionEvaluator @Inject constructor(
    @Application private val context: Context,
    private val batteryController: BatteryController,
    private val bluetoothController: BluetoothController,
    private val stateManager: AxPlatformStateManager,
    private val sensorPrivacyController: IndividualSensorPrivacyController,
    private val locationManager: LocationManager,
) {

    fun evaluateAll(conditions: List<Condition>): Boolean =
        conditions.all { evaluate(it) }

    fun evaluate(condition: Condition): Boolean = when (condition) {
        is Condition.TimeRange -> evaluateTimeRange(condition)
        is Condition.DayOfWeek -> evaluateDayOfWeek(condition)
        is Condition.BatteryRange -> evaluateBatteryRange(condition)
        is Condition.ChargingState -> evaluateChargingState(condition)
        is Condition.WifiConnected -> evaluateWifiConnected(condition)
        is Condition.BluetoothConnected -> evaluateBluetoothConnected(condition)
        is Condition.ScreenOn -> evaluateScreenOn(condition)
        is Condition.FeatureActive -> evaluateFeatureActive(condition)
        is Condition.SensorBlocked -> evaluateSensorBlocked(condition)
        is Condition.LocationNear -> evaluateLocationNear(condition)
    }

    private fun evaluateTimeRange(condition: Condition.TimeRange): Boolean {
        val cal = Calendar.getInstance()
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = condition.startHour * 60 + condition.startMinute
        val endMinutes = condition.endHour * 60 + condition.endMinute
        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    private fun evaluateDayOfWeek(condition: Condition.DayOfWeek): Boolean {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return today in condition.days
    }

    private fun evaluateBatteryRange(condition: Condition.BatteryRange): Boolean {
        val state = stateManager.getState(KEY_BATTERY)
        val level = state.getInt("level", -1)
        return level in condition.min..condition.max
    }

    private fun evaluateChargingState(condition: Condition.ChargingState): Boolean =
        batteryController.isPluggedIn == condition.charging

    private fun evaluateWifiConnected(condition: Condition.WifiConnected): Boolean {
        val state = stateManager.getState(FEATURE_WIFI)
        val isActive = state.getBoolean("active", false)
        if (!isActive) return false
        if (condition.ssid == null) return true
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return false
        val info = wifiManager.connectionInfo ?: return false
        val currentSsid = info.ssid?.removeSurrounding("\"")
        return currentSsid == condition.ssid
    }

    private fun evaluateBluetoothConnected(condition: Condition.BluetoothConnected): Boolean {
        if (!bluetoothController.isBluetoothConnected) return false
        if (condition.deviceAddress == null) return true
        return bluetoothController.connectedDevices.any { device ->
            device.address == condition.deviceAddress
        }
    }

    private fun evaluateScreenOn(condition: Condition.ScreenOn): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isInteractive == condition.on
    }

    private fun evaluateFeatureActive(condition: Condition.FeatureActive): Boolean {
        val state = stateManager.getState(condition.feature)
        return state.getBoolean("active", false) == condition.active
    }

    private fun evaluateSensorBlocked(condition: Condition.SensorBlocked): Boolean =
        sensorPrivacyController.isSensorBlocked(condition.sensor) == condition.blocked

    private fun evaluateLocationNear(condition: Condition.LocationNear): Boolean {
        val lastLocation = locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return false
        val target = Location("").apply {
            latitude = condition.latitude
            longitude = condition.longitude
        }
        return lastLocation.distanceTo(target) <= condition.radiusMeters
    }

    companion object {
        private const val KEY_BATTERY = "battery"
        private const val FEATURE_WIFI = "wifi"
    }
}
