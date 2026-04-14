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
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.PowerManager
import android.util.Log
import java.net.InetAddress
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
    private val connectivityManager: ConnectivityManager,
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
        is Condition.IpAddress -> evaluateIpAddress(condition)
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
        var level = stateManager.getState(KEY_BATTERY).getInt("level", -1)
        if (level < 0) {
            val bm = context.getSystemService(BatteryManager::class.java)
            level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }
        if (level < 0) return false
        return level in condition.min..condition.max
    }

    private fun evaluateChargingState(condition: Condition.ChargingState): Boolean =
        batteryController.isPluggedIn == condition.charging

    private fun evaluateWifiConnected(condition: Condition.WifiConnected): Boolean {
        val state = stateManager.getState(FEATURE_WIFI)
        val isActive = state.getBoolean("active", false)
        if (!isActive) return false
        if (condition.ssid == null && condition.ssidPattern == null) return true
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return false
        val currentSsid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: return false
        if (condition.ssidPattern != null) {
            return runCatching { Regex(condition.ssidPattern).containsMatchIn(currentSsid) }
                .getOrDefault(false)
        }
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

    private fun evaluateIpAddress(condition: Condition.IpAddress): Boolean {
        val token = Binder.clearCallingIdentity()
        try {
            return evaluateIpAddressInternal(condition)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun evaluateIpAddressInternal(condition: Condition.IpAddress): Boolean {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.d(TAG, "IP condition: no active network")
            return false
        }
        val linkProperties = connectivityManager.getLinkProperties(network)
        if (linkProperties == null) {
            Log.d(TAG, "IP condition: no link properties")
            return false
        }
        val parts = condition.cidr.split("/")
        if (parts.size != 2) {
            Log.d(TAG, "IP condition: invalid CIDR format: ${condition.cidr}")
            return false
        }
        val cidrAddress = runCatching { InetAddress.getByName(parts[0]) }.getOrNull()
        if (cidrAddress == null) {
            Log.d(TAG, "IP condition: cannot resolve CIDR address: ${parts[0]}")
            return false
        }
        val prefixLength = parts[1].toIntOrNull()
        if (prefixLength == null) {
            Log.d(TAG, "IP condition: invalid prefix length: ${parts[1]}")
            return false
        }
        val cidrBytes = cidrAddress.address
        val matched = linkProperties.linkAddresses.any { linkAddr ->
            val addrBytes = linkAddr.address.address
            if (addrBytes.size != cidrBytes.size) return@any false
            matchesCidr(addrBytes, cidrBytes, prefixLength)
        }
        Log.d(TAG, "IP condition: cidr=${condition.cidr} " +
            "addrs=${linkProperties.linkAddresses.map { it.address.hostAddress }} " +
            "matched=$matched")
        return matched
    }

    private fun matchesCidr(address: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (i in 0 until fullBytes) {
            if (address[i] != network[i]) return false
        }
        if (remainingBits > 0 && fullBytes < address.size) {
            val mask = (0xFF shl (8 - remainingBits)).toByte()
            if ((address[fullBytes].toInt() and mask.toInt()) !=
                (network[fullBytes].toInt() and mask.toInt())) return false
        }
        return true
    }

    companion object {
        private const val TAG = "RoutinesCondition"
        private const val KEY_BATTERY = "battery"
        private const val FEATURE_WIFI = "wifi"
    }
}
